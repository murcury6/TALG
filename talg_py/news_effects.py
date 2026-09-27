"""Trainable per-article effects, count-normalized aggregation, and named outcome heads.

Weights are stored as inert NPZ arrays, not executable pickle. A fitted candidate
must be explicitly pinned before a News profile uses it.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import time
from pathlib import Path

import numpy as np

from .news_effect_data import feature_contract, load_example
from .news_tensor import atomic_json, atomic_npz, utc


def chronological_split(rows, minimum):
    rows = sorted(rows, key=lambda row: (row["cutoff"], row["symbol"]))
    dates = sorted({r["date"] for r in rows})
    if len(dates) < sum(minimum.values()):
        raise ValueError("Not enough distinct observation dates for train/validation/test")
    first, second = dates[int(len(dates) * .6)], dates[int(len(dates) * .8)]
    validation_start = min(r["cutoff"] for r in rows if r["date"] >= first)
    test_start = min(r["cutoff"] for r in rows if r["date"] >= second)
    groups = {
        "train": [r for r in rows if r["date"] < first and r["label_end"] < validation_start],
        "validation": [r for r in rows if first <= r["date"] < second and r["label_end"] < test_start],
        "test": [r for r in rows if r["date"] >= second],
    }
    for name, group in groups.items():
        if len({r["date"] for r in group}) < minimum[name]:
            raise ValueError("Too few " + name + " dates after purging overlapping outcome windows")
    return groups


def make_network(dimension, width, heads):
    import torch
    class Network(torch.nn.Module):
        def __init__(self):
            super().__init__()
            self.article = torch.nn.Linear(dimension, width)
            self.head = torch.nn.Linear(width, heads)

        def forward(self, x, weights):
            features = torch.tanh(self.article(x))
            aggregate = (features * weights[:, None]).sum(dim=0) / len(x)
            return self.head(aggregate)
    return Network()


def transformed_targets(raw):
    values = np.asarray(raw, dtype=np.float32).copy()
    values[:, 2::3] = np.log(np.maximum(values[:, 2::3], 1e-6))
    return values


def loss_for(predicted, targets, mean, scale):
    import torch
    losses = []
    for index in range(len(targets)):
        if index % 3 == 1:
            losses.append(torch.nn.functional.binary_cross_entropy_with_logits(predicted[index], targets[index]))
        else:
            losses.append((predicted[index] - (targets[index] - mean[index]) / scale[index]) ** 2)
    return torch.stack(losses).mean()


def fit(dataset_path, output, *, width=None, evaluate_test=False):
    import torch
    path, output = Path(dataset_path), Path(output)
    raw = path.read_bytes()
    dataset = json.loads(raw)
    config = dict(dataset["training"])
    if width is not None:
        config["hidden_dimensions"] = width
    rows = dataset["rows"]
    if not rows:
        raise ValueError("No matured outcome labels: collect prospective snapshots and adjusted closes first")
    for row in rows:
        if row["feature_contract"] != dataset["feature_contract"] or not row["cutoff"] < row["label_start"] < row["label_end"] <= time.time():
            raise ValueError("Mixed feature contracts, invalid label ordering or unmatured outcomes")
        y = np.asarray(row["targets"])
        if len(y) != 3 * len(config["horizons_sessions"]) or not np.isfinite(y).all() or np.any(y[2::3] < 0) or np.any((y[1::3] != 0) & (y[1::3] != 1)):
            raise ValueError("Invalid outcome targets")
    groups = chronological_split(rows, config["minimum_dates"])
    torch.set_num_threads(4)
    torch.manual_seed(config["seed"])
    train_y = transformed_targets([r["targets"] for r in groups["train"]])
    mean = torch.tensor(train_y.mean(axis=0))
    scale = torch.tensor(np.maximum(train_y.std(axis=0), 1e-6))
    dimension = load_example(rows[0])[0].shape[1]
    width = config["hidden_dimensions"]
    if type(width) is not int or not 1 <= width <= 100000:
        raise ValueError("Effect width must be between 1 and 100000")
    model = make_network(dimension, width, train_y.shape[1])
    optimizer = torch.optim.AdamW(model.parameters(), lr=config["learning_rate"], weight_decay=config["weight_decay"])
    generator = np.random.default_rng(config["seed"])

    def evaluate(group):
        losses, forecasts = [], []
        with torch.no_grad():
            for row in group:
                x, weights = load_example(row)
                pred = model(torch.from_numpy(x), torch.from_numpy(weights))
                target = torch.from_numpy(transformed_targets([row["targets"]])[0])
                losses.append(float(loss_for(pred, target, mean, scale)))
                forecasts.append(pred.numpy().copy())
        return float(np.mean(losses)), np.asarray(forecasts)

    best, state, epoch_chosen = float("inf"), None, None
    history = []
    for epoch in range(config["epochs"]):
        model.train()
        for index in generator.permutation(len(groups["train"])):
            row = groups["train"][index]
            x, weights = load_example(row)
            target = torch.from_numpy(transformed_targets([row["targets"]])[0])
            optimizer.zero_grad()
            loss = loss_for(model(torch.from_numpy(x), torch.from_numpy(weights)), target, mean, scale)
            if not torch.isfinite(loss):
                raise ValueError("Nonfinite training loss")
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 5.0)
            optimizer.step()
        model.eval()
        validation, _ = evaluate(groups["validation"])
        history.append({"epoch": epoch + 1, "validation_loss": validation})
        print(f"Effect training epoch {epoch + 1}/{config['epochs']}: validation loss {validation:.6g}", flush=True)
        if validation < best:
            best, epoch_chosen = validation, epoch + 1
            state = {k: v.detach().clone() for k, v in model.state_dict().items()}
    if state is None:
        raise ValueError("Training produced no finite checkpoint")
    model.load_state_dict(state)
    test_loss, predicted = evaluate(groups["test"]) if evaluate_test else (None, None)
    target = np.asarray([r["targets"] for r in groups["test"]])
    means, scales = mean.numpy(), scale.numpy()
    reports = []
    baseline = np.mean([r["targets"] for r in groups["train"]], axis=0)
    for index, horizon in enumerate(config["horizons_sessions"] if evaluate_test else []):
        at = index * 3
        returns = predicted[:, at] * scales[at] + means[at]
        probabilities = 1 / (1 + np.exp(-np.clip(predicted[:, at + 1], -60, 60)))
        variation = np.exp(np.clip(predicted[:, at + 2] * scales[at + 2] + means[at + 2], -30, 20))
        reports.append({"horizon_sessions": horizon,
            "return_mse": float(np.mean((returns - target[:, at]) ** 2)),
            "constant_return_mse": float(np.mean((baseline[at] - target[:, at]) ** 2)),
            "direction_brier": float(np.mean((probabilities - target[:, at + 1]) ** 2)),
            "base_rate_brier": float(np.mean((baseline[at + 1] - target[:, at + 1]) ** 2)),
            "variation_mae": float(np.mean(np.abs(variation - target[:, at + 2])))})
    output.mkdir(parents=True, exist_ok=False)
    (output / "dataset.json").write_bytes(raw)
    weights_path = output / "weights.npz"
    atomic_npz(weights_path, article_weight=state["article.weight"].numpy(), article_bias=state["article.bias"].numpy(),
               head_weight=state["head.weight"].numpy(), head_bias=state["head.bias"].numpy(), mean=means, scale=scales)
    metadata = {"schema_version": 1, "algorithm": "tanh_article_count_average_multitask_v1",
        "status": "fitted_research_candidate_not_validated", "trained_at": utc(time.time()),
        "input_dimensions": dimension, "effect_dimensions": width, "training": config,
        "feature_contract": dataset["feature_contract"], "dataset_sha256": hashlib.sha256(raw).hexdigest(),
        "weights_sha256": hashlib.sha256(weights_path.read_bytes()).hexdigest(),
        "split_counts": {k: {"rows": len(v), "dates": len({r['date'] for r in v})} for k, v in groups.items()},
        "chosen_epoch": epoch_chosen, "validation_history": history, "test_loss": test_loss, "test_metrics": reports, "test_evaluated": evaluate_test,
        "limitation": "Research forecasts, uncalibrated probabilities; constant/base-rate comparisons only. No price-only incremental-skill, regime robustness, costs or causal-effect claims."}
    atomic_json(output / "model.json", metadata)
    atomic_json(output / "split-provenance.json", {k: [{f: r[f] for f in ("symbol", "cutoff", "label_start", "label_end", "tensor_sha256")} for r in v] for k, v in groups.items()})
    return metadata


class EffectModel:
    def __init__(self, root, pin, config):
        model_path = Path(root) / pin["model_file"]
        raw = model_path.read_bytes()
        if hashlib.sha256(raw).hexdigest() != pin["model_sha256"]:
            raise ValueError("Effect model metadata checksum mismatch")
        self.metadata = json.loads(raw)
        if self.metadata["algorithm"] != "tanh_article_count_average_multitask_v1" or self.metadata["status"] != "fitted_research_candidate_not_validated":
            raise ValueError("Unsupported or unfitted effect model")
        if self.metadata["feature_contract"] != feature_contract(config):
            raise ValueError("Effect model was fitted to different news features/weights/decay")
        weights = model_path.parent / "weights.npz"
        if hashlib.sha256(weights.read_bytes()).hexdigest() != self.metadata["weights_sha256"]:
            raise ValueError("Effect weights checksum mismatch")
        with np.load(weights, allow_pickle=False) as saved:
            self.arrays = {key: saved[key].copy() for key in saved.files}
        if any(not np.isfinite(a).all() for a in self.arrays.values()):
            raise ValueError("Nonfinite fitted model")
        dimension, width = self.metadata["input_dimensions"], self.metadata["effect_dimensions"]
        heads = 3 * len(self.metadata["training"]["horizons_sessions"])
        expected = {"article_weight": (width, dimension), "article_bias": (width,),
                    "head_weight": (heads, width), "head_bias": (heads,), "mean": (heads,), "scale": (heads,)}
        if set(self.arrays) != set(expected) or any(self.arrays[k].shape != shape for k, shape in expected.items()):
            raise ValueError("Fitted weight shapes do not match metadata")
        if np.any(self.arrays["scale"] <= 0):
            raise ValueError("Invalid target scaling")

    def predict(self, vectors, effective_weights):
        a = self.arrays
        vectors = np.asarray(vectors, dtype=np.float32)
        weights = np.asarray(effective_weights, dtype=np.float32)
        if vectors.ndim != 2 or vectors.shape[1] != self.metadata["input_dimensions"] or weights.shape != (len(vectors),):
            raise ValueError("Effect input shape mismatch")
        if not np.isfinite(vectors).all() or not np.isfinite(weights).all() or np.any((weights < 0) | (weights > 1)):
            raise ValueError("Invalid effect input values")
        if not len(vectors):
            return None, None, []
        features = np.tanh(np.einsum("nd,kd->nk", vectors, a["article_weight"], optimize=False) + a["article_bias"])
        aggregate = np.einsum("n,nk->k", weights, features, optimize=False) / len(vectors)
        raw = np.einsum("hk,k->h", a["head_weight"], aggregate, optimize=False) + a["head_bias"]
        if not np.isfinite(raw).all():
            raise ValueError("Nonfinite effect output")
        outputs = []
        for i, horizon in enumerate(self.metadata["training"]["horizons_sessions"]):
            j = i * 3
            outputs.append({"horizon_sessions": horizon,
                "expected_market_relative_log_return": float(raw[j] * a["scale"][j] + a["mean"][j]),
                "positive_relative_return_probability": float(1 / (1 + np.exp(-np.clip(raw[j + 1], -60, 60)))),
                "relative_daily_variation": float(np.exp(np.clip(raw[j + 2] * a["scale"][j + 2] + a["mean"][j + 2], -30, 20))),
                "calibration": "not_established"})
        return features, aggregate, outputs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--width", type=int, help="Train a different learned width on the same dataset/splits")
    parser.add_argument("--evaluate-test", action="store_true", help="Use only after selecting width/settings on validation data")
    args = parser.parse_args()
    result = fit(args.dataset, args.out, width=args.width, evaluate_test=args.evaluate_test)
    print(json.dumps({"status": result["status"], "effect_dimensions": result["effect_dimensions"], "test_metrics": result["test_metrics"]}, indent=2))


if __name__ == "__main__":
    main()

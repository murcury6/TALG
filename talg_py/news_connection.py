"""Independent, inspectable symbol/story connection weights and supervised fitting.

Initial coefficients are hand-set priors, not fitted financial evidence. Targets
are connection-strength judgments, never future returns or sentiment labels.
"""
from __future__ import annotations

import argparse
import copy
import json
import math
import time
from pathlib import Path

import numpy as np

from .news_tensor import atomic_json, digest, utc

FEATURES = ["ai_best_score_scaled", "direct_anchor", "supply_chain_link", "sector_link", "macro_link"]
ALGORITHM = "logistic_connection_v1"


def sigmoid(values):
    values = np.asarray(values, dtype=np.float64)
    return np.exp(-np.logaddexp(0, -values))


class ConnectionModel:
    def __init__(self, config):
        self.config = copy.deepcopy(config)
        if config["schema_version"] != 1 or config["algorithm"] != ALGORITHM or config["features"] != FEATURES:
            raise ValueError("Unsupported connection-weight contract")
        expected = {"score_divisor": 10.0, "score_clip": 2.0, "missing_ai": "error",
                    "evidence_flags": "binary; repeated evidence does not add weight"}
        if config["feature_contract"] != expected:
            raise ValueError("Unsupported connection feature definition")
        self.coefficients = np.asarray(config["coefficients"], dtype=np.float64)
        self.intercept = float(config["intercept"])
        if self.coefficients.shape != (len(FEATURES),) or not np.isfinite(self.coefficients).all() or not math.isfinite(self.intercept):
            raise ValueError("Connection parameters must be finite and match feature order")
        self.version = digest(json.dumps(config, sort_keys=True))

    def features(self, article):
        ai = article.get("relevance_ai")
        if not ai or not math.isfinite(float(ai["score"])):
            raise ValueError("Connection model requires finite AI relevance evidence")
        kinds = {r["kind"] for r in article["relations"]}
        return np.array([
            np.clip(float(ai["score"]) / 10.0, -2.0, 2.0),
            bool(kinds & {"direct_association", "company_reference"}),
            bool(kinds & {"supplier_event", "customer_event"}),
            bool(kinds & {"sector_topic", "sector_peer_event"}),
            "macro_exposure" in kinds,
        ], dtype=np.float64)

    def predict(self, symbol, article):
        features = self.features(article)
        weight = float(sigmoid(self.intercept + np.dot(features, self.coefficients)))
        return {"symbol": symbol, "information_id": article["id"], "content_hash": article["content_hash"],
                "value": weight, "model_version": self.version, "status": self.config["status"],
                "features": dict(zip(FEATURES, features.tolist(), strict=True)),
                "relevance_model_version": article["relevance_ai"]["model_version"],
                "evaluated_at": time.time(), "meaning": self.config["target"], "is_probability": False}


def fit_model(config, records, *, l2=0.01, steps=2000, learning_rate=0.1):
    """Fit soft-label logistic regression; does not select decay or assess trading.

Records must contain archived feature values and manually assigned connection
strengths. Caller must hold out independent events/dates for later evaluation.
"""
    model = ConnectionModel(config)
    if not records or not math.isfinite(l2) or l2 < 0 or type(steps) is not int or steps < 1 or not math.isfinite(learning_rate) or learning_rate <= 0:
        raise ValueError("Invalid connection fitting inputs")
    if any(r.get("feature_contract") != config["feature_contract"] for r in records):
        raise ValueError("Training feature contracts must match the model")
    if any(r.get("label_kind") != "human_connection_strength" or not r.get("labeler") or not r.get("symbol") or not r.get("information_id") or not r.get("content_hash") or not r.get("relevance_model_version") for r in records):
        raise ValueError("Training needs labeled symbol/article revisions and relevance provenance")
    keys = [(r["symbol"], r["information_id"], r["content_hash"]) for r in records]
    if len(set(keys)) != len(keys):
        raise ValueError("Duplicate training pairs; reconcile labels before fitting")
    matrix = np.asarray([[r["features"][name] for name in FEATURES] for r in records], dtype=np.float64)
    targets = np.asarray([r["target"] for r in records], dtype=np.float64)
    if not np.isfinite(matrix).all() or not np.isfinite(targets).all() or np.any((targets < 0) | (targets > 1)):
        raise ValueError("Targets must be finite connection strengths in [0,1]")
    if np.any(np.abs(matrix[:, 0]) > 2) or np.any((matrix[:, 1:] != 0) & (matrix[:, 1:] != 1)):
        raise ValueError("Features violate the pinned definition")
    beta, bias = model.coefficients.copy(), model.intercept
    for _ in range(steps):
        error = sigmoid(matrix @ beta + bias) - targets
        beta -= learning_rate * (matrix.T @ error / len(targets) + l2 * beta)
        bias -= learning_rate * error.mean()
    logits = matrix @ beta + bias
    result = copy.deepcopy(config)
    result.update(coefficients=beta.tolist(), intercept=float(bias), status="fitted_on_labeled_connections_not_independently_validated")
    result["training"] = {"dataset_sha256": digest(json.dumps(records, sort_keys=True)), "rows": len(records),
        "label_kind": "human_connection_strength", "l2": l2, "steps": steps, "learning_rate": learning_rate,
        "fitted_at": utc(time.time()), "training_soft_label_log_loss": float(np.mean(np.logaddexp(0, logits) - targets * logits)),
        "validation": "not_performed", "decay_experiment": "not_started"}
    ConnectionModel(result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--labels", type=Path, required=True, help="JSON array of human-labeled archived connection features")
    parser.add_argument("--output", type=Path, required=True, help="New model file; existing files are not overwritten")
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Choose a new output file to preserve saved model revisions")
    result = fit_model(json.loads(args.model.read_text()), json.loads(args.labels.read_text()))
    atomic_json(args.output, result)
    print(json.dumps(result["training"], indent=2))


if __name__ == "__main__":
    main()

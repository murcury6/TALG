"""Point-in-time news examples and future close-to-close outcome labels."""
from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from itertools import pairwise
from pathlib import Path

import numpy as np

from .model_profiles import revision, runtime
from .news_tensor import atomic_json, digest


def stamp(value):
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("Timestamps must include timezone")
    return parsed.timestamp()


def feature_contract(config):
    config = dict(config)
    config.pop("effect_model", None)
    return digest(json.dumps(config, sort_keys=True))


def load_example(row):
    path = Path(row["tensor_file"])
    if hashlib.sha256(path.read_bytes()).hexdigest() != row["tensor_sha256"]:
        raise ValueError("Training source snapshot changed")
    with np.load(path, allow_pickle=False) as saved:
        x = saved["article_vectors"].astype(np.float32)
        a = saved["effective_weights"].astype(np.float32)
        if int(saved["n"]) != len(x) or not len(x) or a.shape != (len(x),):
            raise ValueError("Invalid news example shape/count")
        if np.max(saved["available_at"]) > row["cutoff"]:
            raise ValueError("Future information in training inputs")
        if not np.isfinite(x).all() or not np.isfinite(a).all() or np.any((a < 0) | (a > 1)):
            raise ValueError("Invalid news example values")
        if str(saved["symbol"]) != row["symbol"]:
            raise ValueError("Training symbol mismatch")
    return x, a


def read_prices(path):
    result = {}
    for row in json.loads(Path(path).read_text(encoding="utf-8-sig")):
        if row["adjustment"] != "split_and_dividend" or not row.get("source"):
            raise ValueError("Labels require documented split/dividend-adjusted session closes")
        close = float(row["adjusted_close"])
        if not np.isfinite(close) or close <= 0:
            raise ValueError("Invalid adjusted price")
        at = stamp(row["close_time"])
        date = datetime.fromisoformat(row["session"]).date().isoformat()
        result.setdefault(row["symbol"], []).append((date, at, close))
    for rows in result.values():
        rows.sort()
        if len({r[0] for r in rows}) != len(rows) or any(b[1] <= a[1] for a, b in pairwise(rows)):
            raise ValueError("Duplicate or nonmonotonic price sessions")
    return result


def outcomes(row, prices, config):
    stock = prices.get(row["symbol"], [])
    market = prices.get(config["benchmark"], [])
    # Never hide missing symbol sessions by silently shortening the horizon.
    calendar = [r for r in market if r[1] > row["cutoff"]]
    horizons = config["horizons_sessions"]
    if len(calendar) <= max(horizons):
        return None
    calendar = calendar[:max(horizons) + 1]
    by_date = {r[0]: r for r in stock}
    if any(r[0] not in by_date or by_date[r[0]][1] != r[1] for r in calendar):
        return None
    relative = np.log([by_date[r[0]][2] for r in calendar]) - np.log([r[2] for r in calendar])
    targets = []
    for h in horizons:
        change = float(relative[h] - relative[0])
        variation = float(np.sqrt(np.sum(np.diff(relative[:h + 1]) ** 2)))
        targets.extend([change, float(change > 0), variation])
    return {"targets": targets, "label_start": calendar[0][1], "label_end": calendar[-1][1],
            "label_start_session": calendar[0][0], "label_end_session": calendar[-1][0]}


def build(root, reference, output, prices_path=None):
    root, output = Path(root).resolve(), Path(output)
    manifest = revision(root, reference, "news")
    pipeline = json.loads(manifest["contents"]["config"])["pipeline"]
    config = pipeline["effect_model"]["training"]
    prices = read_prices(prices_path) if prices_path else {}
    price_hash, price_snapshot = None, None
    if prices_path:
        price_bytes = Path(prices_path).read_bytes()
        price_hash = hashlib.sha256(price_bytes).hexdigest()
        price_snapshot = root / "work/news-effect-labels/snapshots" / (price_hash + ".json")
        price_snapshot.parent.mkdir(parents=True, exist_ok=True)
        if not price_snapshot.exists():
            price_snapshot.write_bytes(price_bytes)
        elif hashlib.sha256(price_snapshot.read_bytes()).hexdigest() != price_hash:
            raise ValueError("Archived label prices were modified")
    rows, pending, contracts = [], [], set()
    seen_days = set()
    for summary_path in sorted((runtime(root, reference) / "weighted-news/runs").glob("*/summary.json")):
        folder = summary_path.parent
        summary = json.loads(summary_path.read_text(encoding="utf-8"))
        cfg = json.loads((folder / "model.json").read_text(encoding="utf-8"))
        contract = feature_contract(cfg)
        for symbol, detail in summary["symbols"].items():
            path = folder / detail["tensor_file"]
            with np.load(path, allow_pickle=False) as data:
                if int(data["n"]) == 0:
                    continue
                cutoff = max(stamp(summary["completed_at"]), float(np.max(data["available_at"])))
            day = datetime.fromtimestamp(cutoff, timezone.utc).date().isoformat()
            if (symbol, day) in seen_days:
                continue
            seen_days.add((symbol, day))
            row = {"symbol": symbol, "cutoff": cutoff, "date": day, "tensor_file": str(path.resolve()),
                   "tensor_sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "feature_contract": contract,
                   "profile_revision": reference, "run_id": summary["run_id"]}
            load_example(row)
            labels = outcomes(row, prices, config)
            if labels is None:
                pending.append(row)
            else:
                rows.append(dict(row, **labels))
            contracts.add(contract)
    if len(contracts) > 1:
        raise ValueError("Incompatible pipeline contracts in one dataset")
    value = {"schema_version": 1, "state": "labeled" if rows else "awaiting_outcome_labels",
             "training": config, "feature_contract": next(iter(contracts), None), "rows": rows, "pending": pending,
             "price_file_sha256": price_hash, "price_snapshot_file": str(price_snapshot) if price_snapshot else None,
             "label_definition": "From first benchmark session close strictly after snapshot availability to h subsequent session closes; symbol minus benchmark log return, its positive indicator, and sqrt(sum squared daily relative log returns). Not annualized volatility or a causal news effect.",
             "sampling": "Earliest saved snapshot per symbol per UTC date; chronological purging still required"}
    atomic_json(output, value)
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--revision", required=True)
    parser.add_argument("--prices", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    result = build(args.root, args.revision, args.out, args.prices)
    print(json.dumps({"state": result["state"], "labeled": len(result["rows"]), "pending": len(result["pending"])}))


if __name__ == "__main__":
    main()

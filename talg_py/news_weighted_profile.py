"""Weighted information tensors connected to immutable named News profiles."""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import time
from pathlib import Path

import numpy as np

from .model_language import Program
from .model_profiles import revision, revision_folder, runtime, runtime_lock
from .news_features import feature_layout
from .news_tensor import atomic_json, utc
from .symbol_news_tensor import run, symbol_name

ENGINE = "symbol_weighted_tensor_v1"


def is_weighted(config):
    return config.get("engine") == ENGINE


def settings(contents):
    config = json.loads(contents["config"])
    if config.get("version") != 1 or not is_weighted(config):
        raise ValueError("Unsupported weighted News profile")
    symbols = config.get("symbols")
    if not isinstance(symbols, list) or not symbols or any(not isinstance(s, str) or symbol_name(s) != s for s in symbols):
        raise ValueError("Declare at least one uppercase symbol in weighted News settings")
    if len(set(symbols)) != len(symbols):
        raise ValueError("Duplicate News symbols")
    pipeline = copy.deepcopy(config["pipeline"])
    # Only bytes from the immutable manifest are read, never today's dependency files.
    pipeline["relatedness_snapshot"] = json.loads(contents["relatedness"])
    pipeline["connection_weight_snapshot"] = json.loads(contents["connection_weight"])
    program = Program(contents["source"])
    if program.symbol is None:
        raise ValueError("Weighted News requires a symbol output program")
    return config, pipeline, program


def ingest_retained(root):
    """Bring retained collector files into the shared archive without the old encoder."""
    from .news_tensor import Store
    lock = runtime_lock(root / "work/news-tensor")
    try:
        lock.__enter__()
    except (BlockingIOError, PermissionError):
        return "ingestion_lock_unavailable_using_committed_archive"
    try:
        store = Store(root / "work/news-tensor")
        try:
            store.scan(root)
        finally:
            store.db.close()
    finally:
        lock.__exit__(None, None, None)
    return "retained_collector_files_ingested"


def refresh(root, reference):
    root = Path(root).resolve()
    contents = revision(root, reference, "news")["contents"]
    config, pipeline, program = settings(contents)
    folder = runtime(root, reference)
    with runtime_lock(folder):
        atomic_json(folder / "status.json", {"state": "PROCESSING", "profile_revision": reference, "as_of_utc": utc(time.time())})
        try:
            ingestion = ingest_retained(root)
            summary = run(root, config["symbols"], "frozen-profile", config_snapshot=pipeline,
                          output_path=folder / "weighted-news")
            run_folder = folder / "weighted-news/runs" / summary["run_id"]
            result = {"model_version": program.version, "profile_revision": reference, "engine": ENGINE,
                      "run_id": summary["run_id"], "config_version": summary["config_version"],
                      "evaluated_at": time.time(), "symbols": {}, "direction_prediction": None, "ingestion": ingestion}
            errors = 0
            for symbol, detail in summary["symbols"].items():
                tensor_file = run_folder / detail["tensor_file"]
                articles = json.loads((run_folder / (symbol + ".articles.json")).read_text(encoding="utf-8-sig"))
                with np.load(tensor_file, allow_pickle=False) as tensor:
                    vector = tensor["news_tensor"].tolist()
                    tensor_ref = {"file": str(tensor_file), "field": "news_tensor", "dimensions": len(vector),
                                  "profile_revision": reference, "run_id": summary["run_id"],
                                  "config_version": summary["config_version"], "as_of_utc": detail["as_of_utc"],
                                  "state": detail["state"], "denominator": pipeline["denominator"],
                                  "effect_state": detail.get("effect_state", "awaiting_outcome_labels"),
                                  "effects": detail.get("forecasts", []), "effect_tensor": detail.get("effect_tensor"),
                                  "source_captured_at": summary["source_captured_at"],
                                  "newest_first_seen": utc(max(a["first_seen"] for a in articles)) if articles else None}
                try:
                    outputs = program.symbol.run({"symbol": symbol, "articles": articles, "now": result["evaluated_at"],
                                                  "news_tensor": vector, "tensor_reference": tensor_ref})
                    error = ""
                except (ValueError, TypeError, KeyError, ArithmeticError) as problem:
                    outputs, error = {}, str(problem)
                    errors += 1
                result["symbols"][symbol] = {"outputs": outputs, "article_count": len(articles),
                    "article_ids": [a["id"] for a in articles], "tensor": tensor_ref, "error": error}
            # Single publication point. Readers resolve all evidence through this run ID.
            atomic_json(folder / "symbol-ratings.json", result)
            total = sum(v["article_count"] for v in result["symbols"].values())
            status = {"state": "ERROR" if errors else "READY", "profile_revision": reference, "as_of_utc": utc(time.time()),
                      "run_id": summary["run_id"], "encoded_items": total, "pending_items": 0,
                      "symbol_count": len(result["symbols"]), "dimensions": len(vector),
                      "errors": errors, "update_mode": "on_demand", "connection_status": pipeline["connection_weight_snapshot"]["status"]}
            status["effect_state"] = "fitted_research_candidate_not_validated" if pipeline.get("effect_model", {}).get("artifact") else "awaiting_outcome_labels"
            status["planned_effect_dimensions"] = pipeline.get("effect_model", {}).get("training", {}).get("hidden_dimensions")
            if "effect_model" in pipeline:
                from .news_effect_data import build
                prices = root / "work/news-effect-labels/adjusted-closes.json"
                try:
                    dataset = build(root, reference, folder / "effect-dataset.json", prices if prices.exists() else None)
                    status["training_data"] = {"state": dataset["state"], "labeled": len(dataset["rows"]), "pending": len(dataset["pending"])}
                except (ValueError, OSError, KeyError, TypeError) as problem:
                    status["training_data"] = {"state": "data_error", "error": str(problem)}
            atomic_json(folder / "status.json", status)
            return dict(status, rated=total, model_version=program.version)
        except Exception as error:
            atomic_json(folder / "status.json", {"state": "ERROR", "error": str(error), "profile_revision": reference, "as_of_utc": utc(time.time())})
            raise


def snapshot(root, reference):
    value = revision(root, reference, "news")
    folder = runtime(root, reference)
    path = folder / "symbol-ratings.json"
    if not path.exists():
        raise ValueError("Run this weighted News profile first")
    result = json.loads(path.read_text(encoding="utf-8-sig"))
    if result.get("profile_revision") != reference or result.get("model_version") != Program(value["contents"]["source"]).version:
        raise ValueError("Weighted output belongs to another profile or source revision")
    return result


def call(root, symbols, reference):
    result = snapshot(root, reference)
    result["symbols"] = {symbol: result["symbols"].get(symbol, {"outputs": {}, "article_count": 0,
        "error": "Symbol not materialized by this profile; add it in Settings and Run"}) for symbol in symbols}
    return result


def load_tensor(root, reference, symbol):
    """Downstream Python consumer: exact pinned revision and saved output, no recomputation."""
    symbol = symbol_name(symbol)
    result = snapshot(root, reference)
    item = result["symbols"].get(symbol)
    if item is None or item.get("error"):
        raise ValueError("No valid weighted tensor for this symbol")
    expected = runtime(root, reference) / "weighted-news/runs" / result["run_id"] / (symbol + ".npz")
    if Path(item["tensor"]["file"]).resolve() != expected.resolve():
        raise ValueError("Tensor path does not belong to this profile's published run")
    with np.load(expected, allow_pickle=False) as saved:
        if str(saved["config_version"]) != result["config_version"] or str(saved["symbol"]) != symbol:
            raise ValueError("Tensor provenance mismatch")
        return saved["news_tensor"].copy(), item["tensor"]


def data(root, reference, offset=0, search=""):
    try:
        result = snapshot(root, reference)
    except ValueError as error:
        if not (runtime(root, reference) / "symbol-ratings.json").exists():
            return {"columns": [], "rows": [], "total": 0, "message": str(error)}
        raise
    folder = runtime(root, reference) / "weighted-news/runs" / result["run_id"]
    rows = []
    for symbol in result["symbols"]:
        for article in json.loads((folder / (symbol + ".articles.json")).read_text(encoding="utf-8-sig")):
            if search.casefold() not in (symbol + " " + article["title"] + " " + article["publisher"]).casefold():
                continue
            rows.append(dict(article, symbol=symbol, headline=article["title"],
                             weight=article["connection_weight"]["value"],
                             relevance=article["relevance_ai"]["state"],
                             tensor_dimensions=result["symbols"][symbol]["tensor"]["dimensions"],
                             encoded_tokens=article.get("encoding", {}).get("covered_article_tokens"),
                             chunks=article.get("encoding", {}).get("chunk_count")))
    rows.sort(key=lambda a: (-a["event_time"], a["symbol"], a["id"]))
    return {"columns": ["symbol", "headline", "weight", "relevance", "tensor_dimensions", "encoded_tokens", "chunks", "publisher"],
            "rows": rows[offset:offset + 200], "offset": offset, "total": len(rows),
            "message": "Retained symbol/article weights and evidence; Symbol lookup shows the aggregate output",
            "run_id": result["run_id"], "profile_revision": reference}


def install(root, symbols):
    root = Path(root).resolve()
    index = root / "work/model-profiles/profiles.json"
    original = index.read_bytes()
    profiles = json.loads(original)
    source_folder = root / "research/news-average"
    pipeline = json.loads((source_folder / "model.json").read_text(encoding="utf-8-sig"))
    layout = feature_layout(pipeline)
    semantic_only = layout["kind"] == "semantic_only"
    upgraded = pipeline["encoder"].get("backend") == "onnx_chunked_cls_v2"
    profile_id = "learned-news-v2" if upgraded else ("weighted-news-semantic" if semantic_only else f"weighted-news-{layout['dimensions']}")
    name = "Learned news v2" if upgraded else ("Weighted semantic news" if semantic_only else f"Weighted news {layout['dimensions']}")
    if profile_id in profiles["profiles"]["news"]:
        raise ValueError("Weighted profile already exists; edit its saved files rather than overwriting")
    config = {"version": 1, "engine": ENGINE, "symbols": [symbol_name(s) for s in symbols],
              "refresh_mode": "on_demand", "pipeline": pipeline}
    contents = {"config": json.dumps(config, indent=2) + "\n", "source": (source_folder / "weighted-profile.talg").read_text(encoding="utf-8-sig"),
                "inputs": "{}\n", "relatedness": (source_folder / "relatedness.json").read_text(encoding="utf-8-sig"),
                "connection_weight": (source_folder / "connection-model.json").read_text(encoding="utf-8-sig")}
    settings(contents)
    files = {}
    for role, text in contents.items():
        relative = f"work/model-profiles/news/{profile_id}/{role}." + ("talg" if role == "source" else "json")
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")
        files[role] = relative
    payload = {"kind": "news", "profile": profile_id, "name": name, "dependencies": {}, "contents": contents}
    raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()
    reference = hashlib.sha256(raw).hexdigest()
    folder = revision_folder(root, reference)
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "manifest.json").write_bytes(raw)
    profiles["profiles"]["news"][profile_id] = {"name": payload["name"], "dependencies": {}, "files": files, "head": reference}
    profiles["selected"]["news"] = profile_id
    if index.read_bytes() != original:
        raise ValueError("Profile index changed while installing; retry after closing profile edits")
    atomic_json(index, profiles)
    return reference


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["install", "run"])
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--symbol", action="append")
    parser.add_argument("--revision")
    args = parser.parse_args()
    root = args.root.resolve()
    if args.command == "install":
        if not args.symbol:
            parser.error("Install requires at least one --symbol")
        print(install(root, args.symbol))
    else:
        if not args.revision:
            parser.error("Run requires an immutable --revision")
        print(json.dumps(refresh(root, args.revision), indent=2))


if __name__ == "__main__":
    main()

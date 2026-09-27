"""Symbol + related article -> retained vector -> one count-normalized decay average.

Independent research output: never changes profiles, trading, or the shared encoder
database. A run is a current observation, not a historical as-of reconstruction.
"""
from __future__ import annotations

import argparse
import copy
import json
import math
import re
import sqlite3
import time
import uuid
from contextlib import closing
from pathlib import Path

import numpy as np

from .model_profiles import runtime_lock
from .news_features import ArticleEncoder, feature_layout
from .news_tensor import Encoder, atomic_json, atomic_npz, digest, utc


def symbol_name(value: str) -> str:
    value = value.strip().upper()
    if not re.fullmatch(r"[A-Z0-9][A-Z0-9.^-]{0,14}", value):
        raise ValueError("Invalid stock symbol")
    return value


def decay_weights(ages, config):
    """One decay function with an initial fast component and a slower remainder."""
    if config["kind"] != "shock_plus_background":
        raise ValueError("Unsupported decay kind")
    fraction = float(config["shock_fraction"])
    fast = float(config["shock_half_life_seconds"])
    slow = float(config["background_half_life_seconds"])
    if not all(math.isfinite(v) for v in (fraction, fast, slow)) or not 0 <= fraction <= 1 or not 0 < fast <= slow:
        raise ValueError("Decay requires 0 <= shock_fraction <= 1 and 0 < fast <= slow")
    ages = np.asarray(ages, dtype=np.float64)
    if not np.isfinite(ages).all():
        raise ValueError("Ages must be finite")
    ages = np.maximum(0, ages)
    return fraction * np.exp2(-ages / fast) + (1 - fraction) * np.exp2(-ages / slow)


def count_average(vectors, ages, decay, connection_weights=None):
    vectors = np.asarray(vectors, dtype=np.float64)
    weights = decay_weights(ages, decay)
    if vectors.ndim != 2 or weights.shape != (len(vectors),) or not np.isfinite(vectors).all():
        raise ValueError("Expected finite article-by-feature vectors and one age per article")
    connections = np.ones(len(vectors)) if connection_weights is None else np.asarray(connection_weights, dtype=np.float64)
    if connections.shape != (len(vectors),) or not np.isfinite(connections).all() or np.any((connections < 0) | (connections > 1)):
        raise ValueError("Expected one finite connection weight in [0,1] per article")
    # Multiply by 1/mu to avoid overflow from explicitly constructing mu at old ages.
    result = np.einsum("n,nd->d", weights * connections, vectors, optimize=False) / max(1, len(vectors))
    return result, weights


def related_articles(db, symbol, kinds):
    """Select only explicit symbol links; multiple link sources do not repeat a row."""
    symbol = symbol_name(symbol)
    if not kinds or any(not isinstance(kind, str) or not kind for kind in kinds):
        raise ValueError("At least one relation kind is required")
    placeholders = ",".join("?" for _ in kinds)
    records = db.execute(f"""SELECT i.*, a.kind AS relation_kind, a.evidence AS relation_evidence
        FROM information i JOIN associations a ON i.id=a.information_id
        WHERE a.ticker=? AND a.kind IN ({placeholders}) ORDER BY i.id,a.kind,a.evidence""",
        (symbol, *kinds)).fetchall()
    articles = {}
    for record in records:
        row = dict(record)
        relation = {"kind": row.pop("relation_kind"), "evidence": row.pop("relation_evidence")}
        row.pop("vector", None)  # The shared, symbol-independent vector is not reused.
        article = articles.setdefault(row["id"], dict(row, relations=[]))
        article["relations"].append(relation)
    return list(articles.values())


class PairStore:
    """Append-only cache: symbol, content revision and encoder input define each vector."""
    def __init__(self, path):
        self.db = sqlite3.connect(path)
        self.db.row_factory = sqlite3.Row
        self.db.execute("""CREATE TABLE IF NOT EXISTS pairs (
            pair_id TEXT PRIMARY KEY, symbol TEXT, information_id TEXT, content_hash TEXT,
            encoder_version TEXT, input_text TEXT, vector BLOB, dimension INTEGER,
            encoded_at REAL, article_json TEXT)""")
        self.db.execute("""CREATE TABLE IF NOT EXISTS pair_chunks (
            pair_id TEXT PRIMARY KEY, metadata TEXT, vectors BLOB, chunks INTEGER, dimension INTEGER)""")

    def encode(self, symbol, articles, encoder, config, clock=time.time):
        symbol = symbol_name(symbol)
        identity = {"encoder": config["encoder"], "input_template": config["input_template"]}
        if "representation" in config:
            identity["representation"] = config["representation"]
        version = digest(json.dumps(identity, sort_keys=True))
        dimension = feature_layout(config)["dimensions"]
        batch_size = config["batch_size"]
        if type(batch_size) is not int or batch_size < 1:
            raise ValueError("batch_size must be a positive integer")
        pending, keys = [], []
        context = ""
        if "{company_context}" in config["input_template"]:
            graph = config.get("relatedness_snapshot", {})
            entity = graph.get("entities", {}).get(symbol, {})
            context = entity.get("name", symbol) + "; sectors: " + ", ".join(entity.get("sectors", []))
        for article in articles:
            text = config["input_template"].format(symbol=symbol, text=article["text"], company_context=context)
            key = digest(json.dumps([symbol, article["id"], article["content_hash"], version, text]))
            keys.append(key)
            if self.db.execute("SELECT 1 FROM pairs WHERE pair_id=?", (key,)).fetchone() is None:
                pending.append((key, article, text))
        for start in range(0, len(pending), batch_size):
            batch = pending[start:start + batch_size]
            vectors = np.asarray(encoder.encode([entry[2] for entry in batch]), dtype=np.float32)
            if vectors.shape != (len(batch), dimension) or not np.isfinite(vectors).all():
                raise ValueError("Encoder produced invalid symbol/article vectors")
            semantic = getattr(encoder, "semantic_encoder", encoder)
            metadata = getattr(semantic, "last_metadata", None)
            chunks = getattr(semantic, "last_chunks", None)
            with self.db:
                for index, ((key, article, text), vector) in enumerate(zip(batch, vectors, strict=True)):
                    self.db.execute("INSERT INTO pairs VALUES (?,?,?,?,?,?,?,?,?,?)", (
                        key, symbol, article["id"], article["content_hash"], version, text,
                        vector.tobytes(), dimension, clock(), json.dumps(article, ensure_ascii=False)))
                    if metadata is not None:
                        self.db.execute("INSERT INTO pair_chunks VALUES (?,?,?,?,?)", (
                            key, json.dumps(metadata[index]), chunks[index].astype(np.float32).tobytes(),
                            len(chunks[index]), dimension))
        for article, key in zip(articles, keys, strict=True):
            evidence = self.db.execute("SELECT metadata FROM pair_chunks WHERE pair_id=?", (key,)).fetchone()
            if evidence:
                article["encoding"] = json.loads(evidence["metadata"])
        rows = [self.db.execute("SELECT * FROM pairs WHERE pair_id=?", (key,)).fetchone() for key in keys]
        if any(row["dimension"] != dimension or len(row["vector"]) != dimension * 4 for row in rows):
            raise ValueError("Cached article dimension does not match its representation version")
        vectors = np.stack([np.frombuffer(row["vector"], dtype=np.float32) for row in rows]) if rows else np.empty((0, dimension), dtype=np.float32)
        return vectors, np.array([row["encoded_at"] for row in rows]), keys, version


def run(root, symbols, config_path, source_path=None, relatedness_only=False, *, config_snapshot=None, output_path=None):
    root = Path(root).resolve()
    config_path = Path(config_path)
    if not config_path.is_absolute():
        config_path = root / config_path
    config = copy.deepcopy(config_snapshot) if config_snapshot is not None else json.loads(config_path.read_text(encoding="utf-8-sig"))
    if config["schema_version"] != 1 or config["denominator"] != "count_of_all_related_articles_in_the_current_source_snapshot":
        raise ValueError("Unsupported configuration contract")
    if "{symbol}" not in config["input_template"] or "{text}" not in config["input_template"]:
        raise ValueError("Encoder input must contain both symbol and article text")
    decay_weights([], config["decay"])
    layout = feature_layout(config)
    relatedness_model = None
    if "relatedness" in config:
        from .news_relatedness import RelatednessModel
        model_file = Path(config["relatedness"]["model_file"])
        if not model_file.is_absolute():
            model_file = root / model_file
        relatedness_model = config.get("relatedness_snapshot")
        if relatedness_model is None:
            relatedness_model = json.loads(model_file.read_text(encoding="utf-8-sig"))
        RelatednessModel(relatedness_model)  # Validate before reading or encoding articles.
        config["relatedness_snapshot"] = relatedness_model
    connection_model = None
    if "connection_weight" in config:
        from .news_connection import ConnectionModel
        weight_file = Path(config["connection_weight"]["model_file"])
        if not weight_file.is_absolute():
            weight_file = root / weight_file
        saved_weight = config.get("connection_weight_snapshot")
        connection_model = ConnectionModel(saved_weight if saved_weight is not None else json.loads(weight_file.read_text(encoding="utf-8-sig")))
        config["connection_weight_snapshot"] = connection_model.config
        if relatedness_model is None:
            raise ValueError("Configured connection weights require AI relevance evidence")
    source_path = Path(source_path or "work/news-tensor/information.sqlite")
    if not source_path.is_absolute():
        source_path = root / source_path
    source_path = source_path.resolve()
    cache_folder = root / "work/symbol-news-tensor"
    output = Path(output_path) if output_path is not None else cache_folder
    with runtime_lock(cache_folder):
        # Capture all requested links/text in one read transaction before inference.
        with closing(sqlite3.connect(source_path.as_uri() + "?mode=ro", uri=True)) as source:
            source.row_factory = sqlite3.Row
            source.execute("BEGIN")
            if relatedness_model is not None:
                from .news_relatedness import select_related
                selected, relatedness_report = select_related(source, relatedness_model, config["related_kinds"],
                    None if symbols is None else [symbol_name(s) for s in symbols])
            else:
                if symbols is None:
                    symbols = [row[0] for row in source.execute("SELECT DISTINCT ticker FROM associations ORDER BY ticker")]
                symbols = sorted({symbol_name(s) for s in symbols})
                selected = {s: related_articles(source, s, config["related_kinds"]) for s in symbols}
                relatedness_report = {"mode": "legacy_direct_associations"}
            captured_at = time.time()
        run_id = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime()) + "-" + uuid.uuid4().hex[:8]
        folder = output / "runs" / run_id
        folder.mkdir(parents=True)
        config_version = digest(json.dumps(config, sort_keys=True))
        atomic_json(folder / "model.json", config)
        atomic_json(folder / "feature-layout.json", layout)
        if relatedness_model is not None:
            from .news_relevance_ai import filter_articles
            selected, decisions, ai_report = filter_articles(selected, relatedness_model, root,
                cache_folder / "relevance-scores.sqlite", progress=lambda message: print(message, flush=True))
            relatedness_report["ai"] = ai_report
            atomic_json(folder / "relatedness-model.json", relatedness_model)
            for symbol, records in decisions.items():
                atomic_json(folder / (symbol + ".relevance-decisions.json"), records)
            del decisions
        if connection_model is not None:
            atomic_json(folder / "connection-model.json", connection_model.config)
            selected = {symbol: [dict(article, connection_weight=connection_model.predict(symbol, article))
                                for article in articles] for symbol, articles in selected.items()}
        atomic_json(folder / "relatedness-report.json", relatedness_report)
        if relatedness_only:
            summary = {"run_id": run_id, "config_version": config_version, "stage": "relatedness_only",
                "source_captured_at": utc(captured_at), "completed_at": utc(time.time()),
                "symbols": {s: {"related_articles": len(a)} for s, a in selected.items()},
                "relatedness": relatedness_report, "decay_experiment": "not_started"}
            for symbol, articles in selected.items():
                atomic_json(folder / (symbol + ".articles.json"), articles)
            atomic_json(folder / "summary.json", summary)
            atomic_json(output / "latest-relatedness.json", {"run_id": run_id, "summary": str(folder / "summary.json")})
            return summary
        # Do not load the heavy encoder if there are no related articles.
        encoder_type = Encoder
        if config["encoder"].get("backend") == "onnx_chunked_cls_v2":
            from .news_encoder_v2 import ChunkEncoder
            encoder_type = ChunkEncoder
        encoder = ArticleEncoder(encoder_type(root, config["encoder"]), config) if any(selected.values()) else None
        cache = PairStore(cache_folder / "pairs.sqlite")
        results = {}
        effect_model = None
        effect_pin = config.get("effect_model", {}).get("artifact")
        if effect_pin:
            from .news_effects import EffectModel
            effect_model = EffectModel(root, effect_pin, config)
        try:
            for symbol, articles in selected.items():
                vectors, encoded_at, keys, version = cache.encode(symbol, articles, encoder, config)
                now = time.time()  # never backdate vectors to publication time
                event_times = np.array([min(a["event_time"], a["first_seen"], captured_at) for a in articles])
                connections = np.array([a.get("connection_weight", {}).get("value", 1.0) for a in articles])
                value, weights = count_average(vectors, now - event_times, config["decay"], connections)
                relevance_at = np.array([a.get("relevance_ai", {}).get("evaluated_at", captured_at) for a in articles])
                connection_at = np.array([a.get("connection_weight", {}).get("evaluated_at", captured_at) for a in articles])
                available_at = np.maximum(np.maximum(np.maximum(encoded_at, captured_at), relevance_at), connection_at)
                atomic_npz(folder / (symbol + ".npz"), news_tensor=value,
                    article_vectors=vectors, decay_weights=weights, connection_weights=connections,
                    effective_weights=weights * connections,
                    connection_model_version=np.array(connection_model.version if connection_model else "legacy_unit_weights"), information_ids=np.array([a["id"] for a in articles], dtype=str),
                    pair_ids=np.array(keys, dtype=str), event_times=event_times, available_at=available_at,
                    symbol=np.array(symbol), n=np.array(len(articles)), as_of_utc=np.array(utc(now)),
                    encoder_version=np.array(version), config_version=np.array(config_version))
                effect_state = "awaiting_outcome_labels"
                forecasts, effect_reference = [], None
                if effect_model is not None:
                    article_effects, effect_average, forecasts = effect_model.predict(vectors, weights * connections)
                    effect_state = effect_model.metadata["status"] if articles else "no_related_articles"
                    if articles:
                        effect_file = folder / (symbol + ".effects.npz")
                        atomic_npz(effect_file, article_effects=article_effects, effect_tensor=effect_average,
                                   information_ids=np.array([a["id"] for a in articles], dtype=str),
                                   effective_weights=weights * connections, n=np.array(len(articles)),
                                   config_version=np.array(config_version), model_sha256=np.array(effect_pin["model_sha256"]))
                        effect_reference = {"file": str(effect_file), "field": "effect_tensor",
                                            "dimensions": len(effect_average), "model_sha256": effect_pin["model_sha256"]}
                atomic_json(folder / (symbol + ".effects.json"), {"state": effect_state, "forecasts": forecasts,
                            "tensor": effect_reference, "computed_at": utc(time.time()), "model": effect_pin})
                atomic_json(folder / (symbol + ".articles.json"), articles)
                results[symbol] = {"n": len(articles), "dimensions": len(value), "as_of_utc": utc(now),
                    "state": "information_only" if articles else "no_related_articles",
                    "tensor_file": symbol + ".npz", "direction_prediction": None,
                    "effect_state": effect_state, "effect_tensor": effect_reference, "forecasts": forecasts}
        finally:
            cache.db.close()
        summary = {"run_id": run_id, "config_version": config_version, "source": str(source_path),
            "connection_model_version": connection_model.version if connection_model else "legacy_unit_weights",
            "relatedness": relatedness_report, "representation": layout, "source_captured_at": utc(captured_at), "completed_at": utc(time.time()), "symbols": results,
            "decay_experiment": "not_started", "historical_backtest": False}
        atomic_json(folder / "summary.json", summary)
        atomic_json(output / "latest.json", {"run_id": run_id, "summary": str(folder / "summary.json")})
        return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--config", default="research/news-average/model.json")
    parser.add_argument("--source", help="Existing news information.sqlite (read only)")
    selection = parser.add_mutually_exclusive_group(required=True)
    selection.add_argument("--symbol", action="append", help="Repeat to request several symbols")
    selection.add_argument("--all-related-symbols", action="store_true")
    parser.add_argument("--relatedness-only", action="store_true", help="Save relevance decisions without encoding or averaging")
    args = parser.parse_args()
    print(json.dumps(run(args.root, None if args.all_related_symbols else args.symbol, args.config, args.source, args.relatedness_only), indent=2))


if __name__ == "__main__":
    main()

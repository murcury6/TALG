"""Local news meaning tensors, separate relevance edges, and decayed ticker views.

No trading API, remote inference, arbitrary expression execution, or price prediction
is implicit in a semantic embedding. Configuration and archive records stay readable.
"""
from __future__ import annotations

import argparse
import hashlib
import html
import json
import math
import os
import re
import sqlite3
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

import numpy as np
from .news_ratings import model_source, rate_items, rating_weights, custom_inputs


def utc(value: float) -> str:
    return datetime.fromtimestamp(value, timezone.utc).isoformat()


def timestamp(value: str, fallback: float) -> float:
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        return parsed.timestamp() if parsed.tzinfo is not None else fallback
    except (ValueError, AttributeError):
        return fallback


def digest(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def plain(value: str) -> str:
    return " ".join(html.unescape(re.sub(r"<[^>]*>", " ", value or "")).split())


def canonical(url: str) -> str:
    part = urlsplit(url)
    query = [(k, v) for k, v in parse_qsl(part.query) if not k.lower().startswith("utm_")
             and k.lower() not in {"fbclid", "gclid", "ocid", ".tsrc"}]
    return urlunsplit((part.scheme.lower(), part.netloc.lower(), part.path, urlencode(query), ""))


def atomic_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False), encoding="utf-8")
    os.replace(temp, path)


def atomic_npz(path: Path, **values: np.ndarray) -> None:
    temp = path.with_suffix(".tmp")
    with temp.open("wb") as file:
        np.savez_compressed(file, **values)
    os.replace(temp, path)


class Encoder:
    def __init__(self, root: Path, config: dict):
        import onnxruntime as ort
        from tokenizers import Tokenizer
        folder = root / config["directory"]
        options = ort.SessionOptions()
        options.intra_op_num_threads = config["cpu_threads"]
        options.inter_op_num_threads = 1
        self.session = ort.InferenceSession(str(folder / "model_quantized.onnx"), options,
                                           providers=["CPUExecutionProvider"])
        self.tokenizer = Tokenizer.from_file(str(folder / "tokenizer.json"))
        self.tokenizer.enable_truncation(max_length=config["max_tokens"])
        pad = "<pad>" if self.tokenizer.token_to_id("<pad>") is not None else "[PAD]"
        self.tokenizer.enable_padding(pad_id=self.tokenizer.token_to_id(pad), pad_token=pad)
        self.dimension = config["dimension"]

    def encode(self, texts: list[str]) -> np.ndarray:
        tokens = self.tokenizer.encode_batch(texts)
        data = {"input_ids": np.array([t.ids for t in tokens], dtype=np.int64),
                "attention_mask": np.array([t.attention_mask for t in tokens], dtype=np.int64),
                "token_type_ids": np.array([t.type_ids for t in tokens], dtype=np.int64)}
        output = self.session.run(None, {i.name: data[i.name] for i in self.session.get_inputs()})[0]
        mask = data["attention_mask"][..., None]
        vectors = (output * mask).sum(axis=1) / mask.sum(axis=1).clip(min=1)
        vectors /= np.linalg.norm(vectors, axis=1, keepdims=True).clip(min=1e-12)
        if vectors.shape != (len(texts), self.dimension) or not np.isfinite(vectors).all():
            raise ValueError("Invalid encoder output")
        return vectors.astype(np.float32)


class Store:
    def __init__(self, folder: Path):
        folder.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(folder / "information.sqlite")
        self.db.row_factory = sqlite3.Row
        self.db.executescript("""
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS information (
                id TEXT PRIMARY KEY, content_hash TEXT, text TEXT, title TEXT, url TEXT,
                publisher TEXT, event_time REAL, first_seen REAL, observed_at REAL,
                vector BLOB, encoded_at REAL, encoder_version TEXT);
            CREATE INDEX IF NOT EXISTS content_index ON information(content_hash);
            CREATE TABLE IF NOT EXISTS aliases (url TEXT PRIMARY KEY, information_id TEXT);
            CREATE TABLE IF NOT EXISTS associations (
                information_id TEXT, ticker TEXT, kind TEXT, evidence TEXT,
                PRIMARY KEY(information_id,ticker,kind,evidence));
            CREATE TABLE IF NOT EXISTS observations (
                fingerprint TEXT PRIMARY KEY, information_id TEXT, observed_at REAL, raw TEXT);
            CREATE TABLE IF NOT EXISTS cursors (path TEXT PRIMARY KEY, offset INTEGER);
        """)

    def ingest(self, record: dict, now: float) -> bool:
        event = record.get("event")
        if event is not None:
            title, summary, url = event.get("headline", ""), event.get("summary", ""), event.get("url", "")
            publisher = event.get("source", "Alpaca news")
            observed = timestamp(record.get("receivedAt", ""), now)
            published = timestamp(event.get("created_at", ""), observed)
            tags = [(s, "provider_symbol", publisher) for s in event.get("symbols", [])]
        else:
            article, source = record.get("article", {}), record.get("source", {})
            title, summary, url = article.get("title", ""), article.get("summary", ""), article.get("url", "")
            publisher = article.get("publisher", source.get("publisher", ""))
            observed = timestamp(record.get("collectedAt", ""), now)
            published = timestamp(article.get("publishedAt", ""), observed)
            tags = [(s, "provider_symbol", publisher) for s in article.get("symbols", [])]
            if source.get("symbol"):
                tags.append((source["symbol"], "ticker_feed", source.get("id", "")))
        title, summary = plain(title), plain(summary)
        if not title:
            return False
        text = title + "\n" + summary
        content_hash = digest(text.casefold())
        identity_url = canonical(url) if url else "content:" + content_hash
        # Include origin in observation identity: one story can acquire several ticker edges.
        fingerprint = digest(json.dumps(record, sort_keys=True, ensure_ascii=False))
        if self.db.execute("SELECT 1 FROM observations WHERE fingerprint=?", (fingerprint,)).fetchone():
            return False
        known = self.db.execute("SELECT information_id FROM aliases WHERE url=?", (identity_url,)).fetchone()
        if known is None:
            known = self.db.execute("SELECT id FROM information WHERE content_hash=? LIMIT 1", (content_hash,)).fetchone()
        item_id = known[0] if known else digest(identity_url)
        old = self.db.execute("SELECT * FROM information WHERE id=?", (item_id,)).fetchone()
        event_time = min(published, observed, now)  # Future publisher clocks do not amplify weights.
        if old is None:
            self.db.execute("INSERT INTO information VALUES (?,?,?,?,?,?,?,?,?,NULL,NULL,NULL)",
                            (item_id, content_hash, text, title, url, publisher, event_time, observed, observed))
        elif observed >= old["observed_at"] and old["content_hash"] != content_hash:
            self.db.execute("""UPDATE information SET content_hash=?,text=?,title=?,url=?,publisher=?,
                observed_at=?,vector=NULL,encoded_at=NULL,encoder_version=NULL WHERE id=?""",
                            (content_hash, text, title, url, publisher, observed, item_id))
        # Repeated polls and revisions do not reset the original decay age.
        self.db.execute("INSERT OR REPLACE INTO aliases VALUES (?,?)", (identity_url, item_id))
        for ticker, kind, evidence in tags:
            ticker = ticker.strip().upper()
            if re.fullmatch(r"[A-Z0-9][A-Z0-9.^-]{0,14}", ticker):
                self.db.execute("INSERT OR IGNORE INTO associations VALUES (?,?,?,?)", (item_id, ticker, kind, evidence))
        self.db.execute("INSERT INTO observations VALUES (?,?,?,?)",
                        (fingerprint, item_id, observed, json.dumps(record, ensure_ascii=False)))
        return True

    def scan(self, root: Path) -> int:
        changed = 0
        paths = list((root / "data/news-stream").glob("*.jsonl")) + list((root / "data/news").glob("*/*.jsonl"))
        for path in sorted(paths, key=lambda p: p.stat().st_mtime, reverse=True):
            name = str(path.relative_to(root))
            cursor = self.db.execute("SELECT offset FROM cursors WHERE path=?", (name,)).fetchone()
            offset = cursor[0] if cursor else 0
            if path.stat().st_size < offset:
                offset = 0
            with path.open("rb") as file:
                file.seek(offset)
                while line := file.readline():
                    if not line.endswith(b"\n"):
                        break  # A live writer's partial record is read next time.
                    record = json.loads(line.decode("utf-8-sig"))
                    changed += self.ingest(record, time.time())
                    offset = file.tell()
            self.db.execute("INSERT OR REPLACE INTO cursors VALUES (?,?)", (name, offset))
        self.db.commit()
        return changed

    def encode_pending(self, encoder, version: str, batch_size: int) -> int:
        rows = self.db.execute("""SELECT id,text,content_hash FROM information
            WHERE vector IS NULL OR encoder_version != ? ORDER BY first_seen DESC LIMIT ?""", (version, batch_size)).fetchall()
        if not rows:
            return 0
        vectors = encoder.encode([row["text"] for row in rows])
        for row, vector in zip(rows, vectors, strict=True):
            self.db.execute("UPDATE information SET vector=?,encoded_at=?,encoder_version=? WHERE id=? AND content_hash=?",
                            (vector.tobytes(), time.time(), version, row["id"], row["content_hash"]))
        self.db.commit()
        return len(rows)


def aggregate(vectors: np.ndarray, event_times: np.ndarray, relevance: np.ndarray,
              now: float, half_lives: list[float], prior_mass: float) -> dict[str, np.ndarray]:
    half_lives_array = np.asarray(half_lives, dtype=np.float64)
    if not np.isfinite(half_lives_array).all() or np.any(half_lives_array <= 0) or not math.isfinite(prior_mass) or prior_mass <= 0:
        raise ValueError("Half-lives and prior_mass must be positive")
    decay = np.exp2(-np.maximum(0, now - event_times)[:, None] / half_lives_array)
    weights = relevance[:, None] * decay
    mass = weights.sum(axis=0)
    # Explicit contraction avoids a dynamically loaded BLAS dependency for this
    # small-horizon operation (some portable Windows BLAS builds cannot load).
    summed = np.einsum("nh,nd->hd", weights, vectors, optimize=False)
    average = np.divide(summed, mass[:, None], out=np.zeros_like(summed), where=mass[:, None] > 1e-12)
    relevance_mass = float(relevance.sum())
    return {"weighted_sum": summed, "weighted_average": average, "mass": mass,
            "relevance_mass": np.full_like(mass, relevance_mass),
            "decayed_average": summed / max(relevance_mass, 1e-12),
            "fading_information": summed / (mass[:, None] + prior_mass)}


def snapshot(store: Store, config: dict, now: float, version: str) -> tuple[dict, dict, dict]:
    rows = store.db.execute("SELECT * FROM information WHERE vector IS NOT NULL AND encoder_version=? ORDER BY id", (version,)).fetchall()
    dimension = config["encoder"]["dimension"]
    matrix = np.stack([np.frombuffer(row["vector"], dtype=np.float32) for row in rows]) if rows else np.empty((0, dimension), dtype=np.float32)
    times = np.array([row["event_time"] for row in rows])
    ids = [row["id"] for row in rows]
    positions = {item_id: index for index, item_id in enumerate(ids)}
    links: dict[str, dict[int, float]] = {}
    for edge in store.db.execute("SELECT * FROM associations"):
        index = positions.get(edge["information_id"])
        if index is None:
            continue
        weight = float(config["association_weights"].get(edge["kind"], 0))
        if not math.isfinite(weight) or not 0 <= weight <= 1:
            raise ValueError("Relevance weights must be finite and in [0,1]")
        entries = links.setdefault(edge["ticker"], {})
        entries[index] = max(entries.get(index, 0), weight)
    # Explicit relevance rules can apply independent information to further tickers.
    for rule in config["ticker_rules"]:
        terms = [t.casefold() for t in rule["contains_any"]]
        weight = float(rule["weight"])
        if not terms or not math.isfinite(weight) or not 0 <= weight <= 1:
            raise ValueError("A relevance rule needs terms and a weight in [0,1]")
        for index, row in enumerate(rows):
            if any(term in row["text"].casefold() for term in terms):
                for ticker in rule["tickers"]:
                    entries = links.setdefault(ticker, {})
                    entries[index] = max(entries.get(index, 0), weight)
    groups = ["GLOBAL"] + sorted(links)
    model_weights = rating_weights(store, rows)
    outputs = {key: [] for key in ("weighted_sum", "weighted_average", "mass", "relevance_mass", "decayed_average", "fading_information")}
    for group in groups:
        selected = np.arange(len(rows)) if group == "GLOBAL" else np.array(list(links[group]), dtype=int)
        weights = model_weights if group == "GLOBAL" else np.array(list(links[group].values())) * model_weights[selected]
        reduced = aggregate(matrix[selected], times[selected], weights, now, config["half_lives_seconds"], config["prior_mass"])
        for key, value in reduced.items():
            outputs[key].append(value)
    tensor = {key: np.array(value) for key, value in outputs.items()}
    tensor.update(groups=np.array(groups), half_lives_seconds=np.array(config["half_lives_seconds"]), as_of_utc=np.array(utc(now)), encoder_version=np.array(version))
    effects = {}
    for ticker, head in config["effect_heads"].items():
        if ticker not in groups:
            continue
        weights = np.asarray(head["weights"], dtype=np.float64)
        if weights.shape != (dimension,) or not np.isfinite(weights).all() or not head.get("model_version"):
            raise ValueError("Effect head needs fitted weights[dimension] and model_version")
        bias = float(head.get("bias", 0))
        if not math.isfinite(bias):
            raise ValueError("Effect head bias must be finite")
        group_index = groups.index(ticker)
        # Project then decay: baseline bias also disappears as evidence disappears.
        mass = tensor["mass"][group_index]
        values = (np.einsum("hd,d->h", tensor["weighted_sum"][group_index], weights, optimize=False)
                  + bias * mass) / np.maximum(tensor["relevance_mass"][group_index], 1e-12)
        effects[ticker] = {"model_version": head["model_version"], "scores": values.tolist()}
    article_tensor = {"information_ids": np.array(ids), "vectors": matrix, "event_times": times,
                      "available_at": np.array([r["encoded_at"] for r in rows]), "encoder_version": np.array(version)}
    return tensor, article_tensor, effects


def run(root: Path, once: bool = False, refresh_profiles: bool = True):
    folder = root / "work/news-tensor"
    folder.mkdir(parents=True, exist_ok=True)
    # Process lock is automatically released by the OS after a crash.
    import msvcrt
    lock = (folder / "worker.lock").open("a+b")
    if lock.tell() == 0:
        lock.write(b"0"); lock.flush()
    lock.seek(0)
    msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
    config_path = root / "work/models/news-tensor.json"
    config = json.loads(config_path.read_text(encoding="utf-8-sig"))
    atomic_json(folder / "status.json", {"state": "STARTING", "pid": os.getpid(), "as_of_utc": utc(time.time())})
    encoder_config = config["encoder"]
    version = digest(json.dumps(encoder_config, sort_keys=True))
    encoder = Encoder(root, encoder_config)
    store = Store(folder)
    atomic_json(folder / "encoder.json", {"version": version, "config": encoder_config})
    last_snapshot = last_export = last_scan = last_rating = 0.0
    rating_source = ""
    while True:
        now = time.time()
        if now - last_scan >= 1:
            store.scan(root)
            last_scan = now
        count = store.encode_pending(encoder, version, config["batch_size"])
        now = time.time()
        if now - last_snapshot >= config["snapshot_seconds"] or (once and count == 0):
            updated = json.loads(config_path.read_text(encoding="utf-8-sig"))
            if updated["encoder"] != encoder_config:
                raise ValueError("Encoder change requires a worker restart; mixed encoders are not allowed")
            config = updated
            current_source = model_source(root)
            if (not once and (current_source != rating_source or now - last_rating >= 30)) or (once and count == 0):
                rating_result = rate_items(store, config, now, version, current_source, custom_inputs(root))
                atomic_json(folder / "rating-status.json", rating_result)
                from .model_language import Program
                if Program(current_source).symbol is not None:
                    from .news_symbols import evaluate_symbols
                    atomic_json(folder / "symbol-ratings.json", evaluate_symbols(store.db, current_source, now=now))
                from .news_ratings import event_snapshot
                atomic_json(folder / "news-events.json", event_snapshot(store))
                rating_source = current_source; last_rating = now
                from .model_profiles import refresh_active
                if refresh_profiles: refresh_active(root)
            tensors, articles, effects = snapshot(store, config, now, version)
            atomic_npz(folder / "current.npz", **tensors)
            atomic_json(folder / "effects.json", {"as_of_utc": utc(now), "state": "fitted_heads" if effects else "no_fitted_effect_heads", "effects": effects})
            total = store.db.execute("SELECT count(*) FROM information").fetchone()[0]
            encoded = len(articles["information_ids"])
            atomic_json(folder / "status.json", {"as_of_utc": utc(now), "pid": os.getpid(), "state": "BACKFILLING" if encoded < total else "LIVE",
                        "information_items": total, "encoded_items": encoded, "pending_items": total-encoded,
                        "tensor_shape": list(tensors["fading_information"].shape), "information_shape": list(articles["vectors"].shape),
                        "groups": tensors["groups"].tolist(), "half_lives_seconds": config["half_lives_seconds"],
                        "encoder_version": version, "effect_heads": len(effects)})
            last_snapshot = now
            if now - last_export >= config["article_export_seconds"] or encoded == total:
                atomic_npz(folder / "information.npz", **articles)
                last_export = now
        if once and count == 0:
            break
        if count == 0:
            time.sleep(0.25)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--once", action="store_true")
    arguments = parser.parse_args()
    run(arguments.root.resolve(), arguments.once)

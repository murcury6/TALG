"""Per-information model outputs and provenance, separate from the encoder."""
import json
import math
import hashlib
from pathlib import Path
import time
import numpy as np
from .model_language import Program, STARTER

def model_source(root: Path):
    file = root / "work/models/news-rating.talg"
    if not file.exists():
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(STARTER, encoding="utf-8")
    return file.read_text(encoding="utf-8-sig")

def initialize(db):
    db.executescript("""
        CREATE INDEX IF NOT EXISTS observation_information ON observations(information_id);
        CREATE TABLE IF NOT EXISTS news_events (
            sequence INTEGER PRIMARY KEY AUTOINCREMENT, event_key TEXT UNIQUE,
            created_at REAL, information_id TEXT, model_version TEXT, symbols TEXT, outputs TEXT);
        CREATE TABLE IF NOT EXISTS ratings (
            information_id TEXT PRIMARY KEY, model_version TEXT, content_hash TEXT, encoder_version TEXT,
            rated_at REAL, inputs TEXT, outputs TEXT, error TEXT);
        CREATE TABLE IF NOT EXISTS rating_history (
            information_id TEXT, model_version TEXT, content_hash TEXT, encoder_version TEXT,
            rated_at REAL, inputs TEXT, outputs TEXT, error TEXT, vector BLOB,
            PRIMARY KEY(information_id,model_version,content_hash,encoder_version));
        CREATE TABLE IF NOT EXISTS rating_programs (version TEXT PRIMARY KEY, source TEXT);
    """)

def rate_items(store, config, now, version, source, custom=None):
    program = Program(source); db = store.db; initialize(db)
    counts = {row[0]: (row[1], row[2]) for row in db.execute("""
        SELECT information_id,count(*),count(DISTINCT coalesce(json_extract(raw,'$.source.id'),
        json_extract(raw,'$.event.source'),'unknown')) FROM observations GROUP BY information_id""")}
    associations = {}
    for identity, ticker in db.execute("SELECT DISTINCT information_id,ticker FROM associations"):
        associations.setdefault(identity, []).append(ticker)
    links = {row[0]: row[1] for row in db.execute("SELECT information_id,count(DISTINCT ticker) FROM associations GROUP BY information_id")}
    changed = []; history = []; events = []; errors = 0
    for row in db.execute("SELECT * FROM information WHERE vector IS NOT NULL AND encoder_version=?", (version,)):
        observations, sources = counts.get(row["id"], (0, 0))
        inputs = {"headline": row["title"], "text": row["text"], "publisher": row["publisher"],
                  "age_seconds": max(0, now-row["event_time"]), "observation_count": observations,
                  "source_count": sources, "ticker_count": links.get(row["id"], 0), "tickers": sorted(associations.get(row["id"], [])),
                  "half_lives_seconds": config["half_lives_seconds"], "prior_mass": config["prior_mass"]}
        if custom:
            supplied = dict(custom.get("defaults", {})); supplied.update(custom.get("items", {}).get(row["id"], {}))
            inputs.update({"custom." + key: val for key, val in supplied.items()})
        vector = np.frombuffer(row["vector"], dtype=np.float32).tolist()
        try:
            outputs = program.run(dict(inputs, embedding=vector)); error = ""
            if "notify" in outputs and type(outputs["notify"]) is not bool: raise ValueError("notify must be boolean")
            if outputs.get("notify"):
                targets = outputs.get("notify_symbols")
                import re
                if not isinstance(targets, list) or not targets or len(targets) > 120 or any(not isinstance(t, str) or not re.fullmatch(r"[A-Z][A-Z0-9.\-]{0,14}", t) for t in targets):
                    raise ValueError("notify_symbols must be a nonempty list of at most 120 ticker symbols")
                ttl = outputs.get("notify_ttl_seconds", 120)
                if type(ttl) not in (int, float) or not math.isfinite(ttl) or not 1 <= ttl <= 86400:
                    raise ValueError("notify_ttl_seconds must be a finite number from 1 to 86400")
                discriminator = outputs.get("notify_key", "")
                if not isinstance(discriminator, str): raise ValueError("notify_key must be text")
                key = hashlib.sha256(json.dumps([row["id"], program.version, row["content_hash"], discriminator]).encode()).hexdigest()
                events.append((key, now, row["id"], program.version, json.dumps(sorted(set(targets))), json.dumps(outputs, allow_nan=False)))
        except (ValueError, TypeError, KeyError, ArithmeticError) as failure:
            outputs = {}; error = str(failure); errors += 1
        values = (row["id"], program.version, row["content_hash"], version, now,
                  json.dumps(inputs, ensure_ascii=False, allow_nan=False), json.dumps(outputs, allow_nan=False), error)
        changed.append(values); history.append((*values, row["vector"]))
    with db:
        db.execute("INSERT OR IGNORE INTO rating_programs VALUES (?,?)", (program.version, source))
        db.executemany("INSERT OR REPLACE INTO ratings VALUES (?,?,?,?,?,?,?,?)", changed)
        db.executemany("INSERT OR IGNORE INTO rating_history VALUES (?,?,?,?,?,?,?,?,?)", history)
        db.executemany("INSERT INTO news_events(event_key,created_at,information_id,model_version,symbols,outputs) SELECT ?,?,?,?,?,? WHERE NOT EXISTS (SELECT 1 FROM news_events WHERE event_key=?)", [(*event, event[0]) for event in events])
    return {"rated": len(changed), "errors": errors, "model_version": program.version, "rated_at": now,
            "outputs": program.outputs, "parameters": program.parameters}

def custom_inputs(root):
    path = root / "work/models/news-inputs.json"
    return json.loads(path.read_text(encoding="utf-8-sig")) if path.exists() else {}

def rating_weights(store, rows):
    # Missing ratings retain the legacy tensor weight until a rating model has run.
    initialize(store.db)
    saved = {row[0]: row for row in store.db.execute("SELECT information_id,outputs,error,content_hash,encoder_version FROM ratings")}
    rating_active = store.db.execute("SELECT count(*) FROM rating_programs").fetchone()[0] > 0
    weights = []
    for row in rows:
        rating = saved.get(row["id"])
        if rating is None: weights.append(0.0 if rating_active else 1.0); continue
        if rating[2] or rating[3] != row["content_hash"] or rating[4] != row["encoder_version"]: weights.append(0.0); continue
        weight = json.loads(rating[1]).get("tensor_weight", 1)
        weights.append(float(weight) if math.isfinite(weight) and 0 <= weight <= 1 else 0.0)
    return np.array(weights)


def event_snapshot(store):
    events = [{"sequence": sequence, "id": key, "created_at": at, "information_id": identity,
               "model_version": version, "symbols": json.loads(symbols), "outputs": json.loads(outputs)}
              for sequence, key, at, identity, version, symbols, outputs in store.db.execute(
                  "SELECT sequence,event_key,created_at,information_id,model_version,symbols,outputs FROM news_events ORDER BY sequence DESC LIMIT 2000")]
    return {"latest": events[0]["sequence"] if events else 0, "events": list(reversed(events))}

"""Local JSON bridge for the model editor. No broker access or order submission."""
import argparse
import json
from pathlib import Path
import sqlite3
import time
import hashlib
from datetime import datetime, timezone
from .model_language import Program, INPUTS
from .news_ratings import model_source
from .model_workbook import parse_workbook

def date(value):
    return datetime.fromtimestamp(value, timezone.utc).isoformat() if value is not None else None

def news_data(root, offset=0, search="", reference=None):
    from .model_profiles import runtime, revision
    if reference and json.loads(revision(root, reference, "news")["contents"]["config"]).get("engine") == "symbol_weighted_tensor_v1":
        from .news_weighted_profile import data
        return data(root, reference, offset, search)
    file = runtime(root, reference) / "information.sqlite" if reference else root / "work/news-tensor/information.sqlite"
    if not file.exists(): return {"columns": [], "rows": [], "total": 0, "message": "No retained news information yet"}
    db = sqlite3.connect(file.as_uri() + "?mode=ro", uri=True); db.row_factory = sqlite3.Row
    rated = db.execute("SELECT count(*) FROM sqlite_master WHERE name='ratings'").fetchone()[0] > 0
    script = root / "work/models/news-rating.talg"
    source = revision(root, reference, "news")["contents"]["source"] if reference else script.read_text(encoding="utf-8-sig") if script.exists() else ""
    current_version = hashlib.sha256(source.encode()).hexdigest() if source else None
    query = "%" + search + "%"
    total = db.execute("SELECT count(*) FROM information WHERE title LIKE ? OR publisher LIKE ?", (query, query)).fetchone()[0]
    fields = "r.outputs,r.inputs,r.rated_at,r.model_version AS rating_version,r.error,r.content_hash AS rated_content,r.encoder_version AS rated_encoder" if rated else "NULL AS outputs,NULL AS inputs,NULL AS rated_at,NULL AS rating_version,NULL AS error,NULL AS rated_content,NULL AS rated_encoder"
    join = " LEFT JOIN ratings r ON r.information_id=i.id " if rated else " "
    records = db.execute("SELECT i.*, " + fields + " FROM information i" + join +
                         "WHERE title LIKE ? OR publisher LIKE ? ORDER BY event_time DESC,id LIMIT 200 OFFSET ?", (query, query, offset)).fetchall()
    rows = []; outputs = set()
    for record in records:
        scores = json.loads(record["outputs"] or "{}"); outputs.update(scores)
        links = [dict(row) for row in db.execute("SELECT ticker,kind,evidence FROM associations WHERE information_id=?", (record["id"],))]
        is_current = record["rated_content"] == record["content_hash"] and record["rated_encoder"] == record["encoder_version"] and record["rating_version"] == current_version
        data = {"id": record["id"], "headline": record["title"], "publisher": record["publisher"],
                "published": date(record["event_time"]), "text": record["text"], "url": record["url"],
                "tickers": sorted({link["ticker"] for link in links}), "associations": links,
                "tensor_dimensions": len(record["vector"] or b"") // 4,
                "encoded_at": date(record["encoded_at"]), "encoder_version": record["encoder_version"],
                "rated_at": date(record["rated_at"]), "rating_version": record["rating_version"],
                "rating_state": record["error"] or ("Rated" if is_current else "Needs rating"),
                "inputs": json.loads(record["inputs"] or "{}"), "outputs": scores,
                "first_seen": date(record["first_seen"]), "last_observed": date(record["observed_at"])}
        if record["vector"]:
            import numpy as np
            data["embedding"] = np.frombuffer(record["vector"], dtype=np.float32).tolist()
        for key, val in scores.items(): data["output." + key] = val
        rows.append(data)
    db.close()
    columns = ["headline", *["output." + key for key in sorted(outputs)], "tensor_dimensions", "publisher", "tickers", "rating_state", "rated_at"]
    return {"columns": columns, "rows": rows, "total": total, "offset": offset,
            "message": "Saved outputs with rating timestamps; select a row for retained inputs, evidence and vector"}

def ticker_data(root, search="", reference=None):
    file = root / "work/rapid-paper/session.json"
    if not file.exists(): return {"columns": [], "rows": [], "total": 0, "message": "No retained trading session"}
    session = json.loads(file.read_text(encoding="utf-8-sig")); signals = {}
    if reference:
        from .model_profiles import revision
        requested = revision(root, reference, "trade")
        actual = revision(root, session["profileRevision"], "trade") if session.get("profileRevision") else {"profile":"default"}
        if requested["profile"] != actual["profile"]:
            return {"columns": [], "rows": [], "total": 0, "message": "No retained trading session for this profile; Run previews it on retained market inputs"}
    for path in sorted((root / "work/rapid-paper").glob("events-*.jsonl")):
        with path.open(encoding="utf-8-sig") as stream:
            for line in stream:
                try: event = json.loads(line)
                except json.JSONDecodeError: continue
                if event.get("sessionId") == session.get("id") and event.get("event") == "SIGNAL":
                    detail = event.get("detail", {}); signals[detail.get("symbol")] = dict(detail, evaluated_at=event.get("at"))
    rows = []; outputs = set()
    for ticker, retained in session.get("legs", {}).items():
        if search.casefold() not in ticker.casefold(): continue
        signal = signals.get(ticker, {})
        if retained.get("modelEvaluatedAt"):
            signal = {"values": retained.get("modelValues", {}), "evaluated_at": retained.get("modelEvaluatedAt"),
                      "decision": retained.get("modelDecision"), "bars": retained.get("retainedBars", [])}
        values = signal.get("values", {}); outputs.update(values)
        row = {"ticker": ticker, "state": retained.get("phase"), "decision": retained.get("message"),
               "evaluated_at": signal.get("evaluated_at"), "retained": retained, "evaluation": signal,
               "session_profile_revision": session.get("profileRevision"), "session_id": session.get("id"), "session_date": session.get("date"),
               "last_scan": session.get("lastScan"), "session_model": session.get("model"),
               "output_state": ("Evaluation failed: " + retained["modelError"]) if retained.get("modelError") else "Retained evaluation" if signal else "No retained evaluation"}
        for key, val in values.items(): row["output." + key] = val
        rows.append(row)
    return {"columns": ["ticker", "state", "decision", "output_state", "evaluated_at", *["output." + key for key in sorted(outputs)]],
            "rows": rows, "total": len(rows), "message": "Retained session observations and model values, not fresh forecasts"}

def workbook(root, news, reference=None):
    if reference:
        from .model_profiles import revision
        contents = revision(root, reference, "news" if news else "trade")["contents"]
        source = contents["source"] if news else json.loads(contents["config"]).get("signal", {}).get("script", "")
        return parse_workbook(source)[1]
    path = root / ("work/models/news-rating.talg" if news else "work/strategies/rapid-paper.json")
    if not path.exists(): return None
    source = path.read_text(encoding="utf-8-sig")
    if not news: source = json.loads(source).get("signal", {}).get("script", "")
    return parse_workbook(source)[1]

def main():
    parser = argparse.ArgumentParser(); parser.add_argument("command")
    parser.add_argument("--root", type=Path, required=True); parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--code", type=Path); parser.add_argument("--offset", type=int, default=0); parser.add_argument("--search", default="")
    parser.add_argument("--revision")
    args = parser.parse_args(); root = args.root.resolve()
    try:
        if args.command in ("stock-validate", "stock-run"):
            from .stock_selection import compile_selection, evaluate
            source = args.code.read_text(encoding="utf-8-sig")
            program = compile_selection(source)
            result = {"valid": True, "parameters": program.parameters, "outputs": program.outputs} if args.command == "stock-validate" else evaluate(root, source, args.revision)
        elif args.command == "validate":
            program = Program(args.code.read_text(encoding="utf-8-sig"))
            result = {"valid": True, "outputs": program.outputs, "parameters": program.parameters, "inputs": INPUTS}
        elif args.command == "news-symbols":
            from .news_symbols import call
            symbols = [symbol.strip().upper() for symbol in args.search.split(",") if symbol.strip()]
            if not symbols: raise ValueError("Supply requested symbols using --search AAPL,MSFT")
            result = call(root, symbols, args.revision)
        elif args.command == "news-data": result = news_data(root, max(0, args.offset), args.search, args.revision)
        elif args.command == "tickers": result = ticker_data(root, args.search, args.revision)
        elif args.command == "ticker-inputs": result = ticker_data(root, args.search)
        elif args.command == "initialize": result = {"source": model_source(root), "inputs": INPUTS}
        elif args.command == "refresh-news":
            from .model_profiles import news_reference, refresh_news
            from .news_tensor import run
            ref = news_reference(root, args.revision)
            from .model_profiles import revision
            if json.loads(revision(root, ref, "news")["contents"]["config"]).get("engine") != "symbol_weighted_tensor_v1":
                run(root, once=True, refresh_profiles=False)
            result = refresh_news(root, ref)
        elif args.command == "run":
            from .news_tensor import run
            if args.revision:
                from .model_profiles import refresh_news, revision
                saved = revision(root, args.revision, "news")
                # Ingest and encode first; then evaluate the exact saved profile revision.
                if json.loads(saved["contents"]["config"]).get("engine") != "symbol_weighted_tensor_v1":
                    run(root, once=True, refresh_profiles=False)
                result = refresh_news(root, args.revision)
            else:
                run(root, once=True)
                result = json.loads((root / "work/news-tensor/rating-status.json").read_text())
        else: raise ValueError("Unknown workbench command")
        if args.command in ("news-data", "tickers"):
            result["workbook"] = workbook(root, args.command == "news-data", args.revision)
        if args.revision: result.setdefault("profile_revision", args.revision)
        args.out.write_text(json.dumps(result, ensure_ascii=False, allow_nan=False), encoding="utf-8")
    except Exception as error:
        args.out.write_text(json.dumps({"error": str(error)}), encoding="utf-8"); raise

if __name__ == "__main__": main()

"""Callable symbol-level news model; results are retained for the trading adapter."""
import json
import sqlite3
import time
from pathlib import Path
from .model_language import Program

def evaluate_symbols(db, source, symbols=None, now=None):
    now = time.time() if now is None else now
    program = Program(source)
    if program.symbol is None: raise ValueError("News code has no symbol / end symbol program")
    grouped = {symbol: [] for symbol in (symbols or [])}
    records = db.execute("""SELECT DISTINCT a.ticker,i.id,i.title,i.publisher,i.event_time,r.outputs,r.rated_at
        FROM associations a JOIN information i ON i.id=a.information_id JOIN ratings r ON r.information_id=i.id
        WHERE r.error='' AND r.model_version=? AND r.content_hash=i.content_hash AND r.encoder_version=i.encoder_version
        ORDER BY a.ticker,i.event_time DESC,i.id""", (program.version,))
    accepted = set(symbols) if symbols is not None else None
    for ticker, identity, headline, publisher, event_time, outputs, rated_at in records:
        if accepted is not None and ticker not in accepted: continue
        grouped.setdefault(ticker, []).append({"id": identity, "headline": headline, "publisher": publisher,
            "age_seconds": max(0, now-event_time), "rated_at": rated_at, "outputs": json.loads(outputs)})
    result = {"model_version": program.version, "evaluated_at": now, "symbols": {}}
    for symbol, articles in grouped.items():
        try:
            outputs = program.symbol.run({"symbol": symbol, "articles": articles, "now": now})
            result["symbols"][symbol] = {"outputs": outputs, "article_count": len(articles),
                "article_ids": [article["id"] for article in articles], "error": ""}
        except (ValueError, TypeError, KeyError, ArithmeticError) as error:
            result["symbols"][symbol] = {"outputs": {}, "article_count": len(articles), "error": str(error)}
    return result

def call(root: Path, symbols, reference=None):
    source = (root / "work/models/news-rating.talg").read_text(encoding="utf-8-sig") if not reference else ""
    file = (root / "work/news-tensor/information.sqlite").resolve()
    if reference:
        from .model_profiles import revision, runtime
        contents = revision(root, reference, "news")["contents"]
        if json.loads(contents["config"]).get("engine") == "symbol_weighted_tensor_v1":
            from .news_weighted_profile import call as weighted_call
            return weighted_call(root, symbols, reference)
        source = contents["source"]
        file = (runtime(root, reference) / "information.sqlite").resolve()
    with sqlite3.connect(file.as_uri() + "?mode=ro", uri=True) as db:
        return evaluate_symbols(db, source, symbols)

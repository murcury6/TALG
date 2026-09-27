"""Independent stock selection program; selects a universe, never places orders."""
import hashlib
import json
import math
import re
import time
from .model_language import Program

ALL_AVAILABLE = {"all_available", "all available", "all"}

def all_available(value):
    return isinstance(value, str) and value.strip().lower() in ALL_AVAILABLE

INPUTS = {"execution_supported": "Whether the current execution adapter supports this ticker syntax", "asset": "Broker asset metadata, or an empty object for an uncatalogued explicit ticker", "symbol": "Candidate ticker", "predictor_covered": "Whether the saved trading predictor covers this ticker",
          "news": "Current saved news model's symbol outputs, or an empty object", "news_available": "Whether matching news outputs exist",
          "news_age_seconds": "Age of news result; -1 when unavailable", "retained": "Retained trading record, or an empty object",
          "custom": "Local defaults merged with per-symbol custom inputs"}

def compile_selection(source):
    program = Program(source, INPUTS, allow_symbol=False)
    candidates = program.parameters.get("candidates")
    if not all_available(candidates) and (not isinstance(candidates, list) or not candidates or any(not isinstance(s, str) or not re.fullmatch(r"[A-Z][A-Z0-9.\-]{0,14}", s) for s in candidates) or len(set(candidates)) != len(candidates)):
        raise ValueError('Declare param candidates = ["AAPL", "MSFT"] with unique tickers, or param candidates = "all_available"')
    if "include" not in program.outputs: raise ValueError("Declare output include = your_boolean_condition")
    limit = program.parameters.get("max_stocks", 120)
    if type(limit) is not int or not 1 <= limit <= 120: raise ValueError("max_stocks must be an integer from 1 to 120")
    return program

def evaluate(root, source, reference=None):
    from .model_workbench import ticker_data
    program = compile_selection(source); now = time.time()
    def read(relative, default):
        path = root / relative
        return json.loads(path.read_text(encoding="utf-8-sig")) if path.exists() else default
    profile = None; news_ref = None
    if reference:
        from .model_profiles import revision, news_reference, runtime
        profile = revision(root, reference, "stocks")
        news_ref = news_reference(root, reference)
    config = json.loads(profile["contents"].get("trading", "{}")) if profile else read("work/strategies/rapid-paper.json", {})
    predictor = config.get("prediction", {}).get("modelFile", "")
    coverage = set()
    if predictor:
        path = (root / predictor).resolve()
        if not path.is_relative_to(root.resolve()): raise ValueError("Predictor must be inside the project")
        coverage = set(read(predictor, {}).get("symbols", []))
    news = read("work/news-tensor/symbol-ratings.json", {})
    if news_ref:
        path = runtime(root, news_ref) / "symbol-ratings.json"
        news = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
        if news and news.get("profile_revision") != news_ref: raise ValueError("News result belongs to another profile revision")
    news_file = root / "work/models/news-rating.talg"
    current_version = hashlib.sha256(news_file.read_text(encoding="utf-8-sig").encode()).hexdigest() if news_file.exists() else None
    if news_ref:
        current_version = hashlib.sha256(revision(root, news_ref, "news")["contents"]["source"].encode()).hexdigest()
    retained = {item["ticker"]: item for item in ticker_data(root)["rows"]}
    custom = json.loads(profile["contents"].get("inputs", "{}")) if profile else read("work/models/stock-selection-inputs.json", {})
    candidates = program.parameters["candidates"]
    catalogue = read("work/stocks/available-assets.json", {})
    assets = {}
    if catalogue:
        if catalogue.get("scope") != "active_tradable_us_equity" or not isinstance(catalogue.get("assets"), list):
            raise ValueError("Invalid available-stock catalogue; refresh it from Stocks")
        for asset in catalogue["assets"]:
            if asset.get("status") == "active" and asset.get("tradable") is True and asset.get("class") == "us_equity":
                symbol = asset.get("symbol")
                if not isinstance(symbol, str) or not re.fullmatch(r"[A-Z][A-Z0-9.\-]{0,14}", symbol):
                    raise ValueError("Invalid symbol in available-stock catalogue")
                assets[symbol] = asset
    if all_available(candidates):
        if not assets: raise ValueError('All available needs the broker asset catalogue. Connect Alpaca and use Stocks > More > Refresh available stocks.')
        candidates = sorted(assets)
    rows = []; accepted = []; errors = 0
    for symbol in candidates:
        item = news.get("symbols", {}).get(symbol, {})
        at = news.get("evaluated_at")
        available = bool(item) and not item.get("error") and current_version is not None and news.get("model_version") == current_version and isinstance(at, (int, float)) and math.isfinite(at) and at <= now + 5
        supplied = dict(custom.get("defaults", {})); supplied.update(custom.get("symbols", {}).get(symbol, {}))
        inputs = {"symbol": symbol, "asset": assets.get(symbol, {}), "execution_supported": bool(re.fullmatch(r"[A-Z]{1,5}",symbol)), "predictor_covered": symbol in coverage, "news": item.get("outputs", {}) if available else {},
                  "news_available": bool(available), "news_age_seconds": max(0, now-at) if available else -1,
                  "retained": retained.get(symbol, {}), "custom": supplied}
        output = {}; error = ""
        try:
            output = program.run(inputs)
            if type(output["include"]) is not bool: raise ValueError("include must be boolean")
            priority = output.get("priority", 0)
            if type(priority) not in (int, float) or not math.isfinite(priority): raise ValueError("priority must be a finite number")
            if output["include"]: accepted.append((symbol, priority))
        except (ValueError, TypeError, KeyError, ArithmeticError) as problem:
            errors += 1; error = str(problem)
        rows.append({"ticker": symbol, "outputs": output, "inputs": inputs, "error": error,
                     "news_profile_revision": news_ref, "news_version": news.get("model_version"), "evaluated_at": now})
    accepted.sort(key=lambda item: -item[1])
    selected = [symbol for symbol, _ in accepted[:program.parameters.get("max_stocks", 120)]]
    selected_set = set(selected)
    for row in rows: row["selected"] = row["ticker"] in selected_set
    return {"profile_revision": reference, "news_profile_revision": news_ref, "rows": rows, "selected": selected, "errors": errors, "total": len(rows), "model_version": program.version,
            "evaluated_at": now, "source": source, "workbook": program.workbook,
            "candidate_mode": "all_available" if all_available(program.parameters["candidates"]) else "explicit",
            "available_catalogue": {key: catalogue.get(key) for key in ("source", "scope", "fetched_at", "account_mode")} if catalogue else None,
            "columns": ["ticker", "selected", "outputs", "error"]}

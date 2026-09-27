"""Small local cross-encoder judges symbol-context/article relevance before tensorizing.

MS MARCO scores are uncalibrated retrieval scores, NOT probabilities or price effects.
"""
from __future__ import annotations

import hashlib
import json
import math
import sqlite3
import time
from collections import Counter
from datetime import UTC, datetime
from pathlib import Path

import numpy as np

from .news_tensor import digest


class RelevanceAI:
    def __init__(self, root, config):
        import onnxruntime as ort
        from tokenizers import Tokenizer
        self.config = config
        folder = Path(root) / config["directory"]
        manifest = json.loads((folder / "manifest.json").read_text())
        if manifest["model"] != config["model"] or manifest["revision"] != config["revision"]:
            raise ValueError("Relevance model version mismatch; run tools/setup-news-relatedness.py")
        for name, expected in config["sha256"].items():
            if hashlib.sha256((folder / name).read_bytes()).hexdigest() != expected:
                raise ValueError("Relevance model checksum mismatch: " + name)
        options = ort.SessionOptions()
        options.intra_op_num_threads = config["cpu_threads"]
        options.inter_op_num_threads = 1
        self.session = ort.InferenceSession(str(folder / "model_quantized.onnx"), options, providers=["CPUExecutionProvider"])
        self.tokenizer = Tokenizer.from_file(str(folder / "tokenizer.json"))
        self.tokenizer.enable_padding(pad_id=self.tokenizer.token_to_id("[PAD]"), pad_token="[PAD]")
        self.tokenizer.enable_truncation(max_length=config["max_tokens"], strategy="only_second", stride=config["window_stride"])
        self.version = digest(json.dumps(config, sort_keys=True))

    def score(self, query, texts):
        # Keep the first sequence within budget; its exact text is retained in the audit.
        encoded = self.tokenizer.encode_batch([(query, text) for text in texts])
        windows, owners = [], []
        for index, item in enumerate(encoded):
            windows.append(item)
            owners.append(index)
            for overflow in item.overflowing:
                windows.append(overflow)
                owners.append(index)
        scores = np.full(len(texts), -np.inf, dtype=np.float64)
        for start in range(0, len(windows), self.config["inference_batch_size"]):
            batch = windows[start:start + self.config["inference_batch_size"]]
            width = max(len(item.ids) for item in batch)
            ids = np.zeros((len(batch), width), dtype=np.int64)
            mask = np.zeros_like(ids)
            types = np.zeros_like(ids)
            for row, item in enumerate(batch):
                ids[row, :len(item.ids)] = item.ids
                mask[row, :len(item.ids)] = item.attention_mask
                types[row, :len(item.ids)] = item.type_ids
            data = {"input_ids": ids, "attention_mask": mask, "token_type_ids": types}
            logits = np.asarray(self.session.run(None, {i.name: data[i.name] for i in self.session.get_inputs()})[0]).reshape(-1)
            if logits.shape != (len(batch),) or not np.isfinite(logits).all():
                raise ValueError("Invalid relevance scores")
            for owner, score in zip(owners[start:start + len(batch)], logits, strict=True):
                scores[owner] = max(scores[owner], float(score))
        return scores


def context_queries(symbol, model):
    entities = model["entities"]
    identity = next((k for k, e in entities.items() if e.get("symbol") == symbol), None)
    if identity is None:
        return [{"id": "company", "kind": "company", "query": f"{symbol} company financial business earnings news"}]

    def company_query(key):
        entity = entities[key]
        names = entity.get("aliases", [])[:2] or [entity["name"]]
        return " ".join(dict.fromkeys(names)) + " company business earnings revenue production operations news"

    result = [{"id": "company", "kind": "company", "query": company_query(identity)}]
    for sector in entities[identity].get("sectors", []):
        terms = model["sectors"][sector]["terms"][:4]
        result.append({"id": "sector:" + sector, "kind": "sector", "query": ", ".join(terms) + " industry business developments news"})
    for edge in model["relationships"]:
        today = datetime.now(UTC).date().isoformat()
        if edge.get("valid_from", "0000-01-01") > today or edge.get("valid_through", "9999-12-31") < today:
            continue
        other = edge["supplier"] if edge["customer"] == identity else edge["customer"] if edge["supplier"] == identity else None
        if other:
            result.append({"id": "counterparty:" + other, "kind": "counterparty", "via_entity": other,
                           "query": company_query(other), "source_url": edge["source_url"]})
    return list({r["id"]: r for r in result}.values())


def eligible(article, facet):
    if facet["kind"] != "counterparty":
        return True
    return any(reason.get("via_entity") == facet["via_entity"] for reason in article["relations"])


def classify_score(score, config):
    keep = float(config["keep_min_score"])
    strong = float(config["related_min_score"])
    if not all(math.isfinite(x) for x in (keep, strong, score)) or keep > strong:
        raise ValueError("Invalid relevance score or thresholds")
    if score >= strong:
        return "related", True
    if score >= keep:
        return "borderline", True
    return "unrelated", False


def filter_articles(selected, model, root, cache_path, scorer=None, progress=None):
    config = model["ai"]
    classify_score(0, config)
    scorer = scorer or RelevanceAI(root, config)
    version = digest(json.dumps(config, sort_keys=True))
    db = sqlite3.connect(cache_path)
    db.execute("""CREATE TABLE IF NOT EXISTS scores (
        identity TEXT PRIMARY KEY, model_version TEXT, symbol TEXT, query TEXT,
        information_id TEXT, content_hash TEXT, score REAL, scored_at REAL)""")
    filtered, audit, counts = {}, {}, {}
    filter_version = digest(json.dumps(model, sort_keys=True))
    try:
        for symbol, articles in selected.items():
            facets = context_queries(symbol, model)
            filtered[symbol], audit[symbol] = [], []
            evidence = {a["id"]: [] for a in articles}
            for facet in facets:
                query = facet["query"]
                candidates = [a for a in articles if eligible(a, facet)]
                pending, scored = [], {}
                for article in candidates:
                    key = digest(json.dumps([version, query, article["id"], article["content_hash"], article["text"]]))
                    saved = db.execute("SELECT score,scored_at FROM scores WHERE identity=?", (key,)).fetchone()
                    if saved is not None:
                        scored[article["id"]] = saved
                    else:
                        pending.append((key, article))
                for start in range(0, len(pending), config["inference_batch_size"]):
                    batch = pending[start:start + config["inference_batch_size"]]
                    values = scorer.score(query, [a["text"] for _, a in batch])
                    if len(values) != len(batch):
                        raise ValueError("Incomplete relevance scores")
                    at = time.time()
                    with db:
                        for (key, article), value in zip(batch, values, strict=True):
                            score = float(value)
                            classify_score(score, config)
                            db.execute("INSERT INTO scores VALUES (?,?,?,?,?,?,?,?)", (key, version, symbol, query, article["id"], article["content_hash"], score, at))
                            scored[article["id"]] = (score, at)
                    if progress and (start % 512 == 0 or start + len(batch) == len(pending)):
                        progress(f"Relatedness {symbol} {facet['id']}: {start + len(batch)}/{len(pending)} new scores")
                for article in candidates:
                    score, at = scored[article["id"]]
                    evidence[article["id"]].append(dict(facet, score=score, scored_at=at))
            counter = Counter()
            for article in articles:
                contexts = evidence[article["id"]]
                best = max(contexts, key=lambda c: c["score"])
                score = best["score"]
                state, include = classify_score(score, config)
                ai_state, reason = state, "ai_score"
                policy = model.get("inclusion_policy", {})
                entity = any(r["kind"] == "company_reference" for r in article["relations"])
                counterparty = any(r["kind"] in {"supplier_event", "customer_event"} and
                    any(e["kind"] == "company_reference" for e in r.get("anchor_evidence", [])) for r in article["relations"])
                if not include and ((entity and policy.get("retain_explicit_company_reference")) or
                                    (counterparty and policy.get("retain_supported_counterparty_event"))):
                    state, include = "borderline", True
                    reason = "explicit_company_evidence" if entity else "supported_counterparty_event"
                decision = {"information_id": article["id"], "content_hash": article["content_hash"],
                    "state": state, "ai_state": ai_state, "inclusion_reason": reason, "filter_version": filter_version, "include": include, "score": score, "score_is_probability": False,
                    "model_version": version, "evaluated_at": time.time(), "best_context": best["id"], "contexts": contexts,
                    "candidate_reasons": article["relations"]}
                audit[symbol].append(decision)
                counter[state] += 1
                if include:
                    filtered[symbol].append(dict(article, relevance_ai=decision))
            counts[symbol] = dict(counter)
    finally:
        db.close()
    return filtered, audit, {"model": config["model"], "model_version": version, "counts": counts,
                             "filter_version": filter_version, "policy": "max AI context score plus configured explicit-entity/economic-link retention; retained overrides are borderline; scores uncalibrated"}

"""Broad, auditable relatedness BEFORE symbol/article encoding.

An inclusion is a research candidate, not a causal or directional prediction.
The declared graph is one-hop only; inferred links never become fresh anchors.
"""
from __future__ import annotations

import copy
import hashlib
import json
import re
import unicodedata
from collections import Counter, defaultdict
from datetime import UTC, date, datetime

ALGORITHM = "broad_relatedness_v1"


def canonical_text(text):
    return " ".join(unicodedata.normalize("NFKC", text).casefold().split())


def phrase_pattern(phrases):
    if not phrases:
        return None
    if any(not isinstance(p, str) or len(p.strip()) < 2 for p in phrases):
        raise ValueError("Relatedness phrases must have at least two characters")
    return re.compile(r"(?<!\w)(?:" + "|".join(re.escape(canonical_text(p)) for p in sorted(set(phrases), key=lambda x: (-len(x), x))) + r")(?!\w)")


def matches(pattern, text):
    return sorted({m.group(0) for m in pattern.finditer(text)}) if pattern else []


def model_version(model):
    return hashlib.sha256(json.dumps({"algorithm": ALGORITHM, "model": model}, sort_keys=True).encode()).hexdigest()


class RelatednessModel:
    def __init__(self, model):
        self.model = copy.deepcopy(model)
        if model["schema_version"] != 1 or model["algorithm"] != ALGORITHM or model["max_hops"] != 1:
            raise ValueError("Unsupported relatedness contract")
        self.version = model_version(model)
        self.entities = model["entities"]
        self.sectors = model["sectors"]
        self.aliases, self.guarded, self.symbols = {}, {}, {}
        self.members = defaultdict(set)
        self.edges = defaultdict(list)
        self.events = phrase_pattern(model["material_event_terms"])
        self.sector_patterns = {key: phrase_pattern(value["terms"]) for key, value in self.sectors.items()}
        for identity, entity in self.entities.items():
            symbol = entity.get("symbol")
            if symbol:
                if not re.fullmatch(r"[A-Z0-9][A-Z0-9.^-]{0,14}", symbol) or symbol in self.symbols:
                    raise ValueError("Invalid or duplicate entity symbol")
                self.symbols[symbol] = identity
            self.aliases[identity] = phrase_pattern(entity.get("aliases", []))
            self.guarded[identity] = [(phrase_pattern(item["aliases"]), phrase_pattern(item["require_any"])) for item in entity.get("guarded_aliases", [])]
            if any(context is None for _, context in self.guarded[identity]):
                raise ValueError("Ambiguous aliases require context")
            for sector in entity.get("sectors", []):
                if sector not in self.sectors:
                    raise ValueError("Unknown entity sector: " + sector)
                self.members[sector].add(identity)
        for edge in model["relationships"]:
            if edge["supplier"] not in self.entities or edge["customer"] not in self.entities or edge["supplier"] == edge["customer"]:
                raise ValueError("Supply-chain endpoints must be distinct known entities")
            if not edge.get("source_url", "").startswith("https://") or not edge.get("evidence"):
                raise ValueError("Supply-chain edges require evidence and a source URL")
            date.fromisoformat(edge["reviewed_on"])
            if edge.get("valid_from"):
                date.fromisoformat(edge["valid_from"])
            if edge.get("valid_through"):
                date.fromisoformat(edge["valid_through"])
            self.edges[edge["supplier"]].append((edge["customer"], "supplier_event", edge))
            self.edges[edge["customer"]].append((edge["supplier"], "customer_event", edge))
        self.macro = []
        for rule in model.get("macro_rules", []):
            if any(s not in self.sectors for s in rule["sectors"]):
                raise ValueError("Unknown macro sector")
            self.macro.append((rule, phrase_pattern(rule["terms"])))

    def route(self, article, direct_links, as_of_date=None):
        text = canonical_text(article["text"])
        today = (as_of_date or datetime.now(UTC).date()).isoformat()
        routes = defaultdict(list)
        anchors = defaultdict(list)
        event_terms = matches(self.events, text)

        def include(identity, reason):
            symbol = self.entities[identity].get("symbol")
            if symbol:
                routes[symbol].append(reason)

        for link in direct_links:
            symbol = link["ticker"]
            reason = {"kind": "direct_association", "association_kind": link["kind"], "evidence": link["evidence"]}
            routes[symbol].append(reason)
            if symbol in self.symbols:
                anchors[self.symbols[symbol]].append(reason)
        for identity, entity in self.entities.items():
            terms = matches(self.aliases[identity], text)
            for alias, context in self.guarded[identity]:
                if matches(context, text):
                    terms.extend(matches(alias, text))
            symbol = entity.get("symbol")
            # Bare tickers are deliberately not entities: CAT, ARM, IT, etc.
            explicit = []
            if symbol:
                pattern = r"(?<![\w$])\$" + re.escape(symbol) + r"(?![\w.\-])|\b(?:NASDAQ|NYSE|AMEX|NYSEARCA)\s*:\s*" + re.escape(symbol) + r"(?![\w.\-])"
                explicit = re.findall(pattern, article["text"], flags=re.IGNORECASE)
            if terms or explicit:
                reason = {"kind": "company_reference", "entity": identity, "matched_terms": sorted(set(terms + explicit))}
                anchors[identity].append(reason)
                include(identity, reason)

        # Sector-topic stories need not name any company.
        for sector, pattern in self.sector_patterns.items():
            terms = matches(pattern, text)
            if terms:
                for identity in self.members[sector]:
                    include(identity, {"kind": "sector_topic", "sector": sector, "matched_terms": terms,
                                       "evidence": self.sectors[sector]["rationale"]})
        for rule, pattern in self.macro:
            terms = matches(pattern, text)
            if terms:
                for sector in rule["sectors"]:
                    for identity in self.members[sector]:
                        include(identity, {"kind": "macro_exposure", "rule": rule["id"], "sector": sector,
                                           "matched_terms": terms, "evidence": rule["rationale"]})

        # Expand only direct anchors, never the inferred recipients above.
        if event_terms:
            for anchor in sorted(anchors):
                for sector in self.entities[anchor].get("sectors", []):
                    if not self.sectors[sector].get("expand_company_events", True):
                        continue
                    for target in self.members[sector] - {anchor}:
                        include(target, {"kind": "sector_peer_event", "via_entity": anchor, "sector": sector,
                                         "matched_event_terms": event_terms, "anchor_evidence": anchors[anchor]})
                for target, kind, edge in self.edges[anchor]:
                    if edge.get("valid_from", "0000-01-01") > today or edge.get("valid_through", "9999-12-31") < today:
                        continue
                    include(target, {"kind": kind, "via_entity": anchor, "matched_event_terms": event_terms,
                                     "anchor_evidence": anchors[anchor], "relationship": edge})
        # Canonical ordering and deduplication keep snapshots reproducible.
        return {symbol: [json.loads(reason) for reason in sorted({json.dumps(r, sort_keys=True) for r in reasons})]
                for symbol, reasons in sorted(routes.items())}

    def coverage(self, symbol):
        identity = self.symbols.get(symbol)
        if identity is None:
            return {"entity_configured": False, "sector_context": [], "supply_chain_edges": 0,
                    "state": "missing_entity_context", "complete_supply_chain": False}
        return {"entity_configured": True, "sector_context": self.entities[identity].get("sectors", []),
                "supply_chain_edges": len(self.edges[identity]), "state": "configured_broad_candidates",
                "complete_supply_chain": False}


def select_related(db, model, kinds, symbols=None):
    from .news_tensor import digest, plain
    router = RelatednessModel(model)
    links = defaultdict(list)
    accepted_kinds = set(kinds)
    # Manual links are explicit durable user assertions. Feed/provider links are
    # rebuilt from observations of the CURRENT content revision, avoiding stale
    # ticker assignments after corrections at the same URL.
    for row in db.execute("SELECT information_id,ticker,kind,evidence FROM associations WHERE kind='manual'"):
        if row[2] in accepted_kinds:
            links[row[0]].append({"ticker": row[1], "kind": row[2], "evidence": row[3]})
    for item_id, raw, current_hash in db.execute("SELECT o.information_id,o.raw,i.content_hash FROM observations o JOIN information i ON i.id=o.information_id"):
        record = json.loads(raw)
        if record.get("event") is not None:
            article = record["event"]
            title, summary = article.get("headline", ""), article.get("summary", "")
            tags = [(s, "provider_symbol", article.get("source", "Alpaca news")) for s in article.get("symbols", [])]
        else:
            article, source = record.get("article", {}), record.get("source", {})
            title, summary = article.get("title", ""), article.get("summary", "")
            tags = [(s, "provider_symbol", article.get("publisher", source.get("publisher", ""))) for s in article.get("symbols", [])]
            if source.get("symbol"):
                tags.append((source["symbol"], "ticker_feed", source.get("id", "")))
        if digest((plain(title) + "\n" + plain(summary)).casefold()) != current_hash:
            continue
        for symbol, kind, evidence in tags:
            symbol = symbol.strip().upper()
            if kind in accepted_kinds and re.fullmatch(r"[A-Z0-9][A-Z0-9.^-]{0,14}", symbol):
                value = {"ticker": symbol, "kind": kind, "evidence": evidence}
                if value not in links[item_id]:
                    links[item_id].append(value)
    accepted = set(symbols) if symbols is not None else None
    targets = accepted if accepted is not None else set(router.symbols) | {l["ticker"] for ls in links.values() for l in ls}
    selected = {s: [] for s in sorted(targets)}
    all_articles = model.get("ai", {}).get("candidate_scope") == "all_articles_for_requested_symbols"
    scanned = 0
    for row in db.execute("""SELECT id,content_hash,text,title,url,publisher,event_time,first_seen,observed_at
        FROM information ORDER BY id"""):
        article = dict(row)
        scanned += 1
        routes = router.route(article, links[article["id"]])
        for symbol in (sorted(targets) if all_articles else sorted(set(routes) & targets)):
            reasons = routes.get(symbol, [{"kind": "ai_full_collection_candidate", "evidence": "No hard keyword exclusion; AI evaluates company/sector relevance."}])
            selected[symbol].append(dict(article, relations=reasons, relatedness_version=router.version))
    coverage = {s: router.coverage(s) for s in selected}
    counts = {s: dict(sorted(Counter(r["kind"] for a in articles for r in a["relations"]).items())) for s, articles in selected.items()}
    return selected, {"algorithm": ALGORITHM, "version": router.version, "scanned_articles": scanned,
                      "coverage": coverage, "candidate_reason_counts": counts,
                      "meaning": "broad AI candidates, not causal effects or calibrated probabilities"}

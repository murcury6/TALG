import copy
import json
from datetime import date
from pathlib import Path

import numpy as np
import pytest

from talg_py.news_relatedness import RelatednessModel, select_related
from talg_py.news_relevance_ai import classify_score, context_queries, filter_articles
from talg_py.news_tensor import Store, digest, utc


def model():
    return json.loads((Path(__file__).parents[1] / "research/news-average/relatedness.json").read_text())


def article(text):
    return {"id": digest(text), "content_hash": digest(text), "text": text, "relations": []}


def test_supplier_customer_and_sector_routes_are_broad_but_one_hop():
    router = RelatednessModel(model())
    routes = router.route(article("TSMC factory halts production after a power outage"), [])
    assert {"AAPL", "NVDA", "TSM"} <= set(routes)
    assert any(r["kind"] == "supplier_event" for r in routes["AAPL"])
    routes = router.route(article("Apple reports quarterly revenue and iPhone demand"), [])
    assert "AVGO" in routes
    assert any(r["kind"] == "customer_event" for r in routes["AVGO"])
    routes = router.route(article("Nvidia reports lower earnings"), [])
    assert "TSM" in routes
    assert "AAPL" not in routes  # Inferred TSM is not recursively expanded.
    routes = router.route(article("Semiconductor industry faces export restrictions"), [])
    assert {"AMD", "NVDA", "MU"} <= set(routes)


def test_ambiguous_names_and_bare_tickers_do_not_create_company_anchors():
    router = RelatednessModel(model())
    for text in ["A cat climbs a tree", "A person broke an arm", "An apple pie recipe", "Amazon rainforest wildlife"]:
        assert router.route(article(text), []) == {}
    assert "CAT" in router.route(article("NYSE: CAT reports earnings"), [])
    assert "AAPL" in router.route(article("$AAPL announces earnings"), [])
    assert "AAPL" not in router.route(article("$AAPLX announces earnings"), [])
    assert "NVDA" not in router.route(article("Nvidiagram hosts a picnic"), [])


def test_invalid_edges_and_expired_relationships():
    cfg = model()
    invalid = copy.deepcopy(cfg)
    invalid["relationships"][0]["source_url"] = ""
    with pytest.raises(ValueError):
        RelatednessModel(invalid)
    for edge in cfg["relationships"]:
        if edge["customer"] == "AAPL":
            edge["valid_through"] = "2020-01-01"
    routes = RelatednessModel(cfg).route(article("TSMC halts factory production"), [], date(2026, 9, 26))
    assert "AAPL" not in routes


def test_current_revision_links_and_every_article_is_ai_candidate(tmp_path):
    store = Store(tmp_path)
    def record(title, symbol, at):
        return {"collectedAt": utc(at), "source": {"symbol": symbol, "id": symbol},
                "article": {"title": title, "summary": "", "url": "https://example.test/story", "publishedAt": utc(at)}}
    try:
        store.ingest(record("Old company topic", "AAPL", 1000), 1000)
        store.ingest(record("Corrected unrelated story", "MSFT", 1100), 1100)
        selected, report = select_related(store.db, model(), ["ticker_feed"], ["AAPL", "MSFT"])
        assert len(selected["AAPL"]) == len(selected["MSFT"]) == 1
        assert selected["AAPL"][0]["relations"][0]["kind"] == "ai_full_collection_candidate"
        assert any(r["kind"] == "direct_association" for r in selected["MSFT"][0]["relations"])
        assert report["scanned_articles"] == 1
        assert report["coverage"]["AAPL"]["complete_supply_chain"] is False
    finally:
        store.db.close()


class FakeAI:
    def __init__(self):
        self.calls = 0

    def score(self, query, texts):
        self.calls += 1
        return np.array([2.0 if "earnings" in t else -2.0 if "maybe" in t else -10.0 for t in texts])


def test_ai_filters_before_average_keeps_borderline_and_cache_is_stable(tmp_path):
    cfg = model()
    rows = [article("Apple earnings"), article("maybe industry news"), article("pie recipe")]
    ai = FakeAI()
    selected, audit, report = filter_articles({"AAPL": rows}, cfg, tmp_path, tmp_path / "scores.sqlite", scorer=ai)
    assert [a["text"] for a in selected["AAPL"]] == ["Apple earnings", "maybe industry news"]
    assert [a["state"] for a in audit["AAPL"]] == ["related", "borderline", "unrelated"]
    assert report["counts"]["AAPL"] == {"related": 1, "borderline": 1, "unrelated": 1}
    calls = ai.calls
    filter_articles({"AAPL": rows}, cfg, tmp_path, tmp_path / "scores.sqlite", scorer=ai)
    assert ai.calls == calls
    revised = [dict(rows[0], text="Apple earnings revised", content_hash="new")]
    filter_articles({"AAPL": revised}, cfg, tmp_path, tmp_path / "scores.sqlite", scorer=ai)
    assert ai.calls > calls
    assert all(a["score_is_probability"] is False for a in audit["AAPL"])


def test_counterparty_is_checked_as_separate_context_and_bad_score_fails(tmp_path):
    cfg = model()
    assert len(context_queries("AAPL", cfg)) > 2
    row = article("TSMC factory outage")
    row["relations"] = [{"kind": "supplier_event", "via_entity": "TSM"}]
    class SupplierAI:
        def score(self, query, texts):
            return np.array([3.0 if query.startswith("TSMC") else -10.0 for _ in texts])
    selected, audit, _ = filter_articles({"AAPL": [row]}, cfg, tmp_path, tmp_path / "scores.sqlite", scorer=SupplierAI())
    assert len(selected["AAPL"]) == 1
    assert audit["AAPL"][0]["best_context"] == "counterparty:TSM"
    with pytest.raises(ValueError):
        classify_score(float("nan"), cfg["ai"])
    with pytest.raises(ValueError):
        classify_score(1, dict(cfg["ai"], keep_min_score=5, related_min_score=0))


def test_relatedness_only_skips_encoder_and_normal_run_only_encodes_accepted(tmp_path, monkeypatch):
    from talg_py import news_relevance_ai, symbol_news_tensor
    cfg = json.loads((Path(__file__).parents[1] / "research/news-average/baseline-weighted-semantic-384.json").read_text())
    cfg["encoder"]["dimension"] = 2
    cfg.pop("representation", None)
    cfg["relatedness"] = {"model_file": "relatedness.json"}
    cfg["connection_weight"] = {"model_file": "connection-model.json"}
    (tmp_path / "connection-model.json").write_text((Path(__file__).parents[1] / "research/news-average/connection-model.json").read_text())
    (tmp_path / "model.json").write_text(json.dumps(cfg))
    (tmp_path / "relatedness.json").write_text(json.dumps(model()))
    store = Store(tmp_path / "work/news-tensor")
    for index, title in enumerate(["Apple earnings", "maybe industry news", "pie recipe"]):
        store.ingest({"collectedAt": utc(1000), "source": {}, "article": {"title": title,
            "summary": "", "url": f"https://example.test/{index}", "publishedAt": utc(1000)}}, 1000)
    store.db.commit()
    store.db.close()
    class LocalFakeAI(FakeAI):
        def __init__(self, *args):
            super().__init__()
    monkeypatch.setattr(news_relevance_ai, "RelevanceAI", LocalFakeAI)
    def fail_encoder(*args):
        raise AssertionError("Relatedness-only must not tensorize")
    monkeypatch.setattr(symbol_news_tensor, "Encoder", fail_encoder)
    summary = symbol_news_tensor.run(tmp_path, ["AAPL"], "model.json", relatedness_only=True)
    assert summary["symbols"]["AAPL"]["related_articles"] == 2
    assert summary["decay_experiment"] == "not_started"
    assert not (tmp_path / "work/symbol-news-tensor/pairs.sqlite").exists()
    seen = []
    class TensorEncoder:
        def __init__(self, *args):
            pass
        def encode(self, texts):
            seen.extend(texts)
            return np.ones((len(texts), 2), dtype=np.float32)
    monkeypatch.setattr(symbol_news_tensor, "Encoder", TensorEncoder)
    summary = symbol_news_tensor.run(tmp_path, ["AAPL"], "model.json")
    assert summary["symbols"]["AAPL"]["n"] == 2
    assert len(seen) == 2
    assert not any("pie recipe" in text for text in seen)
    folder = tmp_path / "work/symbol-news-tensor/runs" / summary["run_id"]
    assert (folder / "relatedness-model.json").exists()
    assert (folder / "connection-model.json").exists()
    assert len(json.loads((folder / "AAPL.relevance-decisions.json").read_text())) == 3
    with np.load(folder / "AAPL.npz", allow_pickle=False) as saved:
        selected = json.loads((folder / "AAPL.articles.json").read_text())
        for at, row in zip(saved["available_at"], selected, strict=True):
            assert at >= row["relevance_ai"]["evaluated_at"]
            assert at >= row["connection_weight"]["evaluated_at"]
        expected = np.einsum("n,nd->d", saved["connection_weights"] * saved["decay_weights"], saved["article_vectors"]) / int(saved["n"])
        np.testing.assert_allclose(saved["news_tensor"], expected)
        np.testing.assert_array_equal(saved["effective_weights"], saved["decay_weights"] * saved["connection_weights"])


def test_explicit_entity_retention_does_not_turn_provider_tags_into_proof(tmp_path):
    cfg = model()
    cfg["inclusion_policy"] = {"retain_explicit_company_reference": True, "retain_supported_counterparty_event": True}
    rows = [article("Apple settles an iPhone class action lawsuit"), article("A pie recipe"), article("TSMC production halts")]
    rows[0]["relations"] = [{"kind": "company_reference", "entity": "AAPL"}]
    rows[1]["relations"] = [{"kind": "direct_association", "association_kind": "ticker_feed"}]
    rows[2]["relations"] = [{"kind": "supplier_event", "via_entity": "TSM", "anchor_evidence": [{"kind": "company_reference", "entity": "TSM"}]}]
    class LowAI:
        def score(self, query, texts):
            return np.full(len(texts), -10.)
    selected, audit, _ = filter_articles({"AAPL": rows}, cfg, tmp_path, tmp_path / "scores.sqlite", scorer=LowAI())
    assert len(selected["AAPL"]) == 2
    assert [a["include"] for a in audit["AAPL"]] == [True, False, True]
    assert audit["AAPL"][0]["ai_state"] == "unrelated"
    assert audit["AAPL"][0]["state"] == "borderline"
    assert audit["AAPL"][0]["inclusion_reason"] == "explicit_company_evidence"
    assert audit["AAPL"][2]["inclusion_reason"] == "supported_counterparty_event"

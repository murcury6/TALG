import json
from pathlib import Path

import numpy as np
import pytest

from talg_py import symbol_news_tensor as news
from talg_py.news_tensor import Store, utc


def config():
    value = json.loads((Path(__file__).parents[1] / "research/news-average/baseline-weighted-semantic-384.json").read_text())
    value["encoder"]["dimension"] = 2
    value.pop("relatedness", None)
    value.pop("connection_weight", None)
    value.pop("representation", None)  # Legacy semantic-only cache coverage.
    return value


def record(title="Oil supply tightens", symbol="XOM", url="https://example.test/a", at=1000):
    return {"collectedAt": utc(at), "source": {"id": symbol, "symbol": symbol},
            "article": {"title": title, "summary": "Supply report", "url": url,
                        "publishedAt": utc(at)}}


class FakeEncoder:
    def __init__(self, *args):
        self.inputs = []

    def encode(self, texts):
        self.inputs.extend(texts)
        return np.array([[1, 0] if "symbol: XOM\n" in text else [0, 1] for text in texts], dtype=np.float32)


def test_exact_user_denominator_keeps_faded_articles_and_does_not_cancel_decay():
    decay = dict(config()["decay"], shock_fraction=1, shock_half_life_seconds=100)
    value, weights = news.count_average(np.eye(2), [100, 0], decay)
    np.testing.assert_allclose(weights, [.5, 1])
    np.testing.assert_allclose(value, [.25, .5])
    single, _ = news.count_average(np.array([[2., 4.]]), [200], decay)
    np.testing.assert_allclose(single, [.5, 1])
    old_plus_new, _ = news.count_average(np.eye(2), [1e9, 0], decay)
    np.testing.assert_allclose(old_plus_new, [0, .5])


def test_shock_curve_empty_set_future_clamp_and_invalid_parameters():
    decay = config()["decay"]
    weights = news.decay_weights([0, 300, 7200, 1e12, -100], decay)
    assert weights[0] == weights[-1] == 1
    assert 1 > weights[1] > weights[2] > weights[3] == 0
    value, _ = news.count_average(np.empty((0, 2)), [], decay)
    np.testing.assert_equal(value, [0, 0])
    for update in ({"shock_fraction": 2}, {"shock_half_life_seconds": 0}, {"background_half_life_seconds": float("nan")}):
        with pytest.raises(ValueError):
            news.decay_weights([1], dict(decay, **update))
    with pytest.raises(ValueError):
        news.count_average(np.array([[float("nan"), 0]]), [1], decay)


def test_related_only_no_repeated_link_votes_and_distinct_paraphrases_retained(tmp_path):
    store = Store(tmp_path)
    try:
        story = record()
        store.ingest(story, 1000)
        store.ingest(story, 1001)
        store.ingest(record(symbol="DAL"), 1000)
        store.ingest(record("Oil shortage grows", url="https://example.test/b"), 1000)
        store.ingest(record("Unrelated software update", symbol="MSFT", url="https://example.test/c"), 1000)
        identity = store.db.execute("SELECT id FROM information WHERE title='Oil supply tightens'").fetchone()[0]
        store.db.execute("INSERT INTO associations VALUES (?,?,?,?)", (identity, "XOM", "provider_symbol", "other source"))
        related = news.related_articles(store.db, "xom", config()["related_kinds"])
        assert len(related) == 2
        assert {a["title"] for a in related} == {"Oil supply tightens", "Oil shortage grows"}
        assert max(len(a["relations"]) for a in related) == 2
        assert news.related_articles(store.db, "ZZZZ", config()["related_kinds"]) == []
    finally:
        store.db.close()


def test_symbol_is_encoder_input_cache_preserves_revisions_and_decay_reuses_vectors(tmp_path):
    store = Store(tmp_path / "source")
    pairs = news.PairStore(tmp_path / "pairs.sqlite")
    encoder = FakeEncoder()
    cfg = config()
    try:
        store.ingest(record(), 1000)
        store.ingest(record(symbol="DAL"), 1000)
        xom = news.related_articles(store.db, "XOM", cfg["related_kinds"])
        dal = news.related_articles(store.db, "DAL", cfg["related_kinds"])
        a = pairs.encode("XOM", xom, encoder, cfg, clock=lambda: 1100)
        b = pairs.encode("DAL", dal, encoder, cfg, clock=lambda: 1101)
        assert a[2] != b[2]
        np.testing.assert_equal(a[0], [[1, 0]])
        np.testing.assert_equal(b[0], [[0, 1]])
        pairs.encode("XOM", xom, encoder, dict(cfg, decay={}), clock=lambda: 1200)
        cached = pairs.encode("XOM", xom, encoder, dict(cfg, connection_weight={"model_file": "another-revision.json"}), clock=lambda: 1201)
        assert cached[2] == a[2]
        assert len(encoder.inputs) == 2
        store.ingest(record("Oil supply restored", at=1200), 1200)
        revised = news.related_articles(store.db, "XOM", cfg["related_kinds"])
        c = pairs.encode("XOM", revised, encoder, cfg, clock=lambda: 1250)
        assert c[2] != a[2]
        assert pairs.db.execute("SELECT count(*) FROM pairs").fetchone()[0] == 3
        assert revised[0]["event_time"] == 1000
    finally:
        store.db.close()
        pairs.db.close()


def test_end_to_end_saved_provenance_one_tensor_and_source_unchanged(tmp_path, monkeypatch):
    source_folder = tmp_path / "work/news-tensor"
    store = Store(source_folder)
    store.ingest(record(), 1000)
    store.ingest(record(symbol="DAL"), 1000)
    store.db.commit()
    before = list(store.db.execute("SELECT * FROM information"))
    model = tmp_path / "model.json"
    model.write_text(json.dumps(config()))
    monkeypatch.setattr(news, "Encoder", FakeEncoder)
    try:
        summary = news.run(tmp_path, ["XOM", "DAL", "ZZZZ"], model)
        assert summary["decay_experiment"] == "not_started"
        folder = tmp_path / "work/symbol-news-tensor/runs" / summary["run_id"]
        for symbol in ("XOM", "DAL", "ZZZZ"):
            with np.load(folder / (symbol + ".npz"), allow_pickle=False) as saved:
                assert saved["news_tensor"].shape == (2,)
                assert int(saved["n"]) == (0 if symbol == "ZZZZ" else 1)
                if symbol != "ZZZZ":
                    assert saved["available_at"][0] > saved["event_times"][0]
                    assert saved["article_vectors"].shape == (1, 2)
        assert summary["symbols"]["ZZZZ"]["state"] == "no_related_articles"
        assert all(v["direction_prediction"] is None for v in summary["symbols"].values())
        assert [tuple(r) for r in store.db.execute("SELECT * FROM information")] == [tuple(r) for r in before]
        repeat = news.run(tmp_path, ["XOM"], model)
        assert repeat["run_id"] != summary["run_id"]
        assert (folder / "summary.json").exists()
    finally:
        store.db.close()

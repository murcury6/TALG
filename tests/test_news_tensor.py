import json

import numpy as np
import pytest

from talg_py.news_tensor import Store, aggregate, snapshot


def record(title="Oil supply tightens", symbol="XOM", url="https://example.com/1", seen=1000, published=900):
    from talg_py.news_tensor import utc
    return {"collectedAt": utc(seen), "source": {"id": "stock-" + symbol, "symbol": symbol},
            "article": {"title": title, "summary": "Supply disruption", "url": url,
                        "publisher": "Publisher", "publishedAt": utc(published)}}


def config():
    return {"encoder": {"dimension": 2}, "half_lives_seconds": [100, 200], "prior_mass": 1,
            "association_weights": {"ticker_feed": 1, "provider_symbol": 1},
            "ticker_rules": [], "effect_heads": {}}


class Encoder:
    def encode(self, texts):
        return np.array([[1, 0] if "Oil" in text else [0, 1] for text in texts], dtype=np.float32)


def test_half_life_and_fading_average_do_not_cancel_decay():
    first = aggregate(np.array([[1., 0.]]), np.array([1000]), np.array([1.]), 1000, [100], 1)
    later = aggregate(np.array([[1., 0.]]), np.array([1000]), np.array([1.]), 1100, [100], 1)
    assert later["mass"][0] == pytest.approx(first["mass"][0] / 2)
    np.testing.assert_allclose(later["weighted_average"], first["weighted_average"])
    np.testing.assert_allclose(later["decayed_average"], first["decayed_average"] / 2)
    assert later["fading_information"][0, 0] == pytest.approx(1 / 3)
    assert first["fading_information"][0, 0] == pytest.approx(1 / 2)


def test_new_story_changes_average_and_empty_groups_are_finite():
    result = aggregate(np.eye(2), np.array([900, 1000]), np.ones(2), 1000, [100], 1)
    np.testing.assert_allclose(result["weighted_average"], [[1/3, 2/3]])
    np.testing.assert_allclose(result["decayed_average"], [[.25, .5]])
    empty = aggregate(np.empty((0, 2)), np.array([]), np.array([]), 1000, [100], 1)
    np.testing.assert_allclose(empty["fading_information"], [[0, 0]])


def test_same_information_links_to_two_tickers_without_two_global_votes(tmp_path):
    store = Store(tmp_path)
    store.ingest(record(symbol="XOM"), 1000)
    store.ingest(record(symbol="DAL"), 1000)
    assert store.db.execute("SELECT count(*) FROM information").fetchone()[0] == 1
    assert store.db.execute("SELECT count(*) FROM associations").fetchone()[0] == 2
    store.encode_pending(Encoder(), "v1", 16)
    tensor, articles, effects = snapshot(store, config(), 1000, "v1")
    assert list(tensor["groups"]) == ["GLOBAL", "DAL", "XOM"]
    np.testing.assert_allclose(tensor["mass"][:, 0], [.5, .5, .5])
    assert articles["vectors"].shape == (1, 2)
    assert effects == {}


def test_repeated_poll_and_revision_keep_age_and_replace_vector(tmp_path):
    store = Store(tmp_path)
    item = record()
    assert store.ingest(item, 1000)
    assert not store.ingest(item, 1000)
    store.encode_pending(Encoder(), "v1", 16)
    store.ingest(record(title="Airline update", seen=1100, published=1100), 1100)
    row = store.db.execute("SELECT * FROM information").fetchone()
    assert row["event_time"] == 900
    assert row["vector"] is None
    store.encode_pending(Encoder(), "v1", 16)
    tensor, articles, _ = snapshot(store, config(), 1100, "v1")
    np.testing.assert_allclose(articles["vectors"], [[0, 1]])
    np.testing.assert_allclose(tensor["mass"][0], [.25, .5])


def test_identical_content_at_different_urls_is_one_information_item(tmp_path):
    store = Store(tmp_path)
    store.ingest(record(url="https://example.com/1?utm_source=rss"), 1000)
    store.ingest(record(url="https://syndicated.example.com/2"), 1000)
    assert store.db.execute("SELECT count(*) FROM information").fetchone()[0] == 1


def test_partial_live_record_and_restart_resume_without_loss(tmp_path):
    path = tmp_path / "data/news/2026-09-26/test.jsonl"
    path.parent.mkdir(parents=True)
    row = json.dumps(record())
    path.write_text(row[:20], encoding="utf-8")
    store = Store(tmp_path / "work")
    assert store.scan(tmp_path) == 0
    with path.open("a", encoding="utf-8") as file:
        file.write(row[20:] + "\n")
    assert store.scan(tmp_path) == 1
    store.db.close()
    restarted = Store(tmp_path / "work")
    assert restarted.scan(tmp_path) == 0
    assert restarted.db.execute("SELECT count(*) FROM information").fetchone()[0] == 1


def test_effect_is_separate_and_can_have_opposite_signs_without_changing_meaning(tmp_path):
    store = Store(tmp_path)
    store.ingest(record(), 1000)
    store.encode_pending(Encoder(), "v1", 16)
    cfg = config()
    cfg["ticker_rules"] = [{"contains_any": ["Oil"], "tickers": ["DAL"], "weight": .5}]
    cfg["effect_heads"] = {"XOM": {"weights": [1, 0], "model_version": "test-only"},
                           "DAL": {"weights": [-1, 0], "model_version": "test-only"}}
    _, articles, effects = snapshot(store, cfg, 1000, "v1")
    assert effects["XOM"]["scores"][0] > 0
    assert effects["DAL"]["scores"][0] < 0
    np.testing.assert_allclose(articles["vectors"], [[1, 0]])


def test_future_clock_does_not_increase_weight_and_invalid_config_fails(tmp_path):
    store = Store(tmp_path)
    store.ingest(record(published=2000), 1000)
    assert store.db.execute("SELECT event_time FROM information").fetchone()[0] == 1000
    with pytest.raises(ValueError):
        aggregate(np.eye(2), np.array([1, 1]), np.ones(2), 2, [0], 1)

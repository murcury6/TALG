import copy
import json
from pathlib import Path

import numpy as np

from talg_py import news_width_comparison as comparison
from talg_py.news_features import ArticleEncoder


def config():
    return json.loads((Path(__file__).parents[1] / "research/news-average/baseline-weighted-50000.json").read_text())


def test_only_lexical_width_changes_and_semantic_weights_decay_are_controlled():
    original = config()
    changed = comparison.wider_config(original, 100000)
    assert changed["representation"]["word_dimensions"] == 49342
    assert changed["representation"]["character_dimensions"] == 50274
    restored = copy.deepcopy(changed)
    restored["representation"] = original["representation"]
    assert restored == original
    assert original["representation"]["dimensions"] == 50000


def test_collision_measurements_ignore_repetition_and_cosines_match_geometry():
    features = comparison.unique_features("Oil oil oil")
    assert features[0] == {"w1:oil", "w2:oil\x1foil"}
    report = comparison.collisions([features[0], features[0]], 1)
    assert report["within_article_collisions"] == 2
    assert report["corpus_collisions"] == 1
    result = comparison.cosine_pairs(np.array([[1, 0], [0, 1], [1, 1]], dtype=np.float32), np.array([[0, 1], [0, 2]]))
    np.testing.assert_allclose(result, [0, 1 / np.sqrt(2)])


def test_comparison_retains_same_count_weights_time_and_original_input(tmp_path, monkeypatch):
    cfg = config()
    cfg["encoder"]["dimension"] = 2
    cfg["representation"].update(dimensions=16, word_dimensions=6, character_dimensions=8)
    articles = [{"id": "a", "text": "Apple reports revenue growth"}, {"id": "b", "text": "Apple reports weak iPhone demand"}]
    texts = [cfg["input_template"].format(symbol="AAPL", text=a["text"]) for a in articles]
    class Semantic:
        def encode(self, texts):
            return np.array([[1, 0], [0, 1]], dtype=np.float32)
    vectors = ArticleEncoder(Semantic(), cfg).encode(texts)
    weights, decay = np.array([.8, .4]), np.array([.5, .25])
    value = np.einsum("n,nd->d", weights * decay, vectors, dtype=np.float64, optimize=False) / 2
    source = tmp_path / "source"
    source.mkdir()
    (source / "model.json").write_text(json.dumps(cfg))
    (source / "AAPL.articles.json").write_text(json.dumps(articles))
    np.savez(source / "AAPL.npz", article_vectors=vectors, news_tensor=value, connection_weights=weights,
             decay_weights=decay, information_ids=np.array(["a", "b"]), event_times=np.array([10, 20]),
             available_at=np.array([30, 40]), as_of_utc=np.array("2026-09-26T00:00:00Z"))
    before = (source / "AAPL.npz").read_bytes()
    monkeypatch.setattr(comparison, "load_tensor", lambda *args: (value, {"file": str(source / "AAPL.npz"), "run_id": "frozen-test"}))
    folder, report = comparison.compare(tmp_path, "test-reference", "AAPL", 32)
    assert report["articles"] == 2 and report["source_run_id"] == "frozen-test"
    assert report["semantic_block_max_change"] < 1e-6
    with np.load(folder / "32.npz", allow_pickle=False) as wide:
        np.testing.assert_array_equal(wide["decay_weights"], decay)
        np.testing.assert_array_equal(wide["connection_weights"], weights)
        assert wide["article_vectors"].shape == (2, 32)
        assert wide["comparison_only"]
        assert np.all(wide["available_at"] > wide["source_available_at"])
    assert (source / "AAPL.npz").read_bytes() == before

import json
from pathlib import Path

import numpy as np
import pytest

from talg_py.news_features import ArticleEncoder, feature_layout, lexical_features
from talg_py.symbol_news_tensor import PairStore


def production_config():
    return json.loads((Path(__file__).parents[1] / "research/news-average/baseline-weighted-50000.json").read_text())


class FixedSemanticEncoder:
    def __init__(self, dimension=384):
        self.dimension = dimension

    def encode(self, texts):
        result = np.zeros((len(texts), self.dimension), dtype=np.float32)
        result[:, 0] = 1
        return result


def test_50000_layout_full_text_sensitivity_and_batch_stability():
    cfg = production_config()
    encoder = ArticleEncoder(FixedSemanticEncoder(), cfg)
    text = "Target stock symbol: AAPL\n" + "routine earnings report " * 200
    texts = [text + "revenue rises 50 percent", text + "revenue falls 50 percent"]
    actual = encoder.encode(texts)
    assert actual.shape == (2, 50000)
    assert np.isfinite(actual).all()
    np.testing.assert_allclose(np.linalg.norm(actual, axis=1), [1, 1], rtol=1e-6)
    np.testing.assert_equal(actual[0, :384], actual[1, :384])
    assert not np.array_equal(actual[0, 384:24960], actual[1, 384:24960])
    assert not np.array_equal(actual[0, 24960:], actual[1, 24960:])
    np.testing.assert_equal(encoder.encode(texts[:1])[0], actual[0])
    np.testing.assert_equal(encoder.encode(list(reversed(texts)))[1], actual[0])
    assert np.count_nonzero(actual[0, 384:]) > 0
    assert encoder.encode([]).shape == (0, 50000)
    blocks = feature_layout(cfg)["blocks"]
    assert [(b["start"], b["stop"]) for b in blocks] == [(0, 384), (384, 24960), (24960, 50000)]


def test_word_order_changes_bigrams_and_unicode_whitespace_is_stable():
    first = lexical_features("Company acquires rival", 24576, 25040)
    reverse = lexical_features("Rival acquires company", 24576, 25040)
    assert not np.array_equal(first[0], reverse[0])
    for a, b in zip(first, lexical_features("  COMPANY  acquires\nrival ", 24576, 25040)):
        np.testing.assert_equal(a, b)


def test_representation_version_prevents_reusing_small_vector_cache(tmp_path):
    cfg = production_config()
    legacy = dict(cfg)
    legacy.pop("representation")
    article = {"id": "a", "content_hash": "content", "text": "Target company earnings rise"}
    cache = PairStore(tmp_path / "pairs.sqlite")
    try:
        old = cache.encode("AAPL", [article], FixedSemanticEncoder(), legacy)
        expanded = cache.encode("AAPL", [article], ArticleEncoder(FixedSemanticEncoder(), cfg), cfg)
        assert old[3] != expanded[3]
        assert old[2] != expanded[2]
        assert old[0].shape == (1, 384)
        assert expanded[0].shape == (1, 50000)
        assert cache.db.execute("SELECT count(*) FROM pairs").fetchone()[0] == 2
        empty = cache.encode("MSFT", [], None, cfg)
        assert empty[0].shape == (0, 50000)
    finally:
        cache.db.close()


def test_bad_layout_is_rejected():
    cfg = production_config()
    cfg["representation"]["dimensions"] = 50001
    with pytest.raises(ValueError, match="dimension"):
        feature_layout(cfg)

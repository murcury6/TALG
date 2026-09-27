import json
from pathlib import Path

import numpy as np
import pytest
from test_model_profiles import pin
from test_news_relatedness import FakeAI

from talg_py import symbol_news_tensor
from talg_py.model_profiles import refresh_news, revision, runtime
from talg_py.model_workbench import news_data
from talg_py.news_connection import ConnectionModel
from talg_py.news_symbols import call
from talg_py.news_tensor import Store, utc
from talg_py.news_weighted_profile import load_tensor

ROOT = Path(__file__).parents[1]


def make_profile(root, bias=-.5):
    folder = ROOT / "research/news-average"
    cfg = json.loads((folder / "baseline-weighted-semantic-384.json").read_text())
    cfg.pop("representation", None)
    cfg["encoder"]["dimension"] = 2
    weights = json.loads((folder / "connection-model.json").read_text())
    weights["intercept"] = bias
    contents = {"config": json.dumps({"version": 1, "engine": "symbol_weighted_tensor_v1", "symbols": ["AAPL"], "pipeline": cfg}),
        "source": (folder / "weighted-profile.talg").read_text(),
        "relatedness": (folder / "relatedness.json").read_text(), "connection_weight": json.dumps(weights), "inputs": "{}"}
    return pin(root, "news", contents, name="Weighted")


def test_connected_run_symbols_data_and_pinned_dependencies(tmp_path, monkeypatch):
    from talg_py import news_relevance_ai
    class AI(FakeAI):
        def __init__(self, *args):
            super().__init__()
    class Encoder:
        calls = 0
        def __init__(self, *args):
            pass
        def encode(self, texts):
            Encoder.calls += len(texts)
            return np.array([[1, 0] for _ in texts], dtype=np.float32)
    monkeypatch.setattr(news_relevance_ai, "RelevanceAI", AI)
    monkeypatch.setattr(symbol_news_tensor, "Encoder", Encoder)
    store = Store(tmp_path / "work/news-tensor")
    import time
    at = time.time()
    for i, text in enumerate(["Apple earnings", "maybe smartphone industry", "pie recipe"]):
        store.ingest({"collectedAt": utc(at), "source": {}, "article": {"title": text, "summary": "", "url": f"https://example.test/{i}", "publishedAt": utc(at)}}, at)
    store.db.commit()
    store.db.close()
    first = make_profile(tmp_path)
    result = refresh_news(tmp_path, first)
    assert result["rated"] == 2 and result["errors"] == 0
    old = (runtime(tmp_path, first) / "symbol-ratings.json").read_bytes()
    data = news_data(tmp_path, reference=first)
    assert data["total"] == 2 and all(row["tensor_dimensions"] == 2 for row in data["rows"])
    assert all(row["weight"] > 0 for row in data["rows"])
    symbols = call(tmp_path, ["AAPL", "MSFT"], first)
    assert symbols["symbols"]["AAPL"]["outputs"]["dimensions"] == 2
    assert symbols["symbols"]["MSFT"]["error"]
    assert "rating" not in symbols["symbols"]["AAPL"]["outputs"]
    vector, provenance = load_tensor(tmp_path, first, "AAPL")
    assert vector.shape == (2,) and vector[0] > 0
    assert provenance["profile_revision"] == first
    # No dependency files exist under tmp_path: only frozen manifest content works.
    assert not (tmp_path / "research/news-average/connection-model.json").exists()
    second = make_profile(tmp_path, bias=2)
    refresh_news(tmp_path, second)
    assert Encoder.calls == 2  # Same article vectors reused across weight revisions.
    assert (runtime(tmp_path, first) / "symbol-ratings.json").read_bytes() == old
    assert first != second
    weights = ConnectionModel(json.loads(revision(tmp_path, second)["contents"]["connection_weight"]))
    assert weights.intercept == 2
    copied = json.loads((runtime(tmp_path, second) / "symbol-ratings.json").read_text())
    (runtime(tmp_path, first) / "symbol-ratings.json").write_text(json.dumps(copied))
    with pytest.raises(ValueError, match="another profile"):
        load_tensor(tmp_path, first, "AAPL")

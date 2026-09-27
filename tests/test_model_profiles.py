import hashlib
import json
import sqlite3
import time
import pytest
from talg_py.model_profiles import revision, revision_folder, runtime, refresh_news, active_news_revisions
from talg_py.news_tensor import Store, digest
from talg_py.stock_selection import evaluate
from talg_py.model_workbench import news_data
from test_news_tensor import config, record, Encoder


def pin(root, kind, contents, dependencies=None, name="Test"):
    value = {"kind": kind, "profile": name, "name": name, "contents": contents, "dependencies": dependencies or {}}
    raw = json.dumps(value).encode(); ref = hashlib.sha256(raw).hexdigest()
    folder = revision_folder(root, ref); folder.mkdir(parents=True, exist_ok=True)
    (folder / "manifest.json").write_bytes(raw)
    return ref


def news(root, score):
    source = f"output score = {score}\noutput tensor_weight = 1\noutput notify = True\ninput symbols = tickers\noutput notify_symbols = symbols\n"
    source += 'symbol\ninput items = articles\noutput rating = mean(column(items, "outputs.score")) if length(items) else 0\nend symbol\n'
    return pin(root, "news", {"source": source, "config": json.dumps(config()), "inputs": "{}"}, name=str(score))


def evidence(root):
    store = Store(root / "work/news-tensor")
    store.ingest(record(symbol="XOM"), 1000)
    version = digest(json.dumps(config()["encoder"], sort_keys=True))
    store.encode_pending(Encoder(), version, 100)
    return store, version


def test_profiles_have_separate_ratings_tensors_events_and_accept_new_evidence(tmp_path):
    store, version = evidence(tmp_path)
    first, second = news(tmp_path, 2), news(tmp_path, 8)
    refresh_news(tmp_path, first, now=1100); refresh_news(tmp_path, second, now=1100)
    def ratings(ref): return json.loads((runtime(tmp_path, ref) / "symbol-ratings.json").read_text())
    assert ratings(first)["symbols"]["XOM"]["outputs"]["rating"] == 2
    assert ratings(second)["symbols"]["XOM"]["outputs"]["rating"] == 8
    assert ratings(first)["profile_revision"] == first
    assert news_data(tmp_path, reference=first)["rows"][0]["outputs"]["score"] == 2
    assert news_data(tmp_path, reference=second)["rows"][0]["rating_state"] == "Rated"
    assert not store.db.execute("SELECT name FROM sqlite_master WHERE name='ratings'").fetchone()
    events = json.loads((runtime(tmp_path, first) / "news-events.json").read_text())
    assert events["latest"] == 1 and events["profile_revision"] == first
    store.ingest(record(url="https://example.com/2", title="Oil second story", symbol="XOM", seen=1200), 1200)
    store.encode_pending(Encoder(), version, 100)
    refresh_news(tmp_path, first, now=1201)
    assert ratings(first)["symbols"]["XOM"]["article_count"] == 2
    assert ratings(second)["symbols"]["XOM"]["article_count"] == 1
    events = json.loads((runtime(tmp_path, first) / "news-events.json").read_text())
    assert events["latest"] == 2  # Same immutable article/revision does not notify twice.
    with sqlite3.connect(runtime(tmp_path, first) / "information.sqlite") as db:
        assert db.execute("SELECT count(*) FROM rating_history").fetchone()[0] == 2
    store.db.close()


def test_stock_profile_reads_only_its_pinned_news_even_when_global_model_differs(tmp_path):
    store, _ = evidence(tmp_path)
    first, second = news(tmp_path, 2), news(tmp_path, 8)
    refresh_news(tmp_path, first); refresh_news(tmp_path, second)
    source = 'param candidates = ["XOM"]\ninput n = news\noutput include = field(n, "rating") > 5'
    stocks = pin(tmp_path, "stocks", {"source": source, "trading": "{}", "inputs": "{}"}, {"news": first})
    assert evaluate(tmp_path, source, stocks)["selected"] == []
    second_stocks = pin(tmp_path, "stocks", {"source": source, "trading": "{}", "inputs": "{}"}, {"news": second})
    assert evaluate(tmp_path, source, second_stocks)["selected"] == ["XOM"]
    path = runtime(tmp_path, first) / "symbol-ratings.json"
    path.write_text((runtime(tmp_path, second) / "symbol-ratings.json").read_text())
    with pytest.raises(ValueError, match="another profile"):
        evaluate(tmp_path, source, stocks)
    store.db.close()


def test_retains_armed_dependency_graph_and_detects_modified_manifests(tmp_path):
    first, second = news(tmp_path, 2), news(tmp_path, 8)
    stocks = pin(tmp_path, "stocks", {}, {"news": first})
    trade = pin(tmp_path, "trade", {}, {"news": second, "stocks": stocks})
    folder = tmp_path / "work/rapid-paper"; folder.mkdir(parents=True)
    (folder / "session.json").write_text(json.dumps({"phase":"WAITING_NEXT", "profileRevision":trade}))
    assert active_news_revisions(tmp_path) == sorted([first, second])
    (revision_folder(tmp_path, first) / "manifest.json").write_text("{}")
    with pytest.raises(ValueError, match="modified"): revision(tmp_path, first)
    with pytest.raises(ValueError, match="Invalid"): revision_folder(tmp_path, "../escape")

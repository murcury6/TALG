import json
import numpy as np
import pytest
from talg_py.model_language import Program
from talg_py.news_ratings import rate_items
from talg_py.news_tensor import Store, snapshot
from test_news_tensor import config, record, Encoder

def test_code_declares_parameters_inputs_and_structured_outputs():
    program = Program('''param threshold = 2
input x = source_count
input text = headline
output score = x / threshold
output label = "multiple sources" if x >= threshold else "single source"
output terms = ["energy", "supply"]
output metadata = {"matched": contains(text, "oil"), "count": x}
''')
    assert program.parameters == {"threshold": 2}
    result = program.run({"source_count": 4, "headline": "Oil supply"})
    assert result["score"] == 2
    assert result["label"] == "multiple sources"
    assert result["metadata"] == {"matched": True, "count": 4}

@pytest.mark.parametrize("expression", ["__import__('os')", "headline.__class__", "[x for x in embedding]", "open('secret')"])
def test_cannot_escape_model_language(expression):
    with pytest.raises(ValueError): Program("output score = " + expression)

def test_missing_custom_inputs_fail_instead_of_inventing_values():
    program = Program("input x = custom.measurement\noutput measured = x")
    with pytest.raises(KeyError): program.run({})
    assert program.run({"custom.measurement": 7}) == {"measured": 7}

def test_ratings_are_retained_and_control_tensor_weight(tmp_path):
    store = Store(tmp_path); store.ingest(record(), 1000); store.encode_pending(Encoder(), "test", 10)
    source = "input vector = embedding\noutput vector_copy = vector\noutput tensor_weight = 0"
    result = rate_items(store, config(), 1000, "test", source)
    assert result["rated"] == 1 and result["errors"] == 0
    retained = store.db.execute("SELECT outputs FROM ratings").fetchone()[0]
    assert json.loads(retained)["vector_copy"] == [1, 0]
    tensor, _, _ = snapshot(store, config(), 1000, "test")
    assert np.count_nonzero(tensor["decayed_average"]) == 0
    assert store.db.execute("SELECT count(*) FROM rating_history").fetchone()[0] == 1
    rate_items(store, config(), 1001, "test", source)
    assert store.db.execute("SELECT count(*) FROM rating_history").fetchone()[0] == 1
    rate_items(store, config(), 1002, "test", "output tensor_weight = 1 / 0")
    assert store.db.execute("SELECT error FROM ratings").fetchone()[0]
    store.db.close()


def test_model_owns_workbook_and_symbol_aggregation():
    source = '''output score = 1
symbol
input ticker = symbol
input items = articles
output rating = mean(column(items, "outputs.score")) if length(items) else 0
output ticker_name = ticker
end symbol
sheet
{"sheets":[{"name":"Evidence","rows":"/outputs/items","columns":[
{"id":"weight","path":"/weight","type":"number","format":"percent","precision":1}]}]}
end sheet
'''
    program = Program(source)
    assert program.run({}) == {"score": 1}
    assert program.workbook["sheets"][0]["rows"] == "/outputs/items"
    assert program.symbol.run({"symbol":"AAPL", "articles":[{"outputs":{"score":2}},{"outputs":{"score":4}}]}) == {"rating":3,"ticker_name":"AAPL"}
    assert program.symbol.run({"symbol":"MSFT", "articles":[]})["rating"] == 0

@pytest.mark.parametrize("schema", [
    {"sheets":[]},
    {"sheets":[{"name":"a","columns":[{"id":"x","path":"/x","typo":2}]}]},
    {"sheets":[{"name":"a","columns":[{"id":"x","path":"bad"}]}]},
    {"sheets":[{"name":"a","columns":[{"id":"x","path":"/x","source":"invalid"}]}]},
])
def test_workbook_rejects_unsupported_or_broken_contract(schema):
    with pytest.raises(ValueError): Program("output x = 1\nsheet\n" + json.dumps(schema) + "\nend sheet\n")

def test_symbol_lookup_is_scoped_and_retains_provenance(tmp_path):
    from talg_py.news_symbols import evaluate_symbols
    store = Store(tmp_path); store.ingest(record(), 1000); store.encode_pending(Encoder(), "test", 10)
    source = 'output score = 4\nsymbol\ninput items = articles\noutput rating = mean(column(items, "outputs.score")) if length(items) else 0\nend symbol\n'
    rate_items(store, config(), 1000, "test", source)
    ticker = store.db.execute("SELECT ticker FROM associations LIMIT 1").fetchone()[0]
    results = evaluate_symbols(store.db, source, [ticker, "MISSING"], now=1001)
    assert results["symbols"][ticker]["outputs"]["rating"] == 4
    assert results["symbols"]["MISSING"]["outputs"]["rating"] == 0
    assert results["symbols"][ticker]["article_ids"]
    assert results["model_version"] == Program(source).version
    # A changed model never mixes an old rating into its symbol aggregate.
    changed = evaluate_symbols(store.db, source.replace("score = 4", "score = 8"), [ticker], now=1002)
    assert changed["symbols"][ticker]["article_count"] == 0


def test_news_model_controls_event_importance_routing_and_deduplication(tmp_path):
    from talg_py.news_ratings import event_snapshot
    store = Store(tmp_path); store.ingest(record(), 1000); store.encode_pending(Encoder(), "test", 10)
    source = 'input targets = tickers\noutput score = 4\noutput notify = score > 3\noutput notify_symbols = targets\noutput notify_ttl_seconds = 60'
    rate_items(store, config(), 1000, "test", source)
    first = event_snapshot(store)
    assert len(first["events"]) == 1
    assert first["events"][0]["symbols"]
    rate_items(store, config(), 1001, "test", source)
    assert event_snapshot(store) == first
    silent = source.replace("score > 3", "score > 5")
    rate_items(store, config(), 1002, "test", silent)
    assert event_snapshot(store) == first
    # Bad routing is retained as an error, never emitted as an event.
    invalid = source.replace("notify_symbols = targets", 'notify_symbols = "AAPL"')
    result = rate_items(store, config(), 1003, "test", invalid)
    assert result["errors"] == 1
    assert event_snapshot(store) == first


def test_legacy_visual_hints_have_no_model_authority():
    source = 'output x = 2\nsheet\n' + json.dumps({"sheets":[{"name":"Metrics","rowHeight":100,"details":False,
        "sort":{"column":"x","direction":"desc"},"columns":[{"id":"x","path":"/outputs/x","type":"number",
        "label":"Giant","width":9999,"format":"percent","precision":1,"align":"center","missing":"invented"}]}]}) + '\nend sheet\n'
    program = Program(source)
    assert program.run({}) == {"x":2}
    assert program.workbook == {"sheets":[{"name":"Metrics","columns":[{"id":"x","path":"/outputs/x","type":"number"}]}]}

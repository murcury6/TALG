import json
from pathlib import Path
import pytest
from talg_py.stock_selection import compile_selection, evaluate


def test_code_filters_ranks_and_caps_candidates_with_retained_custom_inputs(tmp_path):
    model_dir = tmp_path / "work/models"
    model_dir.mkdir(parents=True)
    (model_dir / "stock-selection-inputs.json").write_text(json.dumps({"symbols": {
        "AAPL": {"score": 2}, "MSFT": {"score": 5}, "NVDA": {"score": -1}}}))
    source = '''param candidates = ["AAPL", "MSFT", "NVDA"]
param max_stocks = 1
input supplied = custom
output priority = field(supplied, "score")
output include = priority > 0
'''
    result = evaluate(tmp_path, source)
    assert result["selected"] == ["MSFT"]
    assert result["errors"] == 0
    assert result["rows"][1]["inputs"]["custom"]["score"] == 5
    assert result["source"] == source
    assert not (tmp_path / "work/strategies/rapid-paper.json").exists()


def test_missing_inputs_are_errors_not_invented_scores(tmp_path):
    result = evaluate(tmp_path, 'param candidates = ["AAPL"]\ninput n = news\noutput include = field(n, "missing") > 0')
    assert result["selected"] == []
    assert result["errors"] == 1
    assert result["rows"][0]["inputs"]["news_available"] is False


@pytest.mark.parametrize("source", [
    'param candidates = []\noutput include = True',
    'param candidates = ["AAPL", "AAPL"]\noutput include = True',
    'param candidates = ["AAPL"]\nparam max_stocks = 0\noutput include = True',
    'param candidates = ["AAPL"]\noutput something = True',
])
def test_invalid_selection_contract_fails(source):
    with pytest.raises(ValueError):
        compile_selection(source)


def test_include_must_be_boolean_and_priority_must_be_numeric(tmp_path):
    assert evaluate(tmp_path, 'param candidates = ["AAPL"]\noutput include = 1')["errors"] == 1
    assert evaluate(tmp_path, 'param candidates = ["AAPL"]\noutput include = True\noutput priority = "high"')["errors"] == 1


def test_starter_workbook_and_candidate_order(tmp_path):
    source = Path("src/main/resources/stock-selection.talg").read_text()
    result = evaluate(tmp_path, source)
    assert result["selected"] == ["AAPL", "MSFT"]
    assert result["workbook"]["sheets"][0]["name"] == "Selection"


def test_all_available_scans_catalogue_beyond_120_then_applies_model_limit(tmp_path):
    catalogue = tmp_path / "work/stocks/available-assets.json"
    catalogue.parent.mkdir(parents=True)
    assets = [{"symbol":"T" + str(i), "class":"us_equity", "status":"active", "tradable":True,"score":i} for i in range(350)]
    assets += [{"symbol":"BRK.B","class":"us_equity","status":"active","tradable":True,"score":999},
               {"symbol":"INACTIVE","class":"us_equity","status":"inactive","tradable":True,"score":10000},
               {"symbol":"BLOCKED","class":"us_equity","status":"active","tradable":False,"score":10000}]
    def write(): catalogue.write_text(json.dumps({"source":"alpaca", "scope":"active_tradable_us_equity", "fetched_at":"2026-09-26T12:00:00Z", "assets":assets}))
    write()
    source = 'param candidates = "all_available"\nparam max_stocks = 2\ninput a = asset\noutput include = True\noutput priority = field(a, "score")'
    result = evaluate(tmp_path,source)
    assert result["total"] == 351
    assert result["selected"] == ["BRK.B", "T349"]
    assert result["errors"] == 0
    assert result["available_catalogue"]["fetched_at"] == "2026-09-26T12:00:00Z"
    assets.append({"symbol":"NEW","class":"us_equity","status":"active","tradable":True,"score":9999}); write()
    updated = evaluate(tmp_path,source)
    assert updated["total"] == 352
    assert updated["selected"] == ["NEW", "BRK.B"]

@pytest.mark.parametrize("declaration", ['"all_available"', '"all available"', '"all"'])
def test_all_available_never_silently_substitutes_a_small_local_list(tmp_path,declaration):
    source = 'param candidates = ' + declaration + '\noutput include = True'
    compile_selection(source)
    with pytest.raises(ValueError,match="broker asset catalogue"):
        evaluate(tmp_path,source)

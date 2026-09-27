import hashlib

import numpy as np
import pytest

from talg_py.news_effect_data import load_example, outcomes
from talg_py.news_effects import EffectModel, chronological_split, make_network
from talg_py.news_encoder_v2 import token_windows
from talg_py.news_tensor import atomic_npz


def test_every_token_is_covered_once_and_company_context_repeated():
    prefix = [11, 12, 13]
    body = list(range(1500))
    windows = token_windows(prefix, body, 512, 101, 102)
    recovered = []
    for start, end, tokens in windows:
        assert len(tokens) <= 512 and tokens[:4] == [101, *prefix] and tokens[-1] == 102
        assert tokens[4:-1] == body[start:end]
        recovered.extend(tokens[4:-1])
    assert recovered == body
    assert token_windows(prefix, [], 512, 101, 102)[0][2] == [101, *prefix, 102]
    with pytest.raises(ValueError, match="insufficient"):
        token_windows(list(range(510)), body, 512, 101, 102)


def test_outcomes_start_after_snapshot_and_require_complete_sessions():
    prices = {"SPY": [("a", 10, 100), ("b", 20, 100), ("c", 30, 100), ("d", 40, 100)],
              "AAPL": [("a", 10, 100), ("b", 20, 110), ("c", 30, 121), ("d", 40, 100)]}
    cfg = {"horizons_sessions": [1, 2], "benchmark": "SPY"}
    result = outcomes({"symbol": "AAPL", "cutoff": 10}, prices, cfg)
    assert result["label_start"] == 20 and result["label_end"] == 40
    assert result["targets"][0] == pytest.approx(np.log(121 / 110))
    assert result["targets"][1] == 1
    assert result["targets"][4] == 0
    prices["AAPL"].pop(2)
    assert outcomes({"symbol": "AAPL", "cutoff": 10}, prices, cfg) is None


def test_chronological_split_purges_overlapping_labels_and_groups_days():
    rows = [{"symbol": symbol, "date": f"2020-{i//28+1:02d}-{i%28+1:02d}",
             "cutoff": i * 100, "label_end": i * 100 + 250} for i in range(30) for symbol in ["AAPL", "MSFT"]]
    groups = chronological_split(rows, {"train": 5, "validation": 2, "test": 2})
    assert max(r["label_end"] for r in groups["train"]) < min(r["cutoff"] for r in groups["validation"])
    assert max(r["label_end"] for r in groups["validation"]) < min(r["cutoff"] for r in groups["test"])
    assert not ({r["date"] for r in groups["train"]} & {r["date"] for r in groups["test"]})
    with pytest.raises(ValueError, match="Not enough"):
        chronological_split(rows, {"train": 60, "validation": 20, "test": 20})


def test_future_features_and_modified_files_are_rejected(tmp_path):
    path = tmp_path / "source.npz"
    atomic_npz(path, article_vectors=np.ones((2, 3)), effective_weights=np.array([.8, .1]),
               available_at=np.array([10., 30.]), n=np.array(2), symbol=np.array("AAPL"))
    row = {"tensor_file": str(path), "tensor_sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "cutoff": 20, "symbol": "AAPL"}
    with pytest.raises(ValueError, match="Future information"):
        load_example(row)
    row["cutoff"] = 30
    x, weights = load_example(row)
    assert x.shape == (2, 3) and weights[0] == pytest.approx(.8)
    row["tensor_sha256"] = "changed"
    with pytest.raises(ValueError, match="snapshot changed"):
        load_example(row)


def test_network_learns_through_user_average_and_numpy_inference_agrees():
    torch = pytest.importorskip("torch")
    torch.manual_seed(17)
    torch.set_num_threads(2)
    model = make_network(2, 8, 3)
    x = torch.tensor([[1., .2], [-.3, .8]])
    weights = torch.tensor([.8, .25])
    optimizer = torch.optim.Adam(model.parameters(), lr=.03)
    target = torch.tensor([.4, -.8, .2])
    initial = float(((model(x, weights) - target) ** 2).mean().detach())
    original = model.article.weight.detach().clone()
    for _ in range(80):
        optimizer.zero_grad()
        loss = ((model(x, weights) - target) ** 2).mean()
        loss.backward()
        optimizer.step()
    assert float(loss.detach()) < initial * .01
    assert not torch.equal(original, model.article.weight)
    effect = EffectModel.__new__(EffectModel)
    effect.metadata = {"input_dimensions": 2, "training": {"horizons_sessions": [1]}}
    effect.arrays = {"article_weight": model.article.weight.detach().numpy(), "article_bias": model.article.bias.detach().numpy(),
                     "head_weight": model.head.weight.detach().numpy(), "head_bias": model.head.bias.detach().numpy(),
                     "mean": np.zeros(3), "scale": np.ones(3)}
    features, aggregate, outputs = effect.predict(x.numpy(), weights.numpy())
    np.testing.assert_allclose(aggregate, (features * weights.numpy()[:, None]).sum(axis=0) / 2, atol=1e-7)
    expected = model(x, weights).detach().numpy()
    assert outputs[0]["expected_market_relative_log_return"] == pytest.approx(float(expected[0]), abs=1e-6)
    assert outputs[0]["positive_relative_return_probability"] == pytest.approx(float(torch.sigmoid(torch.tensor(expected[1]))), abs=1e-6)
    assert effect.predict(np.empty((0, 2)), np.empty(0)) == (None, None, [])


def test_fitting_exports_replayable_inert_weights_and_rejects_tampering(tmp_path):
    pytest.importorskip("torch")
    import hashlib
    import json

    from talg_py.news_effect_data import feature_contract
    from talg_py.news_effects import fit
    from talg_py.news_tensor import atomic_json
    pipeline = {"encoder": {"dimension": 2}, "denominator": "count", "effect_model": {}}
    contract = feature_contract(pipeline)
    rows = []
    for i in range(20):
        path = tmp_path / f"row-{i}.npz"
        x = np.array([[i / 20, .4], [-.2, .1]], dtype=np.float32)
        atomic_npz(path, article_vectors=x, effective_weights=np.array([.8, .3], dtype=np.float32),
                   available_at=np.array([100., 100.]), n=np.array(2), symbol=np.array("AAPL"))
        rows.append({"symbol": "AAPL", "cutoff": 1000. + 100 * i, "date": f"2020-01-{i + 1:02d}",
                     "label_start": 1001. + 100 * i, "label_end": 1010. + 100 * i,
                     "feature_contract": contract, "tensor_file": str(path),
                     "tensor_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                     "targets": [.01 * (i - 10), float(i > 10), .02 + i / 1000]})
    dataset = tmp_path / "dataset.json"
    atomic_json(dataset, {"feature_contract": contract, "rows": rows, "training": {
        "horizons_sessions": [1], "hidden_dimensions": 8, "seed": 17, "epochs": 2,
        "learning_rate": .01, "weight_decay": .0001, "minimum_dates": {"train": 3, "validation": 2, "test": 2}}})
    output = tmp_path / "model"
    info = fit(dataset, output)
    model_file = output / "model.json"
    pin = {"model_file": str(model_file), "model_sha256": hashlib.sha256(model_file.read_bytes()).hexdigest()}
    model = EffectModel(tmp_path, pin, pipeline)
    features, vector, forecasts = model.predict(np.array([[.2, .3]], dtype=np.float32), np.array([.5]))
    assert features.shape == (1, 8) and vector.shape == (8,)
    assert 0 < forecasts[0]["positive_relative_return_probability"] < 1
    assert info["split_counts"]["test"]["rows"] == 4
    assert info["test_evaluated"] is False and info["test_metrics"] == []
    model_file.write_text(json.dumps(dict(info, effect_dimensions=9)))
    with pytest.raises(ValueError, match="metadata checksum"):
        EffectModel(tmp_path, pin, pipeline)


def test_price_collector_uses_final_session_minute_and_early_close():
    from datetime import datetime, timezone

    import httpx

    from talg_py.news_effect_prices import fetch
    def handler(request):
        if request.url.path == "/v2/calendar":
            return httpx.Response(200, json=[{"date": "2020-11-27", "close": "13:00"}])
        assert request.url.params["timeframe"] == "1Min"
        assert request.url.params["adjustment"] == "split,dividend"
        return httpx.Response(200, json={"bars": [
            {"t": "2020-11-27T17:58:00Z", "c": 90},
            {"t": "2020-11-27T17:59:00Z", "c": 100},
            {"t": "2020-11-27T18:01:00Z", "c": 999}], "next_page_token": None})
    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        values = fetch(client, ["AAPL"], "2020-11-27", "2020-11-27", {}, now=datetime(2020, 11, 28, tzinfo=timezone.utc))
    assert len(values) == 1 and values[0]["adjusted_close"] == 100
    assert values[0]["close_time"] == "2020-11-27T18:00:00+00:00"

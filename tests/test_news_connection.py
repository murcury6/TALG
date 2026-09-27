import copy
import json
from pathlib import Path

import numpy as np
import pytest

from talg_py.news_connection import FEATURES, ConnectionModel, fit_model
from talg_py.symbol_news_tensor import count_average


def config():
    return json.loads((Path(__file__).parents[1] / "research/news-average/connection-model.json").read_text())


def article(score=3, kinds=("company_reference",)):
    return {"id": "story", "content_hash": "revision", "relations": [{"kind": k} for k in kinds],
            "relevance_ai": {"score": score, "model_version": "retriever-revision"}}


def test_connection_model_is_bounded_auditable_and_repeated_links_do_not_inflate():
    model = ConnectionModel(config())
    first = model.predict("AAPL", article())
    repeated = model.predict("AAPL", article(kinds=("company_reference", "company_reference", "direct_association")))
    assert 0 < first["value"] < 1
    assert first["value"] == repeated["value"]
    assert list(first["features"]) == FEATURES
    assert first["is_probability"] is False
    assert first["status"] == "provisional_hand_set_coefficients_not_fitted"
    assert model.predict("AAPL", article(-3))["value"] < first["value"]
    changed = copy.deepcopy(config())
    changed["coefficients"][1] += 1
    assert ConnectionModel(changed).version != model.version
    with pytest.raises(ValueError):
        model.predict("AAPL", dict(article(), relevance_ai=None))
    with pytest.raises(ValueError):
        ConnectionModel(dict(config(), coefficients=[float("nan")] * len(FEATURES)))


def test_exact_weighted_formula_zero_weight_still_counts_and_original_vectors_unchanged():
    decay = {"kind": "shock_plus_background", "shock_fraction": 1,
             "shock_half_life_seconds": 100, "background_half_life_seconds": 1000}
    vectors = np.array([[2., 0], [0, 4.]])
    before = vectors.copy()
    result, factors = count_average(vectors, [100, 0], decay, [.8, .25])
    np.testing.assert_allclose(result, [.4, .5])
    np.testing.assert_equal(factors, [.5, 1])
    zero, _ = count_average(vectors, [0, 0], decay, [1, 0])
    np.testing.assert_equal(zero, [1, 0])
    np.testing.assert_equal(vectors, before)
    for invalid in ([.5], [-.1, 1], [1.1, 1], [float("nan"), 1]):
        with pytest.raises(ValueError):
            count_average(vectors, [0, 0], decay, invalid)


def test_supervised_fit_uses_connection_labels_and_does_not_mutate_saved_priors():
    cfg = config()
    before = copy.deepcopy(cfg)
    model = ConnectionModel(cfg)
    records = []
    for i, score in enumerate([-10, -5, 0, 5, 10]):
        row = model.predict("AAPL", article(score, kinds=()))
        records.append(dict(row, feature_contract=cfg["feature_contract"], target=(score + 10) / 20,
                            information_id=str(i), label_kind="human_connection_strength", labeler="synthetic-unit-test"))
    fitted = fit_model(cfg, records)
    later = ConnectionModel(fitted)
    targets = np.array([r["target"] for r in records])
    initial = np.array([model.predict("AAPL", article(s, ()))['value'] for s in [-10, -5, 0, 5, 10]])
    final = np.array([later.predict("AAPL", article(s, ()))['value'] for s in [-10, -5, 0, 5, 10]])
    assert np.mean((final - targets)**2) < np.mean((initial - targets)**2)
    assert cfg == before
    assert fitted["training"]["validation"] == "not_performed"
    assert fitted["training"]["decay_experiment"] == "not_started"
    with pytest.raises(ValueError, match="Duplicate"):
        fit_model(cfg, records + records[:1])
    with pytest.raises(ValueError):
        fit_model(cfg, [dict(records[0], target=2)])
    with pytest.raises(ValueError):
        fit_model(cfg, [dict(records[0], label_kind="future_return")])

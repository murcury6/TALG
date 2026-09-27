# Independent connection-weight model

User extension, 26 September 2026: assign each symbol/story pair an estimated connection strength through a separate model. The original count denominator is retained while clarification about the abbreviated new formula is pending.

```text
x_si = original 50,000-feature symbol/article vector
w_si = connection_model(symbol s, story i, recorded relationship evidence)
d(age) = 1 / mu(age)
X_s(t) = sum_i[x_si * w_si * d(t - t_i)] / n_s
```

The relevance filter decides which stories enter. The connection model estimates their strength in [0,1]. The decay function models age. These are distinct model components. A weight is neither bullish/bearish direction nor the expected size of a price move nor a calibrated probability. The weight is a scalar applied to all coordinates of an article vector, not an extra embedding coordinate. One story can have different weights for different symbols.

An included story with zero weight still counts in n under the original denominator. The average is not divided by sum(w), sum(decay), or sum(w * decay), and is not re-normalized. Thus changes in connection strength and age remain visible in the output magnitude. The archive-size dilution hypothesis still applies. Neither weighting nor decay guarantees that mixtures of contradictory news retain the information needed for prediction.

## Inspectable initial model

`connection-model.json` declares a small logistic regression with five features:

1. Best small-AI retrieval score, divided by 10 and clipped to [-2,2].
2. Direct company-reference or recorded-association flag.
3. Supported supplier/customer relation flag.
4. Sector relation flag.
5. Macro relation flag.

```text
w = sigmoid(intercept + sum_j(coefficient_j * feature_j))
sigmoid(z) = 1 / (1 + exp(-z))
```

Repeated relation evidence cannot increase a binary flag. No future return, story age or decay parameter is an input. The initial coefficients are **hand-set engineering priors, not a fitted or validated financial model**. The sigmoid bounds the output; it does not magically calibrate the AI score or establish economic influence. The small AI supplies one feature; its raw score is not directly used as a weight. The macro feature exists for future fitted models but universal macro inclusion is not yet implemented.

The model is independently replaceable. Its full content hash, parameters, per-story feature values, output weight and evaluation time are saved. Each run freezes `connection-model.json` alongside the relevance and tensor configurations. Existing named News/trading profiles and pinned dependencies are not changed. Changing connection coefficients reuses cached article vectors; it produces a different aggregate/configuration revision. The original unweighted comparison remains runnable with `baseline-unweighted-50000.json`.

Each NPZ now retains `article_vectors`, `connection_weights`, `decay_weights`, `effective_weights` (their product), the resulting `news_tensor`, n and the connection model version. Article JSON contains the weight's inputs and provenance. Availability includes connection evaluation time. Legacy configurations without a connection model use unit weights.

## Fitting the separate model

The fitting implementation exists in `talg_py/news_connection.py`. Actual financial fitting has not been performed: no independently labeled connection-strength dataset has been supplied. To train it, copy archived per-story connection outputs into a JSON array and add these fields to each record:

- `target`: a human-assessed connection strength from 0 to 1, using a written labeling rubric.
- `label_kind`: `human_connection_strength`.
- `labeler`: who assigned the judgment.
- `feature_contract`: the exact object from the saved connection configuration.

Preserve `symbol`, `information_id`, `content_hash`, `relevance_model_version` and the complete named `features` object. Reconcile duplicate judgments before fitting. Example training command, only after real labels are available:

```powershell
& .\work\news-tensor\venv\Scripts\python.exe -m talg_py.news_connection --model research/news-average/connection-model.json --labels path/to/labeled-connections.json --output research/news-average/connection-model-fitted-v1.json
```

The command fits regularized soft-label logistic regression, saves dataset hash and fitting settings, and refuses to overwrite an existing model file. It does not switch the active research config, retarget named profiles, optimize decay, or claim independent validation. Training loss is explicitly a training statistic.

Human labels themselves are subjective. Define the rubric before collecting them, measure disagreements and keep events/dates separate between training and evaluation. A model trained to match connection judgments is not thereby validated for return prediction. Synthetic fitting tests only verify that the fitting code learns a known toy relationship.

## Engineering verification

27 targeted automated tests pass. An isolated synthetic integration run using the actual downloaded relevance AI and actual 50,000-feature encoder retained direct earnings, a supplier outage and sector demand news, and rejected a pie recipe. Its saved aggregate reconstructed exactly from the original vectors, connection weights and decay weights (maximum absolute error 0). Output: `work/symbol-news-tensor/weighted-integration-check/work/symbol-news-tensor/runs/20260926T221744Z-f42a7674/`. This is a correctness check, not real-news accuracy or predictive evaluation.

The separate model is now consumed by the desktop **Weighted news 50k** profile. See [CONNECTED_PROFILE.md](CONNECTED_PROFILE.md) for Run, inspection, pinned dependency files and downstream access.

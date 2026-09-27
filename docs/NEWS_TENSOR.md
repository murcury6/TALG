# Independent news information and ticker effects

The separate [symbol-related count-average research pipeline](../research/news-average/README.md) implements the subsequently requested `symbol + related article -> vector -> decay -> sum / article count` construction, with one aggregate per symbol. It now includes the separate connection weight and is connected through the **Weighted news 50k** named News profile. [Run and output contract](../research/news-average/CONNECTED_PROFILE.md) documents the integration. It retains its own vectors and snapshots without retargeting existing consumers. Its assumptions, collected research papers and explicitly deferred decay study live under `research/news-average/`.

News is represented once, independently of holdings or ticker assignment. The information layer describes meaning; a separate relevance layer determines where it can be applied; a separate effect model can estimate a ticker-specific directional effect. These are not interchangeable.

The worker is `python -m talg_py.news_tensor --root F:\TALG`. It reads the existing append-only news records under `data/news` and `data/news-stream`, without restarting or controlling the desktop or trading runner. It scans for new records approximately every second, embeds pending stories in small batches, and exports decayed views every two seconds. Processing and disk latency can extend those intervals. New observations take priority over the initial historical backlog.

## Meaning

Each unique information item has a 384-dimensional vector produced locally by the multilingual MiniLM encoder. The encoder input is the headline followed by the publisher's supplied summary, with a maximum of 128 tokens, attention-mask mean pooling, and L2 normalization. Longer summaries are truncated for this first model version. Original text remains in the database and collection archive. Full articles are not inferred or fetched by this worker. Model revision and encoder settings are pinned and recorded.

The information tensor has axes `[information item, semantic feature]`. Dimensions are latent semantic features, not named financial variables, sentiment probabilities, or expected returns. The model supports multilingual semantic representation, but language coverage and quality are not universal. Source: [Sentence Transformers model card](https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2); [ONNX export](https://huggingface.co/Xenova/paraphrase-multilingual-MiniLM-L12-v2).

Exact canonical-URL duplicates and exact normalized headline-plus-summary duplicates share an information item. Revisions replace its current vector and keep the original decay origin; observations remain in the ledger. Paraphrased syndicated stories are not yet clustered, so some coverage can still be counted more than once. Repeated polls do not rejuvenate old news.

## Separate relevance

The `associations` table holds information ID, ticker, association type, and evidence. Provider-supplied symbol tags and ticker-specific RSS discovery are distinct association types. Neither is treated as an independently verified causal effect. Multiple feeds linking the same information to the same ticker use the maximum configured relevance weight rather than adding duplicate votes.

`work/models/news-tensor.json` controls association weights and optional `ticker_rules`. For example, a rule can link information containing specific sector terms to a separately supplied list of tickers. No broad sector mappings are invented by default. The raw information vector stays the same when the relevance mapping changes. Information with no ticker association contributes to GLOBAL and remains available for later mappings.

An optional rule has the form `{"contains_any":["term"],"tickers":["SYMBOL"],"weight":0.5}`. The weight is a user-defined relevance coefficient, not a calibrated probability. Rules are declarative substring matches over stored text and do not execute arbitrary code.

## Decay and aggregation

For each information vector `x_i`, event time `t_i`, ticker relevance `r_i`, and half-life `h`:

```text
w_i(t,h) = r_i * 2 ** (-max(0, t - t_i) / h)
S(t,h)   = sum_i w_i(t,h) * x_i
M(t,h)   = sum_i w_i(t,h)
R        = sum_i r_i                   (does not decay with time)
decayed_average = S / R                (the default; zero when R is zero)
weighted_average = S / M               (zero when mass is negligible)
fading   = S / (M + prior_mass)
```

Defaults are half-lives of 300, 1800, 7200, and 86400 seconds, with prior mass 1. These are adjustable research settings, not fitted decay estimates. Publication time is used where supplied and parseable; otherwise first observation is used. Future publisher clocks are clamped to observation/current time. The availability timestamp is when encoding actually completed: a historical publication date must never be used as proof that this system had the vector then.

A normalized mean alone does not weaken when all news becomes old because its numerator and denominator decay together. The default `decayed_average` averages the decayed vectors using the time-independent relevance mass R, so its value halves after a half-life when the information set is unchanged. The `mass` and optional prior-shrunk `fading_information` arrays are also retained. The default denominator includes all encoded information in each group; it grows as distinct information is added. Time advances even when no stories arrive.

The derived tensor axes are `[GLOBAL or ticker, half-life, semantic feature]`. GLOBAL gives each unique information item one vote. Ticker views use the separate relevance mapping. The underlying information tensor is not modified by these projections.

## Ticker-specific effect model

`effect_heads` is deliberately empty until a model is fitted or explicitly specified. Consequently `effects.json` reports `no_fitted_effect_heads`, not a zero-effect prediction. Meaning alone is not evidence of a price direction.

The supported effect head is an inspectable linear map per ticker with 384 `weights`, a `bias`, and a `model_version`. It operates on information independently of the relevance/decay step: the equivalent aggregate is `(S @ weights + bias * M) / R`. Two tickers may therefore receive opposite effects from identical information without changing the shared information vector. A test verifies this mechanism with synthetic coefficients; those coefficients are not installed in the working model. A fitted head must separately document its target, score units, training cutoff, and out-of-sample validation. This worker does not train heads or connect them to order submission.

## Files and operation

- `work/models/news-tensor.json`: editable encoder, half-lives, relevance and effect-head settings. Formula strings document the supported Python implementation; they are not an arbitrary expression interpreter. Numeric decay/relevance settings and effect heads reload between snapshots. Changing the encoder requires a worker restart and re-encoding; different encoders never mix in an aggregate.
- `work/news-tensor/information.sqlite`: independent information, vectors, source observations, relevance links and archive cursors. SQLite WAL and OS process locking support restart recovery.
- `work/news-tensor/information.npz`: information IDs, vectors, event times, encoding availability and model version; exported about every 60 seconds during backfill, then with current snapshots.
- `work/news-tensor/current.npz`: current global/ticker `decayed_average`, `weighted_sum`, `weighted_average`, `mass`, `relevance_mass`, and `fading_information`, plus labeled axes and timestamp. Read with `numpy.load(..., allow_pickle=False)`.
- `work/news-tensor/effects.json`: optional fitted-head effects, or an explicit untrained state.
- `work/news-tensor/status.json`: worker PID, state, encoded/pending counts, dimensions, and configuration version.
- `work/news-tensor/encoder.json`: encoder identity and feature settings.
- `work/news-tensor/worker-error.log`: runtime errors.

The worker is independent of the news collector and does not auto-start after a Windows reboot. `tools/setup-news-tensor.ps1` creates its isolated environment and downloads the pinned model with a SHA-256 check. Use `tools/start-news-tensor.ps1` to start it; an OS lock prevents two tensor workers sharing this store. The two live files are atomic snapshots individually; consumers should use each file's own timestamp rather than assuming separate files were published in one transaction. Original news archives and SQL observations are retained. Current tensors show the latest model state and are not an as-of historical backtest dataset.

```python
import numpy as np
tensor = np.load('work/news-tensor/current.npz', allow_pickle=False)
groups = tensor['groups'].tolist()
aapl = tensor['decayed_average'][groups.index('AAPL')]
# aapl: four time scales by 384 meaning features, not four return predictions.
```

Offline tests cover half-life math, vanishing evidence, new-story weighting, deduplication, separate ticker links, revisions, future clocks, partial live writes, restart cursors, and opposite ticker effect heads without changing information.


## Code-defined ratings and display

The executable rating layer and Models → News workspace are described in [Model language](MODEL_LANGUAGE.md). The model declares configurable inputs and named outputs; the UI renders them. The optional tensor_weight output explicitly scales each item’s aggregate contribution. Rating provenance and vectors used are retained in the information database.

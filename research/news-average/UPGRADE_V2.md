# Learned news v2 upgrade

Implemented 27 September 2026 UTC. The selected News profile is **Learned news v2**, ID `learned-news-v2`. This upgrades news representation and adds a working supervised effect-training/inference path. It does not establish financial forecasting skill.

## What runs now

- BGE-large-en-v1.5: 24 transformer layers, 1,024 learned semantic coordinates, replacing the former 384-coordinate MiniLM baseline. Public ONNX artifacts are pinned by repository revision and verified SHA-256 checksums. Inference runs locally on CPU.
- Complete supplied text: every retained headline/summary token is processed in nonoverlapping windows of at most 512 tokens, repeating target-symbol/company/sector context in each window. Original chunk vectors, token ranges, content hash and coverage counts are retained in the versioned pair cache. Token-count-weighted chunk pooling produces one normalized article vector. No engineered word/character hash features are used.
- The user's existing formula is unchanged: `sum(article_vector * connection_weight * decay) / article_count`. Distinct related stories remain distinct contributions. The separate connection model and illustrative decay retain their existing settings.
- Profile Run publishes the tensor, coverage evidence, effect readiness and a prospective training-dataset index. Data shows encoded token and chunk counts. Symbol lookup exposes effect status and, only when a fitted artifact is pinned, named research forecasts and its learned effect tensor.

The encoder is English-focused, general-purpose pretrained language representation. It is not financially fine-tuned. Full coverage means the text actually supplied by the collector; it does not mean a publisher's full article was fetched. The earlier multilingual encoder remains available through saved profiles. Longer earnings releases can use the same chunk encoder when they are ingested; automatic filings/transcript intake is still separate work.

Model sources: https://huggingface.co/BAAI/bge-large-en-v1.5 and https://huggingface.co/Xenova/bge-large-en-v1.5/tree/dfeef6070b90658e1b391a6940efdb0925c1de6f . The CLS pooling follows the model's documented representation; token-weighted pooling across windows is our explicit, unvalidated aggregation choice.

## Financial-effect network

The implemented trainable architecture is:

    z_si = tanh(W_article @ semantic_si + b_article)
    Z_s(t) = sum_i(z_si * connection_si * decay_i) / n_s
    outputs = W_head @ Z_s(t) + b_head

Both the article transform and outcome heads are optimized through that exact average. The pretrained BGE encoder stays frozen in this first implementation. Default effect width is 4,096; the fitting CLI accepts other widths, including 50,000, without padding or text hashes. These coordinates become learned financial features only after supervised fitting; no untrained expansion is published as financial information.

Three heads per horizon estimate market-relative log return, positive-relative-return probability, and relative daily variation. Provisional horizons are 1, 5 and 20 trading sessions against SPY. The variation label is `sqrt(sum(daily relative log return squared))`; it is not annualized volatility. Named business mechanisms such as margins or demand are not fabricated from these heads and would require appropriate targets.

Targets begin at the first regular-session closing reference strictly after the news snapshot is available, and finish h subsequent sessions later. Thus they do not include the move from snapshot time to that first close. The price collector uses the final regular-session minute, handles early closes through the market calendar, and requests split/dividend adjustments. Missing closing bars or benchmark sessions leave a sample pending. IEX is venue-limited; its bar close is not an official consolidated closing-auction price. API documentation: https://docs.alpaca.markets/us/reference/stockbars .

## Current state and evidence

Profile revision: `1ecf2be26dda0ade700b81648b90f2ea7b2e608ccc3da912b3c9eac3edf0e4c8`.

Run: `20260927T010136Z-9d6b6ec3`. Retained AAPL articles: **405**. Dimensions: **1,024**. Article tokens encoded: **24,122**. Maximum supplied article length: **406 tokens**. Truncated tokens: **0**.

All 405 article chunk-vector records were checked against their expected storage sizes; no lexical blocks enter the selected output. The exact aggregate reconstruction error was zero. A separate real-encoder test encoded all 770 article tokens across two windows, and changing the final sentence changed the embedding. This verifies coverage/sensitivity, not financial accuracy.

There is currently **one prospective observation and zero matured labeled examples** for this new profile. The current state is `awaiting_outcome_labels`; forecasts and effect tensors are absent. Fitting is deliberately rejected until sufficient distinct dates and chronological splits exist. The default minimums after purging are 60 training, 20 validation and 20 test dates. These are operational minimums, not proof that the sample is statistically sufficient. The available news archive starts only a few days ago; it cannot be represented as years of historical evidence.

Tests cover the original aggregation and profile contracts, token coverage, closing-time labels, leakage checks, actual gradient learning through the average, saved-weight inference parity, and tamper rejection. Synthetic training fixtures validate the software only; they do not train the selected live research profile.

## Commands

From `F:/TALG`, use `work/news-tensor/venv/Scripts/python.exe`.

Install the pinned encoder if setting up another checkout:

    python tools/setup-news-encoder-v2.py

The separate CPU training dependency is pinned in `tools/news-effect-requirements.txt`:

    python -m pip install -r tools/news-effect-requirements.txt --index-url https://download.pytorch.org/whl/cpu

Run the selected profile through desktop Run, or:

    python -m talg_py.news_weighted_profile run --root F:/TALG --revision 1ecf2be26dda0ade700b81648b90f2ea7b2e608ccc3da912b3c9eac3edf0e4c8

Collect adjusted session-close labels when outcomes have occurred, using the existing Alpaca environment credentials. This command only reads calendar and market-data endpoints:

    python -m talg_py.news_effect_prices --symbol AAPL --benchmark SPY --start YYYY-MM-DD --end YYYY-MM-DD --out work/news-effect-labels/adjusted-closes.json

Profile Run rebuilds its dataset index automatically. It can also be rebuilt without running inference:

    python -m talg_py.news_effect_data --revision 1ecf2be26dda0ade700b81648b90f2ea7b2e608ccc3da912b3c9eac3edf0e4c8 --prices work/news-effect-labels/adjusted-closes.json --out work/news-effect-labels/dataset.json

Fit candidate widths on identical observations and chronological splits:

    python -m talg_py.news_effects --dataset work/news-effect-labels/dataset.json --width 4096 --out work/news-effects/candidate-4096
    python -m talg_py.news_effects --dataset work/news-effect-labels/dataset.json --width 50000 --out work/news-effects/candidate-50000

Each output folder must be new. Select settings using validation results. Test metrics remain withheld by default; use `--evaluate-test` only after freezing the selected configuration. Repeated model selection against that test would invalidate its untouched status. Training output saves inert NPZ weights, metadata/checksums, exact dataset bytes, split provenance and validation history. Label price snapshots are archived by content hash. Constant-return and base-rate baselines are implemented; price-only incremental skill, calibration, regime stability and execution costs remain further evaluation work.

To use a fitted candidate, set the saved News profile's `pipeline.effect_model.artifact` to an object with `model_file` (path to its model.json) and `model_sha256` (SHA-256 of that file), then Run to save a new immutable profile revision. Inference verifies metadata, weights and the exact input/weight/decay contract. This is a research candidate, not a validated trading model. Existing consuming profiles remain pinned until explicitly relinked.

The desktop package has been rebuilt; restart an already-running desktop process to load the updated status display. Saved baselines and historical 50k/100k experiments are preserved. No order routing, stock-selection dependencies, armed sessions, scheduler or deferred decay study was changed.

## Files

- `talg_py/news_encoder_v2.py`: checked model loading and complete supplied-text encoding.
- `talg_py/news_effect_data.py`: snapshot eligibility and outcome labels.
- `talg_py/news_effect_prices.py`: read-only adjusted regular-session price collection.
- `talg_py/news_effects.py`: trainable transform, purged splits, held-out evaluation and pinned inference.
- `research/news-average/model.json`: selected template.
- `research/news-average/effect-training.json`: readable initial training specification; each saved profile owns its own captured copy under pipeline.effect_model.training.
- `work/news-encoder-v2/profile-verification.json`: real-run verification.

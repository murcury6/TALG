# Connected weighted News profile

The new desktop profile is **Learned news v2**, ID `learned-news-v2`. It is selected for News. Existing Trade and Stocks dependency links and armed sessions retain their original revisions.

## Use in the desktop

Open the updated desktop build, go to Models > News, and choose Learned news v2.

- Model: editable symbol-output expressions. The starter exposes the saved tensor reference, dimensions, article count, information norm and mean connection weight. These are information measurements, not a directional rating.
- Settings: `symbols` controls the stocks to process. Initial configuration is AAPL so that the first full run is bounded. The nested `pipeline` shows the exact count denominator, decay and 1,024-coordinate semantic representation and effect-training configuration.
- Run: ingests new retained collector files when the shared archive lock is available, evaluates relevance, calculates independent connection weights, encodes missing symbol/story pairs, applies decay and publishes the result. If the shared worker owns ingestion, it uses its committed archive snapshot. Run works on retained collection data; it does not itself poll remote news feeds.
- Data: accepted symbol/story pairs with headline, connection weight, relevance state and model dimensions. Selecting a row retains detailed model inputs and relationship evidence.
- More > Symbol lookup: reads the published output for that symbol without rerunning inference or silently changing its timestamp.
- More > Status: shows the profile, configured symbols, formula, dependency-file locations and processing result.

The Java application was rebuilt. An already-open Java process must be reopened to load the updated desktop classes. Processing and cached outputs are file-backed and survive that restart.

## Independent model files and pinned revisions

Editable files are under `work/model-profiles/news/learned-news-v2/`:

- `config.json`: selected symbols and the tensor/decay pipeline.
- `source.talg`: declared symbol outputs.
- `relatedness.json`: small-AI settings and company/sector/supply-chain context.
- `connection_weight.json`: the separate connection model's coefficients and training provenance.

Every profile snapshot captures all these files as immutable manifest content. Runtime dependency reads use those captured bytes; they do not follow the mutable research-folder paths mentioned in the original pipeline config. Edit the profile's dependency files to change this model; the next Run publishes a new revision. Existing consumers keep their old pinned revision until explicitly relinked. Editing a research template does not silently change this saved profile.

Article vectors and AI scores use versioned shared caches. Changing connection coefficients reuses compatible vectors. Each profile revision has its own outputs under `work/model-profiles/revisions/<revision>/runtime/weighted-news/runs/<run>/`. One atomic `symbol-ratings.json` publication selects the completed run; readers resolve all evidence from that run ID. Failed runs retain previous published outputs and write an error status. A previous output is not relabeled as freshly computed.

## Downstream contract

The output remains a single information vector per symbol:

`X_s(t) = sum_i(x_si * w_si * d(t-t_i)) / n_s`

The starter's `tensor` output is a provenance-bearing file reference. The full vector stays in NPZ; it is not printed as 50,000 table columns. A downstream Python consumer can load the exact pinned result:

```python
from pathlib import Path
from talg_py.news_weighted_profile import load_tensor
vector, provenance = load_tensor(Path("F:/TALG"), saved_news_revision, "AAPL")
```

The loader checks the profile, source version, symbol, file location and aggregate configuration version. Custom symbol code can use `input vector = news_tensor`, then compute `norm(vector)`, `component(vector, index)` or `dot(vector, a_compatible_weight_vector)`. A fitted effect model must define its own target and version; the connection weight does not supply a return forecast.

## Update mode and limits

Updates currently occur on Run. The expensive initial relevance pass does not run inside the old shared news worker's short refresh loop. Subsequent calls reuse cached scores/vectors, but still rescan the current collection, recompute weights and advance decay. This integration does not create a scheduler or launch the deferred week-long experiment.

The connection coefficients are still explicitly provisional, and the relationship catalogue is incomplete. Universal macro inclusion remains an open policy choice. The independent weighting architecture has research precedent; predictive usefulness of this particular implementation has not been established. See PRIOR_WEIGHTING_METHODS.md.

## Historical hybrid real-collection run

Historical 50k profile revision: `4d6696ba75a4c0fc39b549120e762b7474bb73238825dc930149421d6072a185`. Run: `20260926T224515Z-b3e4bd82`. The captured collection contained 22,787 distinct articles; 404 were retained for AAPL, producing one 50,000-value tensor. The filter labeled 11 related, 393 borderline, and 22,383 unrelated. Of the retained set, 174 passed the AI cutoff, 121 were retained by explicit company evidence, and 109 by supported counterparty-event evidence. These are model decisions, not accuracy measurements.

The aggregate reconstructed exactly from saved article vectors, connection weights and decay factors (maximum absolute error 0). The actual Java Data and Symbol lookup bridge read the published profile successfully. 59 focused Python tests and targeted Java profile/configuration/model-panel tests pass. The desktop package was rebuilt. Verification record: `work/symbol-news-tensor/connected-verification.json`.

A development spot check found routine institutional-holdings stories among the strongest text-relevance matches. Connection relevance must not be confused with novelty, importance or a price-impact estimate. The connection coefficients remain provisional and no predictive or decay-selection experiment was run.


## Previous 384-coordinate semantic-only run

At the user's direction, word and character hash features were removed from the active template and the new selected **Weighted semantic news** profile. Revision: `4b9ee7ccc2e47dacf1afd5b86f5bf8d39e7099ef33c74fc73ce9cbed47f2e07d`. Run: `20260927T004419Z-4ae33fcc`. The saved run retains 403 AAPL articles and produces exactly 384 pretrained semantic coordinates with no lexical blocks. The aggregate reconstructed from saved vectors and weights with maximum absolute error 0.0; Data and tensor lookup report the same dimensions. Existing profiles and Trade/Stocks pinned dependencies were verified unchanged. The financial-effect transformation remains untrained. The 128-token semantic input limit still applies.


## Current upgrade

See [Learned news v2](UPGRADE_V2.md) for the selected 1,024-coordinate encoder, complete supplied-text coverage, 4,096-coordinate trainable effect stage, data requirements and real-run verification. The effect model currently awaits outcome labels and supplies no forecasts.

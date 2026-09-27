# Symbol-related news average

Created 26 September 2026 from the project discussion. This folder preserves the proposed model, assumptions, evidence, and deferred experiment. It is source-controlled research material, separate from generated `work/` files.

## Agreed construction

For a requested stock symbol s, select its related articles. Tensorize each pair of **symbol + related article**, retain the article and its vector, decay each vector by article age, apply the separate connection weight, then take one count-normalized aggregate:

```text
x_si = encoder("Target stock symbol: " + s + "\nRelated news article:\n" + article_i)
n_s = number of related articles in the captured collection for s
w_si = connection_model(symbol s, story i, relationship evidence)
X_s(t) = (1 / n_s) * sum_i [x_si * w_si / mu(t - t_i)]
       = (1 / n_s) * sum_i [x_si * w_si * d(t - t_i)]
mu(a) = 1 / d(a)
```

There is one resulting vector per symbol, not four separate time-horizon outputs. Every related article in the captured collection counts in n, including very old articles. There is no normalization by the sum of decayed weights, no normalization of the final average to unit length, no automatic event clustering, and no hidden sentiment weighting. Each article vector is L2-normalized after its feature blocks are constructed. The final decay average is not re-normalized.

The source collection already merges exact canonical-URL and normalized-content duplicates into one information item. Repeated polling and multiple association sources do not add votes. Distinct paraphrased articles about the same event remain separate contributions. Thus n means distinct stored information items, not feed polls. This inherits the current collector's article identity policy; changes to that policy require a separately versioned experiment.

## New-story shock

`model.json` supplies one explicit provisional decay function:

```text
d(a) = 0.8 * 2**(-a/300) + 0.2 * 2**(-a/7200)
```

Age is measured in seconds. It starts at 1, falls quickly through the initial component, and retains a slower remainder. This is ONE decay function per story, not two news summaries. At age zero mu=1; mu increases with age. The implementation multiplies by d rather than constructing a potentially overflowing mu.

The 80% weight, five-minute fast half-life and two-hour background half-life are **illustrative implementation defaults**, not choices made by the user, estimated market parameters, or results of a decay experiment. They are editable in `model.json`. The future study will determine whether an initial shock and any proposed parameters help. It has NOT started.

## Meaning, relatedness, and direction

Relatedness now uses a small local AI with company, sector and supported one-hop supplier/customer contexts before tensorization. Every collected article is a candidate for a requested symbol. Ticker tags supply evidence rather than automatic acceptance. Borderline scores are retained under provisional cutoffs. See [relatedness design and validation limits](RELATEDNESS.md). A separate [connection model](CONNECTION_MODEL.md) supplies the new per-symbol/story weight; its initial coefficients are explicitly provisional and unfitted. Broad market-wide inclusion remains an open policy choice.

The current **Learned news v2** profile uses 1,024 pretrained semantic coordinates from pinned BGE-large weights. It encodes every token of the supplied headline/summary in windows, retains chunk vectors and coverage records, and includes company/sector context. It uses no lexical hash blocks. A trainable per-article effect transform (default 4,096 coordinates; configurable up to 100,000) and named outcome heads are implemented but await real outcome labels. See [the complete upgrade, evidence and commands](UPGRADE_V2.md).

The output is information, not a probability, expected return or trading instruction. The effect stage has explicit provisional targets and horizons; it must be fitted and evaluated on subsequent data before forecasts exist.

## Run

From `F:\TALG`, using the existing tensor runtime:

```powershell
& .\work\news-tensor\venv\Scripts\python.exe -m talg_py.symbol_news_tensor --root F:\TALG --symbol AAPL
# Several symbols:
& .\work\news-tensor\venv\Scripts\python.exe -m talg_py.symbol_news_tensor --root F:\TALG --symbol AAPL --symbol MSFT
# All configured symbols and symbols with recorded associations:
& .\work\news-tensor\venv\Scripts\python.exe -m talg_py.symbol_news_tensor --root F:\TALG --all-related-symbols
```

Each invocation captures the current collection once, encodes missing pairs, and writes a new run. Re-run to incorporate new articles or advance decay. No scheduled worker or next-week experiment is created. The existing shared encoder/collector may continue to collect data independently.

- Implementation: `talg_py/symbol_news_tensor.py`.
- Formula and pinned encoder: `research/news-average/model.json`.
- Pair cache: `work/symbol-news-tensor/pairs.sqlite`; append-only by symbol, article content revision and encoder version. Changing decay reuses the vectors; changing encoder input creates new pairs.
- Each run: `work/symbol-news-tensor/runs/<run_id>/` contains a frozen model, summary, per-symbol NPZ with original vectors, decay weights and aggregate, and JSON with retained articles and relation evidence.
- `work/symbol-news-tensor/latest.json` points to the last completed run; past runs remain intact.
- Read NPZ files with `numpy.load(path, allow_pickle=False)`. The requested average is `news_tensor`, with scalar `n`.

The standalone CLI reads the collection read-only. The weighted pipeline is now also connected through the separately named **Learned news v2** desktop News profile; see [connected profile](CONNECTED_PROFILE.md). That profile owns immutable relevance and connection dependencies, Run, Data and Symbol lookup outputs. Existing Trade/Stocks dependencies and armed sessions retain their prior revisions. The output is information; no direction predictor or order rule is introduced.

## Timing and reproduction

The collection is captured in one SQLite read transaction. Source publication time (clamped to first observation/current capture time) supplies age; encoding completion, relevance/connection evaluation and this run's source capture jointly bound availability. A run cannot claim that today's vectors or relationship evidence existed at historical publication time. Snapshots retain text, vectors, config, relation evidence, times and identifiers needed to reproduce their aggregate. The cache retains past content revisions. Empty related sets produce an explicit `no_related_articles` state and a zero vector, not a zero-effect forecast.

The current source's relationship table is not a historical availability ledger. This command intentionally offers no backdated as-of option. A later point-in-time experiment must use prospectively retained snapshots or a properly reconstructed observation/revision ledger, not today's associations or revised stories assigned to past dates.

## Research record

- [Relevance filter and small AI](RELATEDNESS.md)
- [Separate connection-weight model](CONNECTION_MODEL.md)
- [Connected News profile](CONNECTED_PROFILE.md)
- [Prior weighting methods](PRIOR_WEIGHTING_METHODS.md)
- [News component completion checklist](NEWS_COMPONENT_CHECKLIST.md)
- [Assumptions and hypotheses](ASSUMPTIONS_AND_HYPOTHESES.md)
- [Prior research and limits](EVIDENCE.md)
- [Downloaded papers and source manifest](papers/README.md)
- [Deferred decay study](DEFERRED_EXPERIMENT.md)

## Historical 50,000-feature experiment (26 September 2026)

The superseded hybrid is preserved in `baseline-weighted-50000.json` and immutable historical profiles. It is no longer the default. Its layout was:

| Coordinates (zero-based, end exclusive) | Count | Meaning |
| --- | --- | --- |
| [0, 384) | 384 | Learned semantic embedding of symbol plus article, using the existing pinned MiniLM encoder. |
| [384, 24960) | 24,576 | Signed counts of words and adjacent word pairs, mapped into stable hash buckets. |
| [24960, 50000) | 25,040 | Signed counts of character sequences of lengths 3, 4 and 5, mapped into stable hash buckets. |

The lexical features come from the complete provided text, rather than from repeating, padding or projecting the semantic vector. Many lexical coordinates are naturally zero for an individual article because it contains only a small portion of the feature space. Features use NFKC Unicode normalization, case folding, whitespace normalization and versioned BLAKE2b hashing. Collisions are possible; a bucket is not a unique word or a named concept. The sign is a hashing device, not positive/negative sentiment.

Each block is independently L2-normalized; the concatenation is then L2-normalized. All three blocks have equal norm before final normalization when nonempty. This scaling is a baseline assumption, not a learned weighting. Larger width is not proof of greater understanding, statistical independence, or predictive skill.

The same fixed feature layout is used for every article and symbol. Each historical 50,000-feature article vector is retained. The user's formula applies to the whole vector, now including the separately modeled connection weight, producing ONE 50,000-value aggregate per symbol. Pair cache identity includes the entire representation configuration, so old 384-value vectors and new vectors cannot mix. Previous caches and snapshots remain intact. Every new run saves `feature-layout.json`.

At float32, one retained article vector occupies 200,000 bytes before database/compression overhead. A 50,000-value float64 aggregate occupies 400,000 bytes. This is a feature-storage size, not a model parameter count. The encoder's learned parameter count is unchanged. The downstream predictor will need strong regularization and separate empirical evaluation.

Implementation: `talg_py/news_features.py`. The previous semantic-only configuration is retained as `baseline-384.json`. No predictive experiment or parameter search was started by this representation change.

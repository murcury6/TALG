# Relatedness before tensorizing

The executable research pipeline now scans every stored article for each requested symbol, before building any 50,000-feature vector. It uses company identity, sector context and explicitly sourced one-hop supplier/customer links. Recorded ticker tags are evidence, not the final relevance decision. Provider/feed tags are rebuilt from observations matching the current content revision; old tags do not silently survive a corrected article. Multiple matching paths never duplicate a story in the average.

`relatedness.json` is the inspectable configuration. It currently contains 88 entities, 24 custom research sector contexts and 12 supply-chain relationships. This is explicitly incomplete coverage, not a comprehensive commercial relationship database or official industry classification. Unknown symbols lack configured company/sector context and are flagged. Bare ambiguous ticker words such as CAT do not create entity matches; company aliases and contextual guards do. Supply-chain links include source URLs and support validity dates. Inferred recipients never become new graph anchors, so relationships expand only one hop.

## Small local AI

The installed model is the quantized ONNX conversion of `ms-marco-MiniLM-L-6-v2`, a small retrieval cross-encoder (about 22.7 million parameters, 23 MB quantized weights). It runs locally using the existing tensor Python environment. Model revision and downloaded file hashes are pinned and checked. No article text is sent to an external inference service.

Primary model documentation:
- https://huggingface.co/cross-encoder/ms-marco-MiniLM-L6-v2
- https://huggingface.co/Xenova/ms-marco-MiniLM-L-6-v2

The AI compares article text against separate company, sector and eligible counterparty queries. Counterparty queries require a supported relationship route. Combining every context into one giant query performed poorly in development, so each context gets its own score. The maximum applicable score decides inclusion. The current thresholds retain scores at least 0 as related and scores from -4 to 0 as borderline. Scores below -4 are excluded unless configured explicit company-reference or supported supplier/customer-event evidence retains the item as borderline. Provider ticker tags alone never trigger that retention. Saved decisions retain the original AI state and a separate inclusion reason. **These are provisional retrieval cutoffs, not calibrated financial probabilities.** Keeping borderline stories favors recall but admits more noise.

Long inputs are evaluated in overlapping 128-token pair windows, stride 32; the maximum window score is retained. This fixed a synthetic case where a relevant sentence buried in a long unrelated article was missed with 384-token windows. It does not prove perfect recall. More windows and more contexts offer more chances for a false positive. Text means the collected headline and publisher summary, not an unavailable full article.

Scores are cached by pinned model configuration, exact query, article ID, content revision and text. Errors fail the run rather than silently falling back to accepting everything. Snapshots record accepted and rejected decisions, context scores, relationship evidence, model files and evaluation times. Availability includes the time relationships and weights were evaluated; current knowledge cannot be backdated to an old publication date.

## Run and inspect

From the repository root:

```powershell
& .\work\news-tensor\venv\Scripts\python.exe tools/setup-news-relatedness.py
& .\work\news-tensor\venv\Scripts\python.exe -m talg_py.symbol_news_tensor --root F:\TALG --symbol AAPL --relatedness-only
& .\work\news-tensor\venv\Scripts\python.exe tools/check-news-relatedness.py
```

The setup command retrieves the pinned model. `--relatedness-only` saves relevance decisions and connection weights without loading the tensor encoder or calculating a decayed aggregate. Successful runs are in `work/symbol-news-tensor/runs/`; `latest-relatedness.json` points only to a completed relatedness run. Normal tensor runs use exactly the same filter first. Scanning all stored articles for many symbols can be expensive; query scores shared across symbols reuse the same cache.

The connected weighted News profile has now completed a full AAPL archive pass. A real-headline spot check identified obvious false rejections, including company litigation and product-market stories; this motivated the explicit entity/economic-link retention policy above. The final profile is separately versioned and reuses the raw inference cache. See CONNECTED_PROFILE.md and the saved run report for counts. Acceptance counts are model decisions, not independently measured accuracy.

59 focused Python tests and targeted Java integration/configuration tests pass. The actual downloaded model has also been exercised on synthetic examples in `relevance-smoke-cases.json`; output snapshots are in `work/symbol-news-tensor/relevance-smoke/`. These are development regression checks, not an independent financial benchmark.

## Open policy and validation work

Broad market-wide information deserves a separate explicit policy. Filtering solely on the label 'financial news' would also admit unrelated company stories; filtering out all 'social news' would omit economically relevant strikes and boycotts. Proposed policy: retain major macroeconomic context broadly, then use symbol-specific connection strengths for company/sector/supply-chain stories. The current company/sector configuration does not yet implement universal macro inclusion: the Fed-rate synthetic case is deliberately recorded as an open policy case, not counted as a passing expected result. No claim that every market affects every stock equally is built in.

A manually judged sample must include both accepted and rejected articles, direct and indirect links, ambiguous names, macro stories and social events with economic consequences. Record missed relevant stories and false inclusions separately. Freeze thresholds before evaluating a held-out sample. The downstream predictive experiment and next-week decay selection remain deferred.

Implementation: `talg_py/news_relatedness.py`, `talg_py/news_relevance_ai.py`. Connection weights: [CONNECTION_MODEL.md](CONNECTION_MODEL.md).

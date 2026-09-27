# News analysis component: completion checklist

Current representation update, 27 September 2026: the user removed engineered word/character hash features. The selected **Learned news v2** profile uses 1,024 pretrained semantic coordinates with complete supplied-text chunk coverage. The trainable financial-effect path is implemented and awaits labels; see UPGRADE_V2.md. Historical 50k references below document the prior implementation; the learned financial-effect stage remains untrained. See LEARNED_NEWS_EFFECTS.md.

Reviewed 26 September 2026. Architecture specified by the user: (1) bid analysis, (2) news analysis, including earnings and company information, and (3) stock selection. Current focus is completing news analysis. This document is an assessment, not authorization to begin the deferred decay experiment.

## Responsibility and existing implementation

News analysis takes a symbol and related information, retains each input, builds its feature vector, applies the configured decay, and averages by article count. The new implementation produces one 50,000-feature tensor per symbol. Raw archives, a versioned pair cache, snapshot provenance, executable formula tests, and the assumptions/research collection exist.

The news component should describe information and, if tasked with direction, estimate its symbol-specific prospective effect. Stock selection owns choosing the candidate stocks; bid analysis owns quote/bid interpretation. News output must be consumable through explicit versioned dependencies, without silently selecting stocks, placing orders or modifying an armed strategy.

## Missing work, in implementation order

### 1. Unified related information intake, including earnings

Current input is a headline plus publisher-supplied summary with provider/manual/ticker-feed associations. SEC company facts are fetched by the separate CompanyFinancialsPanel and are not inputs to the new symbol_news_tensor pipeline.

Needed: retain earnings releases and relevant filings/available transcripts or guidance statements with symbols, periods, publication/receipt times and source links. Preserve structured actual EPS/revenue, units, accounting basis (e.g. GAAP versus adjusted), prior comparable periods, and guidance where actually supplied. The user currently intends the aggregate itself as the information input; a separate analyst-consensus model is not requested for this step. If a future feature explicitly compares results with analyst expectations, it will require timestamped pre-release estimates. SEC actuals alone do not supply that consensus.

If financial data are rendered into tensorizer text, retain their structured original records alongside the text so numbers, units, periods and revisions remain checkable. Do not treat a later SEC filing timestamp as the earlier earnings-release arrival, or a later restatement as the original reported number.

### 2. Entity, event and relevance handling

Implemented first at the user's direction: a small local AI evaluates every candidate article against company, sector and sourced one-hop supplier/customer context. A separate connection-weight model now scales retained vectors before decay and averaging. Relevance thresholds and initial connection coefficients remain provisional; macro inclusion and complete supply-chain coverage remain open. See RELATEDNESS.md and CONNECTION_MODEL.md. Independently audit false positives and missed indirect effects before claiming reliability.

Retain the user's multiple-story hypothesis: distinct related stories may reinforce the average. Keep enough source/event/revision metadata to later compare repeats, corroboration, contradictions and updates; do not silently introduce event deduplication or change the count denominator.

### 3. Connect the new representation to the News model

The separate **Weighted news 50k** profile is now connected to desktop Run, Data and Symbol lookup, with frozen relevance and connection dependencies and per-revision outputs. Original article vectors, weights, decay factors, count and configuration provenance remain available. Changing connection weights reuses vectors. The full tensor is exposed through a checked file reference and Python loader; it is not relabeled as the old News(rating) scalar.

The initial symbol list is AAPL and can be edited in Settings. Additional symbols need processing before their outputs exist. Independent Java/Python integration tests cover saved-profile behavior and existing consumer compatibility. See CONNECTED_PROFILE.md.

### 4. Continuous updates and trustworthy availability history

The old news worker is continuous; the connected weighted profile updates on Run, once per snapshot. Add processing for newly observed/revised related inputs and periodic decay refresh even when no articles arrive. Define restart behavior, backlog/latency reporting and stale/failed output handling. Keep prospective feature snapshots and relationship/revision availability records so later tests cannot use knowledge collected after a prediction.

Do not let a recently recomputed timestamp hide old evidence: retain both computation time and the newest/oldest contributing information times. Make no-related-news, incomplete/backlogged processing, and unavailable source data distinct from neutral predicted direction.

### 5. Financial interpretation, if News must output direction

A 50,000-feature vector is a representation, not a directional probability. The current encoder has 384 learned semantic output coordinates; the remaining features are deterministic lexical hashes. Increasing total width did not upgrade the underlying language model.

Needed: define the future target/horizon (and whether market-relative), prepare point-in-time labels, and fit a news-effect model with calibration/uncertainty checks. Its output might include a forecast or directional probability with horizon, model version, provenance and validity state. If the architectural boundary instead gives News responsibility only for information tensors, the effect model belongs downstream; that boundary should be explicit. No arbitrary freshness score should be renamed a price forecast.

### 6. Evidence of usefulness — deferred until instructed

Implementation correctness tests have passed; predictive efficacy and decay selection have not been tested. Compare news-enhanced predictions with suitable price-only, class-frequency, news-count and smaller-representation baselines. Use chronological development/selection/evaluation periods and account for event/day dependence, overlaps, source latency and regime differences. Costs and execution evaluation are required before making trading-performance claims.

The requested next-week decay study remains WAITING FOR EXPLICIT USER INSTRUCTION, as recorded in DEFERRED_EXPERIMENT.md. This checklist does not schedule it or select new parameters.

## Definition of completion

Engineering-complete: a chosen News profile continuously produces the configured symbol-level information output from related headlines/company disclosures/earnings, with auditable sources, correct availability, predictable restart behavior, and usable versioned outputs for the other components.

Research-validated: after the separately authorized experiment, measured incremental usefulness and calibrated limitations are documented on later untouched data. An engineering-complete module may still yield a negative research result.

Current scope: finish and evaluate the relatedness/connection step, then wait for the user to say next. Subsequent work includes connecting earnings/company information to the input collection, then integrating the new tensor into the actual News profile and continuous processing. Define the financial output before fitting it. Keep the user's decay-and-count-average construction as the primary baseline.

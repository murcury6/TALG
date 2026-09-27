# Assumptions and hypotheses

Recorded 26 September 2026. Nothing in this register is a claim that the trading strategy is profitable. Mathematical checks and implementation tests are distinct from empirical validation.

| ID | Statement | Current standing | Future evaluation / possible failure |
| --- | --- | --- | --- |
| H01 | A symbol plus a related article can be encoded into useful financial information. | Hypothesis; symbol-conditioned input implemented using a general semantic encoder. | Compare against article-only inputs and simple text/news-count baselines. A ticker prefix may not encode entity roles or financial effects well. |
| H02 | Related story vectors occupy a common space in which addition and arithmetic averaging preserve useful information. | Modeling assumption. | Compare later predictive performance against other aggregates. Contradiction, event interactions and a dominant event can be lost in an average. |
| H03 | `sum(x_i / mu(age_i)) / n` is a useful symbol-level information state. | User's chosen baseline; exact count denominator implemented. | Compare against prices-only and news-count baselines. All old related articles remain in n, so equal fresh evidence shrinks as archive size grows. Measure rather than silently change this behavior. |
| H04 | Newly arriving information produces an initial shock that falls rapidly and may leave a slower remainder. | User hypothesis; provisional two-component decay is a candidate, not a finding. | Compare no decay, single exponential and fast-plus-slow decay after authorization. Publication freshness is not necessarily informational novelty. |
| H05 | Multiple distinct stories on the same subject reinforce a coherent direction through averaging and may represent attention or corroboration. | User hypothesis; paraphrases are retained separately, not clustered. | Compare repeated coverage with genuinely new event updates. Distinct reprints can amplify one story's share of the average; their errors are correlated. Literature on stale news supplies a counter-hypothesis, not a blanket prohibition. |
| H06 | Stronger recent-story weights sufficiently handle mixtures of different events. | User hypothesis, untested. | Separate conflicting/concurrent events from consistent coverage. A fresh minor item can outweigh an older major event; age alone does not measure importance. |
| H07 | A small AI with company, sector and one-hop supply-chain context selects genuinely related articles. | Implemented with provisional thresholds; ticker tags are supporting evidence. | Independently label accepted and rejected examples. The generic retrieval model is not a calibrated financial classifier and the relationship graph is incomplete. |
| H08 | A fitted model can map the aggregate to a useful forecast of subsequent direction. | Not fitted in this change; no direction output. | Define horizon/labels; compare calibrated probabilities, loss and accuracy against class-frequency and price-only models on untouched later data. Similarity coordinates do not inherently mean bullish/bearish. |
| H09 | One decay function and its parameters transfer sufficiently across symbols/event types. | Simplifying baseline assumption. | Report results by event type, symbol and session. A universal half-life may be inappropriate. |
| H10 | Publication age is a suitable decay clock once actual availability is enforced. | Baseline choice, not established. | Compare publication age and first-observed age later; quantify feed and encoding latency. Revised text must never be backdated. |
| H11 | Larger encoders or more complete input text improve downstream usefulness. | Open hypothesis; the semantic baseline remains 384 dimensions/128 tokens; the new combined representation has 50,000 features. | Compare frozen encoders on identical dates, measure truncation, memory and latency; no dimension-only enlargement or padding. |
| H12 | A week provides useful initial evidence for decay selection. | Pilot scope only; not sufficient by itself to establish robustness. | Overlapping labels and many symbols are dependent. Use chronological splits and day/event blocks; require further untouched dates after selecting parameters. |

## Fixed implementation conventions

- Each stored information item related to the symbol gets one contribution, regardless of the number of relation sources. Separate paraphrased items remain separate.
- The newly requested independent connection weight is explicit and versioned. No normalized attention, freshness-weight denominator, sentiment multiplier or trade rule is silently introduced.
- Default decay parameters are engineering placeholders and are labeled as such in the saved config.
- A new text revision creates a new pair-cache entry and remains auditable; only the current revision in a captured source snapshot contributes to that run's average.
- Repeated polling does not reset age. The shared collector retains the original decay origin for revised stories; treatment of materially new updates at the same URL is another future comparison.
- A stronger vector is not automatically greater confidence. Up/down probabilities require a fitted and calibrated model and appropriate evaluation.
- One average is created per requested symbol. There is no cross-symbol average and no automatic portfolio decision.

## Linear identity versus empirical hypothesis

For a linear score without intercept, applying the score to the aggregate equals averaging the decayed individual scores: `beta dot X = sum(d_i * (beta dot x_i)) / n`. This follows from linearity, not market evidence. With an intercept, its treatment must be specified explicitly. Applying a nonlinear probability transform after averaging is generally NOT equal to averaging individual probabilities. Neither identity proves that the resulting score forecasts price direction.

## Representation extension: 50,000 features

H13: Semantic features combined with full-input word, phrase and character features improve useful information retention. This is untested. Compare the saved 384-feature baseline with the new 50,000-feature hybrid on identical later observations and labels. The additional coordinates are deterministic lexical hash features, not newly trained semantic dimensions or 50,000 independent observations.

H14: Equal L2 scaling of the three blocks is an adequate provisional weighting. This is an implementation assumption; lexical repetition, hash collisions and common wording can affect the average. The signed hash values do not encode bullish/bearish meaning.

The requested vector width changes neither the count denominator nor the decay function. Dense neural-model size, embedding width, lexical feature count and downstream fitted parameter count are different quantities.

## Connection-weight extension

H15: A separately modeled connection strength improves the information aggregate: `sum(x_si * w_si * d(age_i)) / n_s`. Implemented with a separately saved logistic model; initial coefficients are hand-set and unvalidated. Human connection labels are required before fitting. Compare unit weights and learned weights on held-out events before claiming improvement.

H16: Human judgments provide a sufficiently consistent target for connection strength. Untested. Define a rubric, measure agreement, and distinguish economic connection from sentiment, expected return and prediction confidence.

H17: Broad market-wide information should be included across symbols, while ordinary unrelated company news should not. Proposed policy, not yet universally implemented. Social news can still carry economically relevant information.

The abbreviated weighted formula did not explicitly resolve its outer denominator; the original count denominator is retained pending clarification. The original unweighted hypothesis remains a saved comparison. The average is the information input to the downstream model; separate analyst-consensus modeling is not part of the current requested step.

H18: Explicit company-reference and documented supplier/customer-event evidence should retain a story even when the generic retrieval AI scores it poorly. Implemented as a separately recorded borderline inclusion policy after real-news spot checks found company litigation and product-market stories below the provisional AI cutoff. Ticker-provider tags alone do not override AI rejection. Measure both recovered relevant stories and additional false inclusions on a held-out judged sample.


## Width comparison and learned effects clarification

H19: Doubling the hybrid representation from 50,000 to 100,000 improves predictive usefulness. The paired mechanics check on 405 AAPL articles reduced hash collisions and preserved very similar pairwise geometry (cosine correlation 0.999402), while doubling dense memory. No predictive outcomes were tested. Both variants still contain only 384 learned semantic coordinates. See WIDTH_COMPARISON.md.

H20: A supervised symbol-conditioned article transformation, trained through the user's weighted decay/count average, produces more financially useful information than raw text encoding alone. Proposed, not implemented or fitted. See LEARNED_NEWS_EFFECTS.md for training targets, availability requirements and the distinction between learned features, named financial heads and causal effects.

H21: Increasing the width of that genuinely trained representation improves held-out forecasts. Open hypothesis; this is distinct from H19. Output width is not equivalent to parameter count or independent information. Compare widths on identical point-in-time data with an untouched chronological test.


27 September 2026 user decision: engineered word/character text-pattern blocks are excluded from the active architecture. H13/H14 and H19 remain historical experiment records, not the selected approach. The new baseline uses only 384 pretrained semantic coordinates while H20/H21 await actual financial-effect training.


## Implemented learned-news upgrade

H22: A larger pretrained semantic encoder with complete supplied-text token coverage improves financially useful information retention. Implemented with 1,024-coordinate BGE-large, repeated company context, retained chunk vectors and token-weighted chunk pooling. Coverage and tail sensitivity verified; predictive advantage is untested. English-focused model replaces the multilingual baseline only in the new profile.

H20/H21 implementation update: a trainable per-article tanh transform, the exact connection/decay/count average, and multi-horizon outcome heads now have fitting and pinned inference implementations. Default hidden width 4,096, with larger widths including 50,000 supported. Synthetic learning tests pass. No real financial model is fitted: the first v2 run supplies one prospective observation and zero matured labels. Validation/test dates are separated chronologically with overlapping labels purged. See UPGRADE_V2.md.

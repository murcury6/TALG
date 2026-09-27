# Learned news effects: proposed next stage

Recorded 27 September 2026 UTC following the user's clarification: a large vector should contain learned information about expected financial effects, rather than merely more text hash values. This retains the original design and hypotheses. The encoder and trainable effect pipeline have now been implemented; see UPGRADE_V2.md. No financially trained effect model or validated predictions are available yet.

## Intended architecture

For each retained related article i and symbol s:

    z_si = f_theta(article_i, symbol_s, company/relationship context known at prediction time)
    Z_s(t) = sum_i(z_si * w_si * d(t - t_i)) / n_s
    forecast_s(t) = g_phi(Z_s(t), explicitly permitted current context)

The trainable article transformation is placed before the user's existing count-normalized average. Connection weights and the existing decay remain separate components and are initially held fixed. The news model owns these forecasts; stock selection decides how to use them. Neither changing a News profile nor training a new model silently retargets a consuming profile.

The transformation should be allowed to read the original retained text or a stronger text encoder, rather than being limited forever to the current truncated 384-coordinate semantic block. An expansion of a frozen vector alone cannot recover facts absent from its input. It can learn a useful nonlinear basis for available information.

## Two distinct kinds of useful coordinates

1. Learned internal features: a trainable vector optimized to help predict outcomes. Its coordinates need not have individual human-readable names. Candidate widths such as 1,024 and 4,096 would be development comparisons, not established optimal sizes. Larger widths, including 50,000, remain testable when training data and measured gains justify them. Merely adding an untrained projection is not an effect model.
2. Named output heads: quantities with an explicit training target and measurement definition. Candidate outcome heads include future market-relative return, probability of positive market-relative return, future realized volatility, and downside return quantiles, across defined horizons. Candidate text interpretation heads include demand, input-cost, margin, financing and litigation pressure, but these require appropriate labeled examples and an unknown/not-observed state. A language model's plausible explanation is not automatically a measured financial effect.

A large output array can also represent distributions over outcomes and horizons. More bins do not constitute more independent learned facts. Probabilities and quantiles need calibration and consistency checks. Named forecasts should be decoded from the aggregate; directly decaying and count-averaging article probabilities would not generally produce a normalized stock forecast distribution.

## How the transformation becomes learned

Train theta and phi using forecasts produced from complete point-in-time news sets and the subsequently observed outcomes. The loss must propagate through the same weighted average used at inference. This avoids assigning the same ensuing stock move to every contemporaneous article as if each were an independently measured causal effect. Article-level features trained only through this loss are predictive contributions, not identifiable per-story causal effects.

Named event/mechanism heads need their own supervised targets or separately audited weak labels; optimizing one future return target does not establish that a particular coordinate represents margins or supplier disruption. Financial text pretraining can help interpret language, but sentiment training alone does not teach ticker-specific future returns. FinBERT is an example of financial language/sentiment training: https://arxiv.org/abs/1908.10063 . Training a news encoder/aggregation against market movement has precedent: https://aclanthology.org/2021.econlp-1.6/ . Neither validates our proposed heads or vector widths.

## Data and validation requirements

The inspected Python news pipeline has semantic encoding, relevance inference and a connection-label fitter, but no implemented point-in-time future-outcome label builder or fitted news-effect model. Current snapshots cannot simply be backdated into historical training examples. Existing market-data collection elsewhere in the project must be audited before reuse.

Each example must identify the symbol, prediction cutoff, eligible source revisions and availability times, contemporaneously available relationship context, target horizon, label interval and dataset version. Return labels require auditable prices, corporate-action treatment, market/session handling and an explicit benchmark. Example target definition: symbol log return minus benchmark log return over the same future interval; this is not beta-adjusted abnormal return or proof of causation. Volatility targets require a defined sampling frequency; downside targets require a specified quantile. Non-price mechanisms require separate labels.

Use chronological train/development/test periods; training labels must end before subsequent evaluation cutoffs, with purging of overlapping label intervals. Group or audit repeated stories/events to limit leakage. Freeze model choices before the untouched test. Compare price/context-only, news-count and smaller learned representations; report calibration as well as forecast losses. A larger learned representation is supported only by better held-out performance, not by its number of coordinates.

First implementation should establish trustworthy targets and dataset boundaries, then train a small multi-task baseline through the existing aggregate. Only then compare larger learned widths. The user's separate week-long decay study remains deferred until explicitly started.

## Status

The implementation now exists with provisional 1/5/20-session targets and an explicit unfitted status. Fitted-artifact inference is connected, but no financial effect model has been fitted or activated. The active information output is the stronger 1,024-coordinate chunked encoder. The completed 50k/100k comparison concerns hashing only and must not be presented as a test of this new learned-effect hypothesis.

## User decision: no lexical feature blocks

The user explicitly removed engineered text-pattern features from scope. The active template and new Weighted semantic news profile use only the existing 384-coordinate pretrained semantic encoder as a temporary input representation. No word/character hashes or padded expansions enter this profile. Historical hybrids remain reproducible but are not the chosen architecture. Training a financially useful effect representation remains outstanding; removing lexical features does not itself accomplish that training. With this removal, the current 128-token semantic limit also becomes the sole encoded coverage limit; original retained text must remain available for a stronger/full-text successor.

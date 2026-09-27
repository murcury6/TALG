# Decay comparison — not started

Status: **WAIT FOR THE USER TO EXPLICITLY SAY TO START.**

The user requested that decay functions be tested over the next week only after the initial documentation and implementation, and said they will indicate when. No start/end dates are fixed, no automation is scheduled, and no decay parameter search or predictive evaluation is authorized by this file. Unit tests of formula correctness and an implementation smoke run are not the proposed market experiment.

## Questions to freeze before starting

1. Prediction target and horizon (for example, a subsequent return or up/down label); distinguish reaction already observed from future movement.
2. Symbol universe, relatedness rule, story identity policy, encoder version, source coverage and latency treatment.
3. Exact calendar dates, market sessions and data availability requirements.
4. A small declared candidate set: no decay, one exponential, and fast-shock plus slower-background decay. Keep the user's count denominator fixed for the primary comparison.
5. Training, parameter-selection and final untouched evaluation dates. Do not select a decay on a week and report the same week as independent validation.

## Suggested later comparisons

- Prices-only, news-count-only, and symbol-conditioned news aggregates with the same downstream model and dates.
- Article-only encoding versus symbol-plus-article encoding.
- Repeated coverage retained (primary user hypothesis) versus event/novelty controls (secondary comparison, not an automatic production change).
- Different-event conflicts and recently revised articles, alongside consistent same-event reporting.
- Track predictive loss and calibration, class balance and accuracy, coverage/missingness, and encode-to-decision latency. Economic claims additionally require actual strategy costs and execution evaluation.
- Assess dependence by trading day and event. Thousands of overlapping forecasts in one week are not thousands of independent market regimes.
- Preserve every prospective article revision, relation observation, encoding completion, feature snapshot, candidate configuration and prediction before attaching future labels.

A one-week study would be an initial pilot. Report inconclusive results honestly and retain further untouched dates for confirmation. Do not promise the best decay or a profitable directional forecast in advance.

# How weighted news has been used before

Reviewed 26 September 2026. These precedents motivate comparisons; they do not validate our model, connection coefficients, vector size or chosen decay.

## Count-normalized weighted news

Happersberger, Lohre, Nolte and Rother (working paper, 15 January 2020) construct weighted sentiment as the sum of event sentiment times event weights, divided by the number of eligible events in a lookback window (Appendix B, equation 12). One weighting scheme is temporal decay. This is close to the user's count denominator. Their features are sentiment scores within a bounded window; ours are symbol-conditioned vectors and count all retained related articles. Their US/Japan results are less favorable than some other regions.

Source and locally saved paper: [Author manuscript](https://wp.lancs.ac.uk/fofi2020/files/2020/04/FoFI-2020-120-David-Happersberger.pdf), `papers/Happersberger-et-al-2020-News-Analytics.pdf`.

## Learning article weights with attention

Hu et al., *Listening to Chaotic Whispers* (WSDM 2018; revised preprint 2019), encode news, learn news-level softmax weights, aggregate article vectors into a daily representation, and learn temporal attention across days for trend prediction. This supports separating article importance from temporal information. Their weights are normalized over an article set and their time component is learned; that differs from our independent bounded connection weights, fixed decay and division by article count.

Source: [Paper](https://arxiv.org/abs/1712.02136), `papers/Hu-et-al-2019-preprint-Chaotic-Whispers.html`.

## Learning relevance without manual article labels

Del Corro and Hoffart (ECONLP 2021) train on headlines and stock-index movement labels, then reuse unnormalized attention scores to rank financial relevance. They use about 1.5 million headlines across four indices. This offers a training route beyond human connection-strength labels. It is a relevance-ranking study, not proof of a tradable forecast: the authors explicitly accept stories describing already observed price moves and discuss uncertainty in interpreting attention. Our current model does not implement this joint training.

Source: [ACL paper](https://aclanthology.org/2021.econlp-1.6/), `papers/Del-Corro-Hoffart-2021-Financial-Relevance.pdf`.

## Shared market context with stock-specific exposure

RavenPack's 2012 sentiment-factor methodology estimates each stock's exposure to changes in an overall news-sentiment index through regression, controlling for traditional risk factors. This is a precedent for treating market-wide news as shared context while allowing different stock exposures. It does not assume identical effects across every company and does not establish article-level causal connections.

Source: [RavenPack methodology](https://www.ravenpack.com/research/constructing-sentiment-factor/).

## What these imply for this project

The weighted-vector architecture has precedent. Its empirical contribution would be demonstrating that our particular relevance, relationship, representation and decay choices help on later untouched observations.

Two distinct training targets remain possible:

- Connection judgments: fit the independent connection model to consistent human labels describing whether and how closely a story concerns a company or its economic links. The supervised fitter already supports this target.
- Predictive usefulness: fit weights jointly with a downstream prediction objective. This can learn what matters for that objective, but its weights should not automatically be described as real-world connection strengths or causal explanations. It requires point-in-time news/price labels, chronological evaluation and leakage checks.

Neither approach has been fitted on real financial labels here. The current connection coefficients remain explicit engineering priors. No experiment or decay-parameter search has been started. Preserve unit-weight and smaller-vector baselines, and compare count normalization with other normalization rules only in a separately specified experiment.

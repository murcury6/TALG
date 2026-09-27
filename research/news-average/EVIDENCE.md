# Prior evidence: what has and has not been tested

Literature checked 26 September 2026. This is a focused research review, not an exhaustive systematic review. The sources below test related ideas; none establishes this project's exact symbol-conditioned, count-normalized decay formula or provisional shock parameters.

## Repeated information and novelty

Paul Tetlock (2011), *All the News That's Fit to Reprint: Do Investors React to Stale Information?*, Review of Financial Studies 24(5), 1481–1512. [Author's university research page](https://business.columbia.edu/faculty/research/all-news-thats-fit-reprint-do-investors-react-stale-information).

The study measures staleness through similarity to a firm's preceding stories. It finds a weaker stock response to stale news and evidence of subsequent reversal, consistent with some overreaction to repeated information. This directly motivates testing whether repeated coverage reinforces a useful signal or overweights old information. It does not say that every repeated story is useless, and does not validate a particular time-decay curve. Newly published wording and novel underlying information are different quantities.

## Aggregating headline representations

Luciano Del Corro and Johannes Hoffart (2021), *From Stock Prediction to Financial Relevance: Repurposing Attention Weights to Assess News Relevance Without Manual Annotations*. [Paper and publication](https://aclanthology.org/2021.econlp-1.6/), [full paper](https://aclanthology.org/2021.econlp-1.6.pdf).

This work combines headline representations using learned attention for index movement classification, and evaluates relevance ranking using about 1.5 million headlines. It supports studying aggregated text features and differentiated relevance, but its learned weighting and index-level task differ from our equal-count average for an individual symbol. The paper explicitly does not control for endogeneity in its relevance analysis; reported relationships should not be read as causal or as a guarantee of executable profit.

## News effects evolving over time

Steven L. Heston and Nitish R. Sinha (2016), *News versus Sentiment: Predicting Stock Returns from News Stories*, FEDS 2016-048. [Federal Reserve working paper page](https://www.federalreserve.gov/econres/feds/news-versus-sentiment-predicting-stock-returns-from-news-stories.htm).

Using more than 900,000 stories, the authors report short-lived daily-news predictability, longer-horizon relationships for weekly news, and faster responses to positive news than negative news. This is particularly relevant to the shock-and-decay hypothesis, but the differing horizons and asymmetry also challenge a universal age-only curve. Their sentiment method and sample differ from our embedding pipeline; their findings do not select our half-lives.

Xiao Liu, Heyan Huang, Yue Zhang and Changsen Yuan (2020 preprint; later journal version), *News-Driven Stock Prediction With Attention-Based Noisy Recurrent State Transition*. [Authors' paper](https://arxiv.org/abs/2004.01878).

The authors model sequential stock movement with news-event attention and an explicit noise component, reporting improvements against their baselines. This provides precedent for testing temporally structured news information. A recurrent model with attention is not evidence that a fixed exponential mixture, or recency alone, captures the same behavior.

## Conclusion for this project

News aggregation, relevance, novelty and temporal effects have empirical precedents. The exact proposed average and claim that recency shocks sufficiently handle different stories remain hypotheses. Implement the proposed formula faithfully, preserve evidence, and later compare it with simple baselines using information genuinely available at each prediction time. This implementation is not a replication of any cited paper.

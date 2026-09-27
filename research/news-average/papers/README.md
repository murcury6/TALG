# Collected research papers

Downloaded 26 September 2026 from public author, institutional, and proceedings sources. Full papers are saved locally; notes in [EVIDENCE.md](../EVIDENCE.md) distinguish their findings from this project's untested hypotheses.

| Paper | Local PDF | Why it matters |
| --- | --- | --- |
| Tetlock, *All the News That's Fit to Reprint* (2011 publication) | [October 2010 author manuscript](Tetlock-2010-author-manuscript-Fit-to-Reprint.pdf) | Repeated coverage, stale information, and subsequent reversal. The saved version is an author manuscript, not the final publisher typesetting. |
| Heston & Sinha, *News versus Sentiment* (2016) | [FEDS working paper](Heston-Sinha-2016-News-versus-Sentiment.pdf) | Different response horizons and faster/slower reactions to positive/negative news. |
| Del Corro & Hoffart, *From Stock Prediction to Financial Relevance* (2021) | [ECONLP proceedings paper](Del-Corro-Hoffart-2021-Financial-Relevance.pdf) | Aggregated headline representations and learned news relevance. |
| Liu et al., *News-Driven Stock Prediction With Attention-Based Noisy Recurrent State Transition* (2020) | [arXiv v1 preprint](Liu-et-al-2020-News-Driven-State-Transition.pdf) | Temporal news modeling and separation of news effects from noise. |

`sources.json` contains titles, authors, version descriptions and original URLs. `manifest.json` adds download verification timestamps, resolved URLs, byte counts and SHA-256 checksums. `download.py` reproduces the collection and checks PDF signatures; it does not overwrite an existing local paper. Keep the version distinctions when citing.

No paper here proves that our particular symbol-conditioned average or provisional decay function predicts direction profitably.

Two additional weighting precedents were collected: Happersberger et al. (2020), saved as PDF, and Hu et al. (WSDM 2018, revised preprint 2019), saved as official full-text HTML after repeated PDF transfer truncation. The manifest records actual URLs, formats and checksums. See [comparison](../PRIOR_WEIGHTING_METHODS.md).

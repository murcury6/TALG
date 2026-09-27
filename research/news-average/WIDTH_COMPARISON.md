# Controlled representation width comparison

Completed 27 September 2026 UTC on 405 retained AAPL articles from run `20260927T003053Z-b603db10`. This measures representation mechanics, not forecasting performance.

| Measurement | 50,000 values | 100,000 values |
| --- | ---: | ---: |
| Learned semantic coordinates | 384 | 384 |
| Word hash coordinates | 24,576 | 49,342 |
| Character hash coordinates | 25,040 | 50,274 |
| Within-article word feature collisions | 50 | 20 |
| Within-article character feature collisions | 2,985 | 1,868 |
| Corpus word feature collision fraction | 17.02% | 9.33% |
| Corpus character feature collision fraction | 54.22% | 34.46% |
| Dense article vector memory, decimal MB | 81 | 162 |
| Compressed snapshot, decimal MB | 1.724 | 1.946 |
| Lexical rebuild, single run, seconds | 0.695 | 0.715 |

Collision counts measure unique feature occurrences minus occupied hash buckets. Corpus collisions count different features across the entire corpus sharing a coordinate; they are not a rate of wrong predictions or a direct measurement of lost meaning. Timing excludes neural inference and is not a latency benchmark.

Across 2,048 sampled article pairs, cosine similarities had correlation 0.999402; the mean absolute change was 0.002421. Wider hashing reduced collisions but barely changed the measured similarity structure. Whether it improves predictions remains untested.

Controls: identical article revisions, symbol/text, learned semantic vectors, block scaling, connection weights, frozen decay weights/time and article-count denominator. Rebuilding the original representation agreed to 1.49e-08; the semantic block was preserved to the same floating-point tolerance. The aggregate was checked against the original saved aggregate.

Artifacts: `work/symbol-news-tensor/width-comparisons/20260927T003823Z-e5e0c1f8` contains both NPZ variants, their model definitions, input records, sampled pair similarities and the full report with provenance hashes. Comparison outputs record their actual new availability time separately from the frozen source time. They are not historical forecasts. The selected News profile was not changed. The deferred decay experiment was not started.

Known baseline limitation: the pinned v1 word tokenizer contains incorrectly encoded non-ASCII punctuation/currency characters in its regex. The controlled comparison preserves that behavior to reproduce existing cached vectors. A correction needs a separately versioned encoder/cache identity; the character block still processes Unicode text. The wider version does not repair tokenization or the semantic encoder's 128-token input limit.

## Interpretation and revised direction

The user clarified that the desired expansion consists of learned, financially useful information rather than more text hash coordinates. See LEARNED_NEWS_EFFECTS.md. Width alone does not meet that requirement. These measurements support closing the hash-width mechanics comparison and designing a supervised effect representation next; they do not select a predictive winner.

"""Paired representation-width comparison on one frozen News snapshot.

Measures representation mechanics only. It does not fit a predictor, score future
returns, select decay parameters, or change the active News profile.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import time
import unicodedata
import uuid
from itertools import pairwise
from pathlib import Path

import numpy as np

from .news_features import WORD_PATTERN, ArticleEncoder, feature_layout, normalize
from .news_tensor import atomic_json, atomic_npz, digest, utc
from .news_weighted_profile import load_tensor


def wider_config(config, dimensions):
    result = copy.deepcopy(config)
    layout = feature_layout(config)
    if layout["kind"] != "semantic_word_character_hash_v1":
        raise ValueError("This comparison supports the pinned v1 hybrid representation")
    semantic = config["encoder"]["dimension"]
    if type(dimensions) is not int or dimensions <= layout["dimensions"]:
        raise ValueError("Comparison width must exceed the baseline width")
    old = config["representation"]
    lexical = dimensions - semantic
    words = round(lexical * old["word_dimensions"] / (layout["dimensions"] - semantic))
    result["representation"].update(dimensions=dimensions, word_dimensions=words, character_dimensions=lexical - words)
    feature_layout(result)
    return result


def unique_features(text):
    text = " ".join(unicodedata.normalize("NFKC", text).casefold().split())
    words = WORD_PATTERN.findall(text)
    return ({"w1:" + token for token in words} | {"w2:" + a + "\x1f" + b for a, b in pairwise(words)},
            {"c" + str(size) + ":" + text[start:start + size] for size in (3, 4, 5)
             for start in range(max(0, len(text) - size + 1))})


def bucket_ids(features, dimensions):
    hashes = [int.from_bytes(hashlib.blake2b(f.encode("utf-8"), digest_size=8, person=b"TALG-news-v1").digest(), "little") & ((1 << 63) - 1) for f in features]
    return {value % dimensions for value in hashes}


def collisions(feature_sets, dimensions):
    unique_count = sum(len(features) for features in feature_sets)
    lost = sum(len(features) - len(bucket_ids(features, dimensions)) for features in feature_sets)
    corpus = set().union(*feature_sets)
    corpus_lost = len(corpus) - len(bucket_ids(corpus, dimensions))
    return {"within_article_unique_feature_occurrences": unique_count, "within_article_collisions": lost,
            "within_article_collision_fraction": lost / unique_count if unique_count else 0,
            "corpus_unique_features": len(corpus), "corpus_collisions": corpus_lost,
            "corpus_collision_fraction": corpus_lost / len(corpus) if corpus else 0}


def cosine_pairs(vectors, pairs):
    norms = np.sqrt(np.einsum("nd,nd->n", vectors, vectors, dtype=np.float64, optimize=False))
    values = []
    for start in range(0, len(pairs), 16):
        indices = pairs[start:start + 16]
        first, second = indices[:, 0], indices[:, 1]
        dots = np.einsum("nd,nd->n", vectors[first], vectors[second], dtype=np.float64, optimize=False)
        values.extend((dots / np.maximum(norms[first] * norms[second], 1e-30)).tolist())
    return np.asarray(values)


def compare(root, reference, symbol, dimensions=100000):
    root = Path(root).resolve()
    _, provenance = load_tensor(root, reference, symbol)
    source_file = Path(provenance["file"])
    source_folder = source_file.parent
    config = json.loads((source_folder / "model.json").read_text(encoding="utf-8-sig"))
    articles = json.loads((source_folder / (symbol + ".articles.json")).read_text(encoding="utf-8-sig"))
    with np.load(source_file, allow_pickle=False) as saved:
        baseline = saved["article_vectors"].copy()
        decay = saved["decay_weights"].copy()
        connections = saved["connection_weights"].copy()
        original = saved["news_tensor"].copy()
        ids = saved["information_ids"].copy()
        event_times = saved["event_times"].copy()
        available_at = saved["available_at"].copy()
        as_of = str(saved["as_of_utc"])
    if len(articles) < 2 or ids.tolist() != [a["id"] for a in articles]:
        raise ValueError("Need at least two aligned retained articles")
    wide_config = wider_config(config, dimensions)
    width = baseline.shape[1]
    texts = [config["input_template"].format(symbol=symbol, text=a["text"]) for a in articles]
    semantic = baseline[:, :config["encoder"]["dimension"]].copy()
    for row in semantic:
        normalize(row)
    class FrozenSemantic:
        def encode(self, requested):
            if requested != texts:
                raise ValueError("Comparison changed the semantic input order")
            return semantic.copy()
    times = {}
    at = time.perf_counter()
    rebuilt = ArticleEncoder(FrozenSemantic(), config).encode(texts)
    times[width] = time.perf_counter() - at
    baseline_error = float(np.max(np.abs(rebuilt - baseline)))
    if baseline_error > 1e-6:
        raise ValueError("Frozen baseline cannot be reproduced by the current feature implementation")
    del rebuilt
    at = time.perf_counter()
    wide = ArticleEncoder(FrozenSemantic(), wide_config).encode(texts)
    times[dimensions] = time.perf_counter() - at
    if not np.isfinite(wide).all():
        raise ValueError("Nonfinite wider representation")
    semantic_error = float(np.max(np.abs(wide[:, :semantic.shape[1]] - baseline[:, :semantic.shape[1]])))
    if semantic_error > 1e-6:
        raise ValueError("Widening changed the controlled semantic block")
    features = [unique_features(text) for text in texts]
    report = {"scope": "paired representation mechanics; predictive performance not evaluated",
              "profile_revision": reference, "source_run_id": provenance["run_id"], "symbol": symbol,
              "articles": len(articles), "frozen_as_of_utc": as_of, "created_at": utc(time.time()),
              "article_ids_sha256": digest(json.dumps(ids.tolist())),
              "source_npz_sha256": hashlib.sha256(source_file.read_bytes()).hexdigest(),
              "feature_source_sha256": hashlib.sha256(Path(__file__).with_name("news_features.py").read_bytes()).hexdigest(),
              "source_vector_reconstruction_max_error": baseline_error, "semantic_block_max_change": semantic_error,
              "controls": ["same article revisions and ordering", "same symbol and text", "same learned semantic vectors",
                           "same block scaling", "same connection weights", "same frozen decay weights and time", "same count denominator"],
              "timing_note": "Single sequential run per width, frozen semantic inputs; excludes neural inference. Not a latency benchmark.",
              "variants": {}, "decay_experiment": "not_started"}
    output = root / "work/symbol-news-tensor/width-comparisons" / (time.strftime("%Y%m%dT%H%M%SZ", time.gmtime()) + "-" + uuid.uuid4().hex[:8])
    output.mkdir(parents=True)
    for size, vectors, cfg in ((width, baseline, config), (dimensions, wide, wide_config)):
        effective = connections * decay
        value = np.einsum("n,nd->d", effective, vectors, dtype=np.float64, optimize=False) / len(vectors)
        if size == width:
            np.testing.assert_allclose(value, original, rtol=1e-12, atol=1e-12)
        file = output / f"{size}.npz"
        atomic_npz(file, news_tensor=value, article_vectors=vectors, connection_weights=connections,
                   decay_weights=decay, effective_weights=effective, information_ids=ids,
                   event_times=event_times, source_available_at=available_at,
                   available_at=np.maximum(available_at, time.time()), comparison_only=np.array(True),
                   built_at_utc=np.array(utc(time.time())), as_of_utc=np.array(as_of),
                   n=np.array(len(articles)), representation_version=np.array(digest(json.dumps(cfg, sort_keys=True))))
        atomic_json(output / f"{size}-model.json", cfg)
        layout = feature_layout(cfg)
        report["variants"][str(size)] = {"layout": layout, "lexical_build_seconds": times[size],
            "dense_article_vector_bytes": int(vectors.nbytes), "saved_npz_bytes": file.stat().st_size,
            "nonzero_fraction": float(np.count_nonzero(vectors) / vectors.size),
            "aggregate_norm": float(np.sqrt(np.sum(value * value))),
            "word_collisions": collisions([f[0] for f in features], cfg["representation"]["word_dimensions"]),
            "character_collisions": collisions([f[1] for f in features], cfg["representation"]["character_dimensions"])}
    pairs = np.column_stack(np.triu_indices(len(articles), 1))
    if len(pairs) > 2048:
        pairs = pairs[np.random.default_rng(20260926).choice(len(pairs), 2048, replace=False)]
    small_cos, big_cos = cosine_pairs(baseline, pairs), cosine_pairs(wide, pairs)
    difference = np.abs(big_cos - small_cos)
    first, second = small_cos - small_cos.mean(), big_cos - big_cos.mean()
    denominator = float(np.sqrt(np.sum(first * first) * np.sum(second * second)))
    report["pair_similarity"] = {"sampled_pairs": len(pairs), "seed": 20260926,
        "mean_absolute_cosine_change": float(difference.mean()), "maximum_absolute_cosine_change": float(difference.max()),
        "pearson_correlation": float(np.sum(first * second) / denominator) if denominator else None}
    atomic_json(output / "article-inputs.json", articles)
    atomic_npz(output / "pair-similarities.npz", article_indices=pairs, baseline_cosines=small_cos, wider_cosines=big_cos)
    atomic_json(output / "comparison.json", report)
    print(json.dumps({"folder": str(output), "report": report}, indent=2))
    return output, report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--revision", required=True)
    parser.add_argument("--symbol", required=True)
    parser.add_argument("--dimensions", type=int, default=100000)
    args = parser.parse_args()
    compare(args.root, args.revision, args.symbol, args.dimensions)


if __name__ == "__main__":
    main()

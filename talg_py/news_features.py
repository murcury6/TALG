"""Stable semantic and lexical features; lexical buckets are not learned parameters."""
from __future__ import annotations

import hashlib
import re
import unicodedata
from itertools import pairwise

import numpy as np

ALGORITHM = "semantic_word_character_hash_v1"
WORD_PATTERN = re.compile(r"[^\W_]+(?:[.'Ã¢â‚¬â„¢\-][^\W_]+)*|[%$Ã¢â€šÂ¬Ã‚Â£Ã‚Â¥]", re.UNICODE)


def feature_layout(config):
    semantic = config["encoder"]["dimension"]
    representation = config.get("representation")
    if type(semantic) is not int or semantic < 1:
        raise ValueError("Semantic dimension must be a positive integer")
    blocks = [{"name": "semantic", "start": 0, "stop": semantic, "dimensions": semantic}]
    if representation is None:
        return {"kind": "semantic_only", "dimensions": semantic, "blocks": blocks}
    if representation["kind"] != ALGORITHM:
        raise ValueError("Unsupported article representation")
    if representation["word_ngram_range"] != [1, 2] or representation["character_ngram_range"] != [3, 5]:
        raise ValueError("Version 1 uses word unigrams/bigrams and character 3/4/5-grams")
    if representation["hash"] != "blake2b_64_signed_v1" or representation["normalization"] != "l2_per_block_then_combined":
        raise ValueError("Unsupported hash or normalization contract")
    stop = semantic
    for name in ("word", "character"):
        size = representation[name + "_dimensions"]
        if type(size) is not int or size < 1:
            raise ValueError("Lexical block sizes must be positive integers")
        blocks.append({"name": name, "start": stop, "stop": stop + size, "dimensions": size})
        stop += size
    if representation["dimensions"] != stop:
        raise ValueError("Total feature dimension does not match declared blocks")
    return {"kind": ALGORITHM, "dimensions": stop, "blocks": blocks,
            "lexical_features": "signed hash buckets; collisions possible; not learned semantic coordinates",
            "normalization": representation["normalization"]}


def normalize(vector):
    # Elementwise reduction avoids the portable Windows runtime's optional BLAS loader.
    norm = float(np.sqrt(np.sum(vector * vector, dtype=np.float64)))
    if norm > 0:
        vector /= norm
    return vector


def add_feature(vector, feature):
    value = hashlib.blake2b(feature.encode("utf-8"), digest_size=8, person=b"TALG-news-v1").digest()
    bits = int.from_bytes(value, "little")
    vector[(bits & ((1 << 63) - 1)) % len(vector)] += -1 if bits >> 63 else 1


def lexical_features(text, word_dimensions, character_dimensions):
    text = " ".join(unicodedata.normalize("NFKC", text).casefold().split())
    words = WORD_PATTERN.findall(text)
    word = np.zeros(word_dimensions, dtype=np.float32)
    character = np.zeros(character_dimensions, dtype=np.float32)
    for token in words:
        add_feature(word, "w1:" + token)
    for first, second in pairwise(words):
        add_feature(word, "w2:" + first + "\x1f" + second)
    for size in (3, 4, 5):
        for start in range(max(0, len(text) - size + 1)):
            add_feature(character, "c" + str(size) + ":" + text[start:start + size])
    return normalize(word), normalize(character)


class ArticleEncoder:
    """One semantic inference plus full-input lexical features per article."""
    def __init__(self, semantic_encoder, config):
        self.semantic_encoder = semantic_encoder
        self.config = config
        self.layout = feature_layout(config)

    def encode(self, texts):
        semantic = np.asarray(self.semantic_encoder.encode(texts), dtype=np.float32)
        dimension = self.config["encoder"]["dimension"]
        if semantic.shape != (len(texts), dimension) or not np.isfinite(semantic).all():
            raise ValueError("Invalid semantic encoder output")
        representation = self.config.get("representation")
        if representation is None:
            return semantic
        result = np.zeros((len(texts), self.layout["dimensions"]), dtype=np.float32)
        blocks = self.layout["blocks"]
        for row, text in enumerate(texts):
            word, character = lexical_features(text, representation["word_dimensions"], representation["character_dimensions"])
            result[row, :dimension] = normalize(semantic[row].copy())
            result[row, blocks[1]["start"]:blocks[1]["stop"]] = word
            result[row, blocks[2]["start"]:blocks[2]["stop"]] = character
            normalize(result[row])
        return result

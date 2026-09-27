"""Pinned ONNX semantic encoder with complete supplied-text token coverage."""
from __future__ import annotations

import hashlib
from pathlib import Path

import numpy as np


def token_windows(prefix, body, max_tokens, cls_id, sep_id):
    capacity = max_tokens - len(prefix) - 2
    if capacity < 16:
        raise ValueError("Company context leaves insufficient article-token capacity")
    return [(start, min(start + capacity, len(body)),
             [cls_id, *prefix, *body[start:start + capacity], sep_id])
            for start in range(0, max(1, len(body)), capacity)]


def unit_rows(values):
    values = np.asarray(values, dtype=np.float32)
    norms = np.sqrt(np.sum(values * values, axis=-1, keepdims=True, dtype=np.float64))
    return (values / np.maximum(norms, 1e-12)).astype(np.float32)


class ChunkEncoder:
    def __init__(self, root: Path, config: dict):
        import onnxruntime as ort
        from tokenizers import Tokenizer
        if config["backend"] != "onnx_chunked_cls_v2" or config["pooling"] != "cls_token_weighted_chunk_mean":
            raise ValueError("Unsupported semantic encoder contract")
        self.config = config
        folder = root / config["directory"]
        for name in ("model_quantized.onnx", "tokenizer.json", "config.json"):
            expected = config["sha256"][name]
            if hashlib.sha256((folder / name).read_bytes()).hexdigest() != expected:
                raise ValueError("Encoder artifact checksum mismatch: " + name)
        options = ort.SessionOptions()
        options.intra_op_num_threads = config["cpu_threads"]
        options.inter_op_num_threads = 1
        self.session = ort.InferenceSession(str(folder / "model_quantized.onnx"), options,
                                           providers=["CPUExecutionProvider"])
        self.tokenizer = Tokenizer.from_file(str(folder / "tokenizer.json"))
        self.tokenizer.no_truncation()
        self.tokenizer.no_padding()
        self.dimension = config["dimension"]
        self.last_metadata = []
        self.last_chunks = []

    def encode(self, texts):
        result, self.last_metadata, self.last_chunks = [], [], []
        separator = self.config["chunk_prefix_separator"]
        for text in texts:
            prefix_text, found, body_text = text.partition(separator)
            if not found:
                raise ValueError("Missing company/article separator")
            prefix = self.tokenizer.encode(prefix_text + separator, add_special_tokens=False).ids
            body = self.tokenizer.encode(body_text, add_special_tokens=False).ids
            windows = token_windows(prefix, body, self.config["max_tokens"],
                                    self.tokenizer.token_to_id("[CLS]"), self.tokenizer.token_to_id("[SEP]"))
            chunks = []
            for start in range(0, len(windows), self.config["chunk_batch_size"]):
                batch = windows[start:start + self.config["chunk_batch_size"]]
                length = max(len(w[2]) for w in batch)
                ids = np.full((len(batch), length), self.tokenizer.token_to_id("[PAD]"), dtype=np.int64)
                mask = np.zeros_like(ids)
                for row, (_, _, tokens) in enumerate(batch):
                    ids[row, :len(tokens)] = tokens
                    mask[row, :len(tokens)] = 1
                feeds = {"input_ids": ids, "attention_mask": mask, "token_type_ids": np.zeros_like(ids)}
                hidden = self.session.run(None, {i.name: feeds[i.name] for i in self.session.get_inputs()})[0]
                if hidden.ndim != 3 or hidden.shape[-1] != self.dimension:
                    raise ValueError("Unexpected token embedding output")
                chunks.extend(unit_rows(hidden[:, 0]))
            chunks = np.asarray(chunks, dtype=np.float32)
            lengths = np.array([max(1, end - start) for start, end, _ in windows])
            pooled = np.sum(chunks * lengths[:, None], axis=0) / lengths.sum()
            result.append(unit_rows(pooled))
            self.last_chunks.append(chunks)
            self.last_metadata.append({"algorithm": "nonoverlapping_tokens_repeat_company_context_v2",
                "article_tokens": len(body), "context_tokens": len(prefix), "chunk_count": len(windows),
                "covered_article_tokens": sum(end - start for start, end, _ in windows),
                "truncated_tokens": 0, "token_ranges": [[a, b] for a, b, _ in windows],
                "input_sha256": hashlib.sha256(text.encode()).hexdigest()})
        values = np.asarray(result, dtype=np.float32).reshape(len(texts), self.dimension)
        if not np.isfinite(values).all():
            raise ValueError("Nonfinite semantic vectors")
        return values

from __future__ import annotations

from datetime import datetime, timezone

import httpx
import pytest

from talg_py.llama_features import annotate_article

ARTICLE = {
    "article_id": "synthetic-1",
    "symbol": "AAPL",
    "published_at_utc": "2026-09-21T12:00:00Z",
    "headline": "Synthetic company announces a product",
    "body": "This is a fictional test article.",
    "source_url": "https://example.invalid/synthetic-1",
}


def test_llama_feature_keeps_provenance_and_availability() -> None:
    def reply(request: httpx.Request) -> httpx.Response:
        assert request.headers["Authorization"] == "Bearer test-key"
        assert request.read().decode().count("Synthetic company") == 1
        return httpx.Response(200, json={"completion_message": {"content": {
            "type": "text", "text": '{"score": 0.25, "confidence": 0.6}'
        }}})

    with httpx.Client(transport=httpx.MockTransport(reply)) as client:
        feature = annotate_article(
            ARTICLE, client=client, api_key="test-key", model="test-model",
            url="https://example.invalid/v1/chat/completions",
            available_at=datetime(2026, 9, 21, 12, 5, tzinfo=timezone.utc),
        )
    assert feature["tone_score"] == 0.25
    assert feature["available_at_utc"] == "2026-09-21T12:05:00Z"
    assert feature["source_url"] == ARTICLE["source_url"]


def test_llama_feature_fails_closed_on_bad_score() -> None:
    def reply(_: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"completion_message": {"content": {
            "type": "text", "text": '{"score": 2, "confidence": 0.9}'
        }}})

    with (
        httpx.Client(transport=httpx.MockTransport(reply)) as client,
        pytest.raises(ValueError, match="outside"),
    ):
        annotate_article(
            ARTICLE, client=client, api_key="test-key", model="test-model",
            url="https://example.invalid/v1/chat/completions",
            available_at=datetime(2026, 9, 21, 12, 5, tzinfo=timezone.utc),
        )

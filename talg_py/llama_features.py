"""Use the native Meta Llama API to annotate supplied, timestamped articles."""

from __future__ import annotations

import argparse
import csv
import json
import math
import os
import re
from datetime import datetime, timezone
from pathlib import Path

import httpx
from dotenv import load_dotenv

INPUT_FIELDS = ("article_id", "symbol", "published_at_utc", "headline", "body", "source_url")
OUTPUT_FIELDS = (
    "article_id", "symbol", "published_at_utc", "available_at_utc", "source_url",
    "llama_model", "tone_score", "self_reported_confidence",
)
SYMBOL = re.compile(r"[A-Z][A-Z0-9.-]{0,9}\Z")

SYSTEM_PROMPT = (
    "You are a financial-text annotation service, not an investment adviser. "
    "Use only the supplied headline and body; do not infer facts or browse. "
    "Estimate directional tone for the named stock over the next trading day. "
    "Reply with exactly one JSON object containing numeric keys score and confidence. "
    "score must be between -1 (negative) and 1 (positive); confidence must be between 0 and 1. "
    "If the text is irrelevant or ambiguous, use score 0 and low confidence."
)


def parse_utc(value: str) -> datetime:
    timestamp = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if timestamp.tzinfo is None:
        raise ValueError("Article timestamp must include a timezone")
    return timestamp.astimezone(timezone.utc)


def annotate_article(
    article: dict[str, str], *, client: httpx.Client, api_key: str,
    model: str, url: str, available_at: datetime | None = None,
) -> dict[str, object]:
    if not api_key or not model:
        raise ValueError("LLAMA_API_KEY and LLAMA_MODEL are required")
    if not all(field in article for field in INPUT_FIELDS):
        raise ValueError("Article input is missing a required field")
    if not article["article_id"] or not SYMBOL.fullmatch(article["symbol"]):
        raise ValueError("Article ID and a plain uppercase symbol are required")
    published = parse_utc(article["published_at_utc"])
    available = available_at or datetime.now(timezone.utc)
    if available.tzinfo is None or available < published:
        raise ValueError("Feature availability must be timezone-aware and after publication")

    request = {
        "model": model,
        "temperature": 0,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": (
                f"Symbol: {article['symbol']}\n"
                f"Headline: {article['headline']}\n"
                f"Body: {article['body']}"
            )},
        ],
    }
    response = client.post(url, headers={"Authorization": f"Bearer {api_key}"}, json=request)
    response.raise_for_status()
    payload = response.json()
    try:
        content = payload["completion_message"]["content"]["text"]
        values = json.loads(content)
        score = float(values["score"])
        confidence = float(values["confidence"])
    except (KeyError, IndexError, TypeError, ValueError, json.JSONDecodeError) as exc:
        raise ValueError("Llama response did not match the feature contract") from exc
    if not math.isfinite(score) or not -1 <= score <= 1:
        raise ValueError("Llama tone score is outside [-1, 1]")
    if not math.isfinite(confidence) or not 0 <= confidence <= 1:
        raise ValueError("Llama confidence is outside [0, 1]")
    return {
        "article_id": article["article_id"], "symbol": article["symbol"],
        "published_at_utc": published.isoformat().replace("+00:00", "Z"),
        "available_at_utc": available.astimezone(timezone.utc).isoformat().replace("+00:00", "Z"),
        "source_url": article["source_url"], "llama_model": model,
        "tone_score": score, "self_reported_confidence": confidence,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("articles", type=Path, help="CSV with the documented input columns")
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    load_dotenv(Path.cwd() / ".env")
    api_key = os.getenv("LLAMA_API_KEY", "")
    model = os.getenv("LLAMA_MODEL", "")
    url = os.getenv("LLAMA_CHAT_COMPLETIONS_URL", "https://api.llama.com/v1/chat/completions")
    with args.articles.open(newline="", encoding="utf-8") as source:
        reader = csv.DictReader(source)
        if reader.fieldnames != list(INPUT_FIELDS):
            raise ValueError("Unexpected article CSV header")
        articles = list(reader)
    if not articles:
        raise ValueError("No articles supplied")
    with httpx.Client(timeout=45.0) as client:
        features = [annotate_article(article, client=client, api_key=api_key, model=model, url=url)
                    for article in articles]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", newline="", encoding="utf-8") as output:
        writer = csv.DictWriter(output, fieldnames=OUTPUT_FIELDS)
        writer.writeheader()
        writer.writerows(features)
    print(f"Wrote {len(features)} text features to {args.output}")


if __name__ == "__main__":
    main()

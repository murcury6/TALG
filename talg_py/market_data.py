"""Fetch daily stock bars from Alpaca; no trading endpoints are used."""

from __future__ import annotations

import argparse
import csv
import math
import os
import re
from datetime import date, datetime, timezone
from pathlib import Path

import httpx
from dotenv import load_dotenv

FIELDS = ("symbol", "timestamp_utc", "open", "high", "low", "close", "volume", "feed", "adjustment", "source")
SYMBOL = re.compile(r"[A-Z][A-Z0-9.-]{0,9}\Z")


def fetch_daily_bars(
    symbol: str,
    start: str,
    end: str,
    *,
    client: httpx.Client,
    api_key: str,
    api_secret: str,
    data_url: str = "https://data.alpaca.markets",
    feed: str = "iex",
) -> list[dict[str, object]]:
    """Return validated bars for the inclusive date range, sorted by UTC timestamp."""
    if not SYMBOL.fullmatch(symbol):
        raise ValueError("Symbol must be a plain uppercase stock ticker")
    if date.fromisoformat(start) >= date.fromisoformat(end):
        raise ValueError("start must precede end")
    if feed not in {"iex", "sip"}:
        raise ValueError("feed must be iex or sip")
    if not api_key or not api_secret:
        raise ValueError("Alpaca market-data credentials are required")

    url = f"{data_url.rstrip('/')}/v2/stocks/{symbol}/bars"
    headers = {"APCA-API-KEY-ID": api_key, "APCA-API-SECRET-KEY": api_secret}
    params: dict[str, str] = {
        "start": start,
        "end": end,
        "timeframe": "1Day",
        "feed": feed,
        "adjustment": "raw",
        "sort": "asc",
        "limit": "10000",
    }
    bars: list[dict[str, object]] = []
    seen_tokens: set[str] = set()

    while True:
        response = client.get(url, headers=headers, params=params)
        response.raise_for_status()
        payload = response.json()
        if not isinstance(payload, dict) or not isinstance(payload.get("bars"), list):
            raise TypeError("Unexpected Alpaca bars response")
        for raw in payload["bars"]:
            if not isinstance(raw, dict):
                raise TypeError("Unexpected bar payload")
            stamp = datetime.fromisoformat(str(raw["t"]).replace("Z", "+00:00"))
            if stamp.tzinfo is None:
                raise ValueError("Bar timestamp is missing its timezone")
            stamp = stamp.astimezone(timezone.utc)
            open_, high, low, close = (float(raw[key]) for key in ("o", "h", "l", "c"))
            volume = int(raw["v"])
            if not all(map(math.isfinite, (open_, high, low, close))) or low <= 0:
                raise ValueError("Bar prices must be positive and finite")
            if high < max(open_, close, low) or low > min(open_, close) or volume < 0:
                raise ValueError("Inconsistent OHLCV bar")
            bars.append({
                "symbol": symbol,
                "timestamp_utc": stamp.isoformat().replace("+00:00", "Z"),
                "open": open_, "high": high, "low": low, "close": close,
                "volume": volume, "feed": feed, "adjustment": "raw", "source": "alpaca",
            })
        token = payload.get("next_page_token")
        if not token:
            break
        if not isinstance(token, str) or token in seen_tokens:
            raise ValueError("Invalid or repeated Alpaca page token")
        seen_tokens.add(token)
        params["page_token"] = token

    bars.sort(key=lambda bar: str(bar["timestamp_utc"]))
    timestamps = [str(bar["timestamp_utc"]) for bar in bars]
    if len(timestamps) != len(set(timestamps)):
        raise ValueError("Duplicate bar timestamp")
    return bars


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("symbol")
    parser.add_argument("start", help="inclusive YYYY-MM-DD")
    parser.add_argument("end", help="inclusive YYYY-MM-DD")
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    load_dotenv(Path.cwd() / ".env")
    with httpx.Client(timeout=30.0) as client:
        bars = fetch_daily_bars(
            args.symbol, args.start, args.end, client=client,
            api_key=os.getenv("APCA_API_KEY_ID", ""),
            api_secret=os.getenv("APCA_API_SECRET_KEY", ""),
            data_url=os.getenv("APCA_API_DATA_URL", "https://data.alpaca.markets"),
            feed=os.getenv("APCA_API_DATA_FEED", "iex"),
        )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", newline="", encoding="utf-8") as output:
        writer = csv.DictWriter(output, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(bars)
    print(f"Wrote {len(bars)} bars to {args.output}")


if __name__ == "__main__":
    main()

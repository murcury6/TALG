"""Read-only Alpaca adjusted daily close collection for news outcome labels."""
from __future__ import annotations

import argparse
import os
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

import httpx
from dotenv import load_dotenv

from .news_tensor import atomic_json, utc
from .symbol_news_tensor import symbol_name


def fetch(client, symbols, start, end, headers, feed="iex", now=None):
    now = datetime.now(timezone.utc) if now is None else now
    if feed not in {"iex", "sip"}:
        raise ValueError("Use iex or sip feed")
    begin, finish = datetime.fromisoformat(start).date(), datetime.fromisoformat(end).date()
    if begin > finish:
        raise ValueError("Start must not follow end")
    calendar_response = client.get("https://paper-api.alpaca.markets/v2/calendar", headers=headers,
                                   params={"start": start, "end": end})
    calendar_response.raise_for_status()
    calendar = {}
    eastern = ZoneInfo("America/New_York")
    for day in calendar_response.json():
        close = datetime.fromisoformat(day["date"] + "T" + day["close"]).replace(tzinfo=eastern).astimezone(timezone.utc)
        if close <= now:
            calendar[day["date"]] = close
    results = []
    for symbol in sorted({symbol_name(s) for s in symbols}):
        params = {"timeframe": "1Min", "start": start, "end": (finish + timedelta(days=1)).isoformat(),
                  "adjustment": "split,dividend", "feed": feed, "sort": "asc", "limit": 10000}
        tokens = set()
        while True:
            response = client.get(f"https://data.alpaca.markets/v2/stocks/{symbol}/bars", headers=headers, params=params)
            response.raise_for_status()
            payload = response.json()
            for bar in payload["bars"]:
                bar_start = datetime.fromisoformat(bar["t"].replace("Z", "+00:00")).astimezone(timezone.utc)
                date = bar_start.astimezone(eastern).date().isoformat()
                if date in calendar and bar_start + timedelta(minutes=1) == calendar[date]:
                    results.append({"symbol": symbol, "session": date, "close_time": calendar[date].isoformat(),
                        "adjusted_close": bar["c"], "adjustment": "split_and_dividend", "feed": feed,
                        "source": "alpaca_final_regular_session_minute_with_market_calendar", "retrieved_at": utc(now.timestamp()),
                        "limitation": "Final regular-session minute close; IEX is venue-limited, not a consolidated official closing auction price"})
            token = payload.get("next_page_token")
            if not token:
                break
            if token in tokens:
                raise ValueError("Repeated price pagination token")
            tokens.add(token)
            params["page_token"] = token
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--symbol", action="append", required=True)
    parser.add_argument("--benchmark", default="SPY")
    parser.add_argument("--start", required=True)
    parser.add_argument("--end", required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--feed", choices=["iex", "sip"], default="iex")
    args = parser.parse_args()
    load_dotenv(Path.cwd() / ".env")
    key, secret = os.getenv("APCA_API_KEY_ID"), os.getenv("APCA_API_SECRET_KEY")
    if not key or not secret:
        parser.error("Set APCA_API_KEY_ID and APCA_API_SECRET_KEY for read-only market-data access")
    with httpx.Client(timeout=60) as client:
        rows = fetch(client, [*args.symbol, args.benchmark], args.start, args.end,
                     {"APCA-API-KEY-ID": key, "APCA-API-SECRET-KEY": secret}, args.feed)
    atomic_json(args.out, rows)
    print(f"Saved {len(rows)} adjusted session closes")


if __name__ == "__main__":
    main()

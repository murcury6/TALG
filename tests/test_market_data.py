from __future__ import annotations

import httpx
import pytest

from talg_py.market_data import fetch_daily_bars


def test_fetches_and_validates_paged_bars() -> None:
    seen: list[httpx.Request] = []

    def reply(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        if "page_token=next" in str(request.url):
            return httpx.Response(200, json={"bars": [
                {"t": "2026-09-22T20:00:00Z", "o": 101, "h": 103, "l": 100, "c": 102, "v": 1200}
            ], "next_page_token": None})
        return httpx.Response(200, json={"bars": [
            {"t": "2026-09-21T20:00:00Z", "o": 100, "h": 102, "l": 99, "c": 101, "v": 1000}
        ], "next_page_token": "next"})

    with httpx.Client(transport=httpx.MockTransport(reply)) as client:
        bars = fetch_daily_bars("AAPL", "2026-09-21", "2026-09-23", client=client,
                                api_key="test-key", api_secret="test-secret")

    assert len(bars) == 2
    assert bars[0]["close"] == 101.0
    assert bars[1]["timestamp_utc"] == "2026-09-22T20:00:00Z"
    assert seen[0].headers["APCA-API-KEY-ID"] == "test-key"
    assert seen[0].url.params["feed"] == "iex"


def test_rejects_inconsistent_ohlc() -> None:
    def reply(_: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"bars": [
            {"t": "2026-09-21T20:00:00Z", "o": 100, "h": 99, "l": 98, "c": 101, "v": 100}
        ]})

    with (
        httpx.Client(transport=httpx.MockTransport(reply)) as client,
        pytest.raises(ValueError, match="Inconsistent"),
    ):
        fetch_daily_bars("AAPL", "2026-09-21", "2026-09-23", client=client,
                         api_key="test-key", api_secret="test-secret")

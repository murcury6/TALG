# Free News / Info collection

The **NEWS** rail button opens world events, markets, policy, company news, and SEC financials. News is collected independently of the watchlist and trading rules. RSS collection needs no API key. The all-symbol live news socket uses the existing saved Alpaca market-data credentials with Alpaca only, and makes no trading calls.

The expanded registry currently contains **364 RSS/Atom feeds**, including two discovery feeds for each of **77 stocks/ETFs**, including direct publishers across North America, Europe, Asia, Africa, the Middle East and Australia; defense, energy and shipping publications; official economic/health releases; and Google News discovery feeds. Google results carry their attributed publisher and their discovery feed separately. Publisher attribution does not imply independent reporting or verification. The source registry is extensible through **Sources & coverage → Edit feeds** (up to 500 feeds).

## Collecting and reading

News uses one 30-pixel utility bar across the top: **News** menu, labeled **Search news**, **Filters**, **Refresh news**, **Read full article**, and the matching article count. Everything underneath is the resizable headline list and article preview; there is no title block, tab strip, filter row or footer. Headlines wrap, with source and local publication time underneath. The first result opens automatically, and updates preserve the selected article. Search and filters give an explicit empty state when no stories match.

**Filters** opens a popover with labeled publication-date, source, category, stock/ETF and topic controls plus **Reset filters**. A count on the button identifies active non-default filters even when the popover is closed. **News** opens source management, company financials, the saved collection, selected-article source details, and the automatic-update setting. Collection progress is available in that menu and on the Refresh news tooltip. Source management and financials open separate windows so the news workspace stays available.

Open NEWS once to start collection. TALG checks enabled feeds immediately and every minute while the application remains open, including when another tab is selected. Uncheck the update control to stop periodic collection; **Refresh** still works. The collector uses six concurrent requests, bounded response sizes, timeouts, and independent error handling. One blocked publisher does not stop other feeds. The standalone news collector can also run while the TALG window is closed. RSS often exposes only a recent window of stories.

Headlines default to all sources/categories and the last seven days. Search requires all entered words to occur in the headline, publisher, or feed excerpt. Use the stock selector to retrieve dedicated ticker-feed results even when the symbol is absent from the headline. **Sources & coverage → Add stock** adds a Yahoo Finance ticker feed to the readable source registry. The `symbol` field records provider-feed association, not independently verified company relevance; related-market stories can appear. Topic filters use explicit keywords, not an inferred trading impact. **All indexed** includes the whole browsing index. Date filters use the first collection time for undated entries. Undated entries and publisher timestamps more than five minutes beyond the latest observation sort after normally dated entries. Future publisher timestamps are retained but sorting caps them at the latest observation; the list labels them “date uncertain” and **News → Source details** explains the discrepancy. A revised publication date later than first collection is allowed when it precedes the latest observation.

The publisher's headline and feed-provided excerpt are displayed as plain text with the original link. **Open article** opens that link in the browser (Google-discovered links may route through Google). The app does not download/pay for full articles, bypass paywalls, or claim publisher statements are verified facts. Some linked articles require a subscription even though their feed is free. RSS use is intended for this personal research reader; the publisher's terms still govern reuse and redistribution.

Same-publisher identical URLs are collapsed in the visible table; versions and source attribution remain in the collection. Stories with different links or publishers are retained, including syndicated copies. Story count is not a corroboration count. Google News adds breadth but also inherits Google's editorial/algorithmic selection. The list does not guarantee neutrality or exhaustive coverage.

**Sources & coverage** shows request status, last successful retrieval, item count for the most recent attempt, and newest publication date independently. A working feed can still be stale. HTTP failures and empty feeds are shown explicitly. Previously stored items remain when a source fails. Disabling a source hides its items but does not erase collection files.

## File collection on the drive

```text
data/news/
  sources.json                  complete configured feed registry
  collection-status.json        latest check results, including failures
  2026-09-23/                   UTC collection date
    bbc-world.jsonl            normalized items and revisions, one JSON record per line
    bbc-world.xml              latest successful raw feed snapshot for this day
    aljazeera.jsonl
    aljazeera.xml
    ...
data/financials/
  company-tickers.json          SEC directory cache
  AAPL/
    <UTC timestamp>-companyfacts.json
    <UTC timestamp>-normalized.json
work/news/
  articles.json                 fast browsing index and feed health
  sources.json                  optional user-edited registry override
```

**Open collection** opens `data/news` in File Explorer. The permanent `data/news` and `data/financials` collections are never automatically deleted. Each news record has a content fingerprint, UTC `collectedAt`, the complete source descriptor, and an `article` with source ID, attributed publisher, category, headline, URL, publisher-provided excerpt, publication date (blank when unknown), and observation times. Identical repeated polls do not add duplicate rows within a day. A changed headline, excerpt or publication time creates another record; the same item may recur on later days. Original XML is a daily latest snapshot; JSONL preserves the collected normalized revisions. Daily JSONL and raw XML writes replace files through temporary files. No trading credentials or SEC contact email are saved in these records.

The browsing index is capped at 20,000 items and 30 days since first observation; this limit does **not** remove the permanent archive. Older records remain accessible as JSONL files, rather than through the current tab's search. Feed parsing is limited to 250 entries per response and 4 MB of feed content; this is a bounded personal reader rather than an exhaustive global news archive. Sources may provide only titles or excerpts. Missing content is not generated.

Read the collection with Python, for example:

```python
import json
from pathlib import Path

for file in Path("data/news").glob("*/*.jsonl"):
    for line in file.read_text(encoding="utf-8").splitlines():
        record = json.loads(line)
        article = record["article"]
        print(record["collectedAt"], article["publisher"], article["title"])
```

## Company financials

The financials tab looks up a ticker through the SEC company directory and downloads free company-facts JSON directly from `data.sec.gov`. Enter a contact email for SEC automated-request identification; no API key is needed. The email is sent in the SEC request header and not persisted. Access can fail due to SEC network restrictions; the app reports that error without inventing data.

Both raw and normalized timestamped files are retained. The table includes supported US-GAAP revenue, net income, operating income, diluted EPS, operating cash flow, assets, liabilities and cash tags. It distinguishes roughly three-month, annual and point-in-time observations, excludes year-to-date totals, and retains original units. It selects the latest filed observation for each metric/start/end/unit; these displayed restatements must not be treated as information available at earlier dates. Open the linked filing or consult the raw JSON for history. Q4 is not derived, and custom tags, IFRS-only reports and segment revenue may not be covered. Empty metrics remain absent.

## Verification

```powershell
& .\.tools\pixi\pixi.exe run mvn verify
# Explicit live check: contacts public feeds and saves the real collection.
& .\.tools\pixi\pixi.exe run mvn '-Dtest=NewsLiveTest' '-Dtalg.liveNews=true' test
# Optional offscreen UI renders using already-collected data.
& .\.tools\pixi\pixi.exe run mvn '-Dtest=NewsPanelRenderTest' '-Dtalg.renderNews=true' test
```

Ordinary tests stay offline. The news unit tests cover RSS/Atom/RDF parsing, time zones, unsafe XML, attribution, revisions, duplicate polls, persistence, and separation between archive retention and index limits. The SEC test checks that a six-month cash-flow total is not labeled quarterly and that later restatements win.

Source references: [SEC APIs](https://www.sec.gov/search-filings/edgar-application-programming-interfaces), [Federal Reserve feeds](https://www.federalreserve.gov/feeds/feeds.htm), [PBS feeds](https://www.pbs.org/newshour/about/pbs-news-rss-feeds), [Guardian feeds](https://www.theguardian.com/help/feeds). The exact configured URLs are in `src/main/resources/news-sources.json`.

## Standalone collector

Run `io.github.murcury6.talg.NewsCollector` with the built jar on the classpath for one complete collection, or append `--watch` to target a new RSS pass every minute. The RSS collector needs no credentials; the live socket uses the saved Alpaca connection for news data only. It never submits trades. The running background collector is independent of the trading app and exits on process termination or Windows shutdown; it is not registered for automatic Windows startup.

`work/news/collector-status.json` records the last completed pass, PID, configured feeds, responding feeds and ticker count. `collector-output.log` and `collector-error.log` capture the launched process. Stop that PID to stop standalone collection. Only one standalone collector can run per workspace. The updated app and collector serialize collection/index writes through `work/news/collection.lock`. Close an older TALG build yourself and use `tools/start-news-desktop.ps1` to load the updated News tab.

Stock feeds retain provider selection and do not imply exhaustive or unbiased coverage. Raw feeds and daily JSONL include the source symbol. A saved `work/news/sources.json` overrides bundled defaults; disabled sources stay disabled.

## Live push delivery (September 26 update)

The background collector now authenticates to Alpaca's `wss://stream.data.alpaca.markets/v1beta1/news` endpoint and subscribes to `news: ["*"]`. This is all available news symbols, not a 77-ticker restriction. The 77-ticker list applies to extra RSS discovery. Alpaca confirms the subscription before the status can say SUBSCRIBED. Incoming stories and revisions are immediately appended to `data/news-stream/<UTC date>.jsonl`, including original provider, provider timestamps, receive timestamp, ID, and symbol tags. The normalized live index is `work/news/stream-articles.json`. The updated News UI checks that local index every two seconds; ingestion does not wait for the UI or an RSS pass. No automatic trades are triggered by news.

`work/news/stream-status.json` reports connection/subscription state, heartbeat, received-article count for the current process, and latest article time. A subscribed connection with zero articles means the subscription is established but no new news event has arrived in this process. Ping/pong detects a silent connection; reconnects use increasing delays up to 60 seconds. There is currently no REST gap backfill, so disconnected periods can miss events. Raw event archives remain on disk.

`work/news/collector-config.json` sets `rssIntervalSeconds` (currently 60) and `streamEnabled`. RSS cycles target one minute; a slow cycle may take longer. Conditional ETag/Last-Modified requests reduce unchanged payloads, and failing feeds back off up to one hour, honoring longer Retry-After responses. RSS freshness is still limited by each publisher's release/cache cadence. RSS is not represented as a WebSocket. The source registry records unavailable feeds and results separately.

Runtime files are isolated under the path in `work/news/runtime.json`, so the collector can be upgraded without replacing the jar loaded by the desktop. To load the updated desktop controls manually after closing the older TALG window, run `tools/start-news-desktop.ps1`. The background collector continues without opening or controlling any windows. Turning off the desktop RSS checkbox does not stop the independent collector.

Protocol references: [Alpaca real-time news](https://docs.alpaca.markets/us/docs/streaming-real-time-news), [Alpaca WebSocket authentication and subscription](https://docs.alpaca.markets/us/docs/streaming-market-data). Alpaca's currently documented news provider is Benzinga; [historical news data](https://docs.alpaca.markets/us/docs/historical-news-data). The broad RSS registry complements this stream; it does not make all publishers stream in real time.


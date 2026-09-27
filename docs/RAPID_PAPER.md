# Rapid paper basket and model bricks

TRADE → Paper strategy runs an explicitly armed paper session. Defaults: AAPL, MSFT, NVDA, AMZN, GOOGL, META, TSLA, AMD, AVGO, NFLX, INTC, MU, PLTR, UBER, JPM, BAC, XOM, CVX, WMT and DIS. It scans every ten seconds from **04:00–20:00 Eastern**, including premarket and after-hours. Defaults retain one share per stock, five positions including pending entries, and at most $1,000 per entry ($5,000 entry exposure). Daily entry and per-stock entry caps are now **0 = no count cap**, so the former 200-entry stop no longer ends the run. A rolling limit of 12 submissions/minute reserves five slots for exits; daily loss and exposure limits still apply.

## Easily editable hours and limits

### Bid-driven revision (September 24)

**Open, language-based rules:** `signal.bidRules.entryExpression` and `exitExpression` are executable strings in the same bounded expression language as the statistical strategy script. The Bid Rules tab exposes the exact expressions, and its number controls supply named parameters. For example:

```text
bid_change_bps >= min_bid_rise_bps and bid_imbalance >= min_bid_imbalance
bid_change_bps <= -exit_bid_fall_bps or (has_sizes and bid_imbalance <= exit_bid_imbalance)
```

These are defaults, not compulsory Java trading conditions. Replace the expressions to change the bid strategy without recompiling. Supported fields and parameters appear in the UI; unknown fields and invalid grammar are rejected before saving/arming. Forms preserve custom expressions when parameters change. Bid entries also pass the independent statistical buy expression; bid exits join statistical sells and configured price/risk exits. The runtime retains documented data-integrity and paper-execution guards; those are not profit signals. The existing strategy is unchanged by this migration—its previous conditions are now explicit expressions in the model file and frozen session snapshot. Runtime/source and durable architecture requirements are in `src/main/java/` and `AGENTS.md`.

The selected configuration now enables **Model bricks → BID RULES** and disables timed holding exits (`holdSeconds: 0`). The five-second interval refreshes IEX snapshots; it is not a trade trigger and this implementation is not a streaming or full-depth order-book system. Every bid-change decision compares two fresh, strictly ordered observed quote timestamps. Identical quotes, duplicates, out-of-order observations and first observations after a stale gap cannot create entry signals. Displayed quote-size fields come directly from Alpaca's `bs` and `as` values; missing/nonpositive sizes block new entries.

Entry requires a bid rise of at least 0.1 basis point, displayed size imbalance `(bidSize - askSize) / (bidSize + askSize)` of at least 0.10, and the existing statistical buy rule plus spread/risk/exposure checks. The size ratio is unit-independent. These are editable experimental thresholds, not trained or validated order-book coefficients. The original minute-bar regression remains a separate statistical filter.

Exit triggers include a bid fall of at least 1 basis point, size imbalance of -0.25 or less, a statistical sell signal accompanying a changed quote, or the bid reaching the stop/trailing stop/target. Timed holding exits are disabled in bid mode. Daily-loss, user-stop, session-close and pending-order timeout controls remain independent operational safeguards. A cooldown or refreshed minute bar alone cannot trigger an entry; a new qualifying quote change is required after the cooldown. Multiple qualifying quotes within one minute can produce independent entries after prior positions close.

Orders still use marketable limit prices (buy at observed ask plus one cent, sell at observed bid minus one cent), subject to configured bounds; bid-driven signal logic does not mean passive bid-only order pricing. Pending buys reserve full exposure and the submission-rate ceiling still applies. `QUOTE_DECISION` and `SUBMIT_INTENT` events store observed prices, sizes, movement and reasons alongside fills, preserving the file collection.

The September 24 loss stop remains latched even if liquidation later improves marked P&L; re-arming that stopped day is refused. The revised configuration is scheduled for September 25. The free feed covers IEX's top of book only, as described in [Alpaca's market-data FAQ](https://docs.alpaca.markets/us/docs/market-data-faq) and [quote field documentation](https://docs.alpaca.markets/us/docs/real-time-stock-pricing-data).

### Fast paper preset (September 24)

The saved experiment now uses `RapidPaperModel.fastPaper()` settings: five-second scans, ten positions, up to $5,000 per entry and $50,000 combined entry exposure, with a 100-share ceiling. Quantity is the whole-share floor of the available dollar budget divided by the buy limit, further limited by shares, buying power and remaining exposure. Pending buys reserve their full limit notional before another entry can be submitted; existing positions are marked conservatively at the greater of entry and fresh ask. Price increases can take existing holdings above the exposure threshold, which blocks additional entries rather than promising a fixed market-value ceiling.

The preset buys the top half of eligible statistical forecasts and exits below the 35th percentile, with a 60-second maximum hold and ten-second re-entry cooldown. Capacity-blocked signals can be reconsidered on subsequent scans of the same bar, but an accepted or uncertain entry consumes that stock's bar. This is still a one-minute-feature model, not high-frequency trading or evidence of an edge.

Up to 24 submissions per rolling minute include both buys and sells, with ten reserved for exits. Pending-order polling is at most every ten seconds during normal operation; stop and window-close reconciliation take priority. The adapter caches the calendar for one minute and the panel reuses its broker client. These controls reduce API traffic under [Alpaca's documented 200 trading requests/minute limit](https://alpaca.markets/support/usage-limit-api-calls); scans, account checks and order queries also consume requests, so 24 is a ceiling, not a promised throughput. Other clients sharing the account can still cause throttling.

The daily estimated marked loss trigger is $500 for this larger PAPER experiment; today's prior P&L remains included when re-arming. It is an exit trigger, not a guaranteed loss ceiling. The original conservative `defaults()` remains available for new configurations; the fast preset is explicitly selected in this account's saved configuration.

**Model bricks → SPEED & SIZE** exposes entry dollars, total exposure, share ceiling, holding time, cooldown and submission rate as ordinary form controls. HOURS & LIMITS controls scans, positions and loss trigger. The forms write separate sizing/exits/execution/portfolio bricks and synchronize the script's `qty` ceiling. The remaining EXITS and EXECUTION tabs edit risk, spread and chase settings without duplicating form values.

The live view and session files now record filled shares and bought-plus-sold dollar turnover. Those counters start with this update (earlier fills remain in the event files), count terminal confirmed fills once, survive restart and same-day re-arming, and reset on a new trading day. Turnover is not profit. Multi-share fill/partial-exit reconciliation sells only the confirmed remaining quantity; the cost estimate scales the $0.02/share term with quantity.

Open **TRADE → Paper strategy → Model bricks → HOURS & LIMITS**. The form has ordinary time fields, checkboxes and number controls for start/end time, extended-hours eligibility, repeat trading days, scan interval, close-position buffer, daily/per-stock entry caps, maximum positions and daily loss trigger. No JSON editing is required for these settings. Press **Validate & Save Bricks**, then arm the session. Stop and flatten an existing active session before editing; the stop button also disables automatic repeat.

The schedule uses `America/New_York` so daylight saving changes are automatic. Broker calendar dates exclude weekends and holidays. On early-close days, the regular close comes from the calendar and the extended session ends four hours later (normally 17:00). The configured window is clipped to eligible hours. Disabling extended hours restricts execution to the broker's regular session even if the typed start/end times are wider.

By default, new entries end at 19:55, leaving five minutes for closing orders before 20:00. The close-position buffer is editable from one to thirty minutes. At the end, outstanding orders are canceled and reconciled; remaining positions or uncertain orders require review and block automatic repeat. Once flat, repeat waits for the next actual trading day and resets that day's counters/P&L. Restart resumes the saved schedule and skips missed days, rather than replaying orders. Re-arming on the same day retains prior daily counters and P&L so it cannot erase a loss limit. Prior sessions are archived under `work/rapid-paper/sessions/`.

**Active hours are not a promise of fills:** the free IEX feed can lack fresh quotes or consecutive minute bars in parts of the extended session. The status explicitly shows that it is waiting for data. No stale quote is substituted, and the app does not upgrade to paid data. The existing predictor was validated only on regular-session observations; it has no demonstrated extended-hours edge.

## Statistical model

`StatisticalReturnModel` is standardized ridge regression, with an intercept and seven observed features: one-, three- and five-minute log returns, five-minute return volatility, high-low range fraction, relative volume, and deviation from minute VWAP. The target is the next two-minute close-to-close return. Features need eight consecutive completed minutes. Training means/scales are frozen; standardized inputs are clipped at eight standard deviations. Regularization is fixed at 0.1 times the training sample count; it was not optimized against validation results.

The fitted model uses 49,968 IEX observations from September 8–17, 2026. September 18–22 supplies 19,144 chronological validation observations, excluded from coefficient and scaling estimation. Mean absolute error was 7.494 basis points versus 7.487 for a no-change forecast; direction accuracy was 47.96%. No validation forecast exceeded the assumed four basis points plus $0.02/share round-trip cost. These overlapping forecasts are not independent trades or a portfolio backtest. **This model has no demonstrated profitable edge.**

The armed default is an explicitly labeled **model-ranked PAPER VOLUME experiment**. Each eligible stock gets a regression forecast and a percentile rank within the eligible basket. It buys the top quartile and can exit below the median. Ranking can select a negative forecast or one below trading costs. This exercises execution capacity; it must not be presented as a profitable strategy. For cost-gated entries, change the buy rule to `buy expected_edge > 0` and use a compatible sell rule. That variant can produce zero entries.

## Replaceable bricks

The **Model bricks** tab edits the independent parts stored in `work/strategies/rapid-paper.json`:

| Brick | What it changes |
| --- | --- |
| Universe | Stocks to evaluate; the predictor must cover every selected symbol |
| Hours & Limits | Eastern start/end, extended hours, daily repeat, scan interval, close buffer, entry caps, positions and loss limit, using form controls |
| Prediction | Fitted model JSON under `work/models/`; swap compatible coefficients without changing execution |
| Signal | Existing strategy expression engine: named `let` equations plus buy/sell conditions |
| Sizing | Share ceiling, entry dollars and total exposure, via SPEED & SIZE |
| Exits | Risk floor/ceiling, reward multiple, trailing distance and holding time |
| Portfolio | Concurrent positions, total entries, per-stock entries and session loss trigger |
| Execution | Cooldown, rolling order budget, spread and price-chase limits |

Signal fields include OHLCV, minute VWAP, `previous_close`, `predicted_return`, `trading_cost`, and `prediction_rank`. `trading_cost` estimates four basis points plus observed spread and $0.02/share. The script's ticker is a preview placeholder; the runner applies it independently to the universe. This uses the same bounded equation/rule parser as the manual strategy builder. Arbitrary external R indicators/model inputs are rejected in rapid mode until an adapter supplies fresh, aligned outputs; they are not silently ignored. No arbitrary code executes inside the rule evaluator.

Validate & Save Bricks writes the next armed model. Arming copies the full configuration and fitted predictor into session state and its ARMED event. Edits are disabled while running or waiting for the next trading day; restart and daily repeat use that frozen snapshot, not newly edited files. Replacing the statistical predictor is separate from order transport and reconciliation.

## Execution and records

The default stop distance is the greater of the latest minute range or 0.15% of price; skip entries above 0.8% risk or 0.08% spread. Target is 1.5 times initial risk. After a one-risk-unit advance, trail one risk unit behind the highest observed bid. The holding limit is two minutes, with a sixty-second re-entry cooldown. Stop new entries and attempt exits after estimated marked daily loss reaches $50 or at the configured close-position buffer. These are triggers, not guaranteed maximum losses. A loss limit can stop trading earlier than the scheduled end; repeat resumes only on the next trading day.

The broker adapter has a fixed Alpaca PAPER endpoint. Orders remain limit/DAY and explicitly set `extended_hours=true` when that setting is enabled. Immediately before each POST, it verifies the broker timestamp against that date's eligible calendar window, instead of treating the regular-session `is_open` flag as an extended-hours gate. It refuses to queue outside eligible hours. [Alpaca's extended-order requirements](https://docs.alpaca.markets/us/docs/orders-at-alpaca) and [half-day extended hours](https://alpaca.markets/blog/alpaca-introduces-stock-trading-from-4-am-to-8-pm-et/).

Latest quotes/minute bars are fetched in one basket snapshot; eight-bar warm-up comes from short historical requests once at startup, now including pre/postmarket bars. IEX is explicitly selected, with no paid-data upgrade. Each pending entry reserves a position slot before POST, and submission intent/client ID are saved before network mutation. An uncertain POST is never automatically retried. Pending orders are canceled after thirty seconds and await terminal confirmation. Only confirmed remaining filled quantity is sold; at most three exit attempts follow confirmed terminal outcomes. A conflicting position/order or unclear outcome stops new entries and flags manual review while other positions attempt to exit.

Keep TALG open and the computer awake. Exit limits are app-managed, not broker-hosted stops; unavailable data, a closed app, gaps or unfilled orders can leave positions open. Do not trade the basket manually in the same paper account. The process lock is shared with the old single-stock runner.

Files are preserved as a collection:

- `work/strategies/rapid-paper.json`: model bricks.
- `work/models/rapid-return-v1.json`: coefficients, feature scaling and validation metrics.
- `work/rapid-paper/session.json`: frozen model, per-stock positions/order state and portfolio counters.
- `work/rapid-paper/events-YYYY-MM-DD.jsonl`: features, predictions, rules, order intents, acknowledgments, fills and estimated P&L.
- `work/rapid-paper/research/*-iex.json`: twenty historical data files.
- `work/rapid-paper/research/validation-summary.json`: statistical validation and limitations.
- `work/rapid-paper/research/execution-load-test.json`: deterministic fake-broker throughput test; not actual exchange fills.
- `work/rapid-paper/research/broker-preflight.json`: read-only actual broker/data check.

The fake-broker load test retains an explicit 200/20 capped fixture and completes 100 round trips / 200 orders across five simultaneous slots. Separate tests verify uncapped operation beyond 200 entries and 20 per stock, pre/postmarket fills with the regular clock closed, extended limit/DAY payloads, early closes, daylight saving, weekends, repeat across restart, user-stop cancellation of repeat, same-day P&L retention, stale quotes, live-mode refusal and uncertain submissions. Virtual-clock throughput is not a measurement of real network throughput.
# Expanded stock and ETF research (25 September 2026)

The configured paper basket now contains 77 symbols: 50 stocks and 27 ETFs/ETPs, including UVXY. See `work/rapid-paper/research/expanded-universe/README.md` for the 82-candidate screen, 15 public primary sources, per-symbol observations, exclusions and chronological validation. UVXY targets 1.5x daily short-term VIX futures returns, not spot VIX. VIXY/VIXM/SVXY did not have sufficient contiguous IEX history for this model. The new predictor is `work/models/rapid-return-expanded-v1.json`; its validation does not demonstrate profitability.

The editable universe accepts up to 120 symbols while existing order and exposure bounds remain unchanged. Warmup failures are isolated and logged per symbol; fresh snapshots continue and can warm the model naturally. History seeding occurs once per arm/restart/trading day. Earlier sections above describe the original pilot and its research history.


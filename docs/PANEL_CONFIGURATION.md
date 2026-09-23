# TALG panel configuration

A fresh desk stays empty until the user presses `+`; a saved desk restores its configured panels. `+` creates a blank, movable panel immediately. The first choice *inside the panel* is Manual or AI-assisted configuration. A configuration is local to that panel; a second panel may show the same view with different settings. After configuration, **VAR** toggles a left-side quick-variable drawer over the panel without resizing its content; **{}** opens the full panel editor or Analysis studio code; **...** offers manual refresh and fit/restore. No example prices, positions, model estimates, or AI-generated results appear as live data. The separate [desk profile](DESK_PROFILES.md) saves the whole operating space.

## Configuration model

| Layer | Current | Next useful addition |
| --- | --- | --- |
| Data | Alpaca account views, stock snapshot, session watchlist, validated model-signal CSV preview | Explicit source, feed, as-of time, freshness and missing-data state on every panel |
| Scope | View, chart/quote ticker, account paper/live, quick-open from watchlist | Optional symbol linking between panels |
| Time | Portfolio history period; stock-panel range, Alpaca bar interval, and independent refresh cadence | Market-calendar-aware range presets and streaming updates |
| Analysis | Stock-panel script: configurable SMA, EMA, Bollinger, RSI, MACD, per-bar return and volatility; up to five stocks; observed OHLCV/VWAP/trade-count plots; custom R indicators; local model studio | Multiple lower panes, saveable study presets, benchmark analytics |
| Presentation | Free move, resize and fit; per-panel chart display style (line, step, area, points, columns, lollipop, candles, hollow candles, OHLC, Heikin-Ashi) | Axes and units, saved chart-style presets |
| Statistical lens | Descriptive risk lens | Cash-flow-aware return method, benchmark, sampling frequency, confidence interval method and explicit assumptions |
| AI-assisted | Entry point that states no model is connected | Natural-language request -> *draft* validated panel specification -> user review -> apply; never arbitrary executable code or invented data |

Manual setup begins with a panel type. Portfolio shows only portfolio view and applicable history; Stock chart shows one script editor; Market quote shows only ticker; Watchlist has no extra fields; Modeling board has an optional signal CSV path; Analysis studio opens an R code editor. A stock chart can use a watchlist or directly entered ticker without requiring a held position. Seven graph starters fill the chart script with editable configuration, not prices: Trend + bands, Two-stock comparison, Two separate stocks, Intraday volatility, Candles + volume, Price + volume, and Volume + average. Every script command maps to a real calculation or data request. AI output should eventually be constrained to the same typed panel specification as manual setup, with provenance and a preview of the proposed settings before applying.

## Stock-panel script (version 1)

This is declarative configuration for **one panel**, not executable R or Java. Blank lines and `#` comment lines are allowed. Unknown or duplicate commands produce a line-numbered error. One `ticker`, `range`, `bars`, `refresh`, and `study` line is required; `plot` defaults to `close` and `display` defaults to `line` for older saved profiles. `overlay` and `compare` lines are optional and repeatable. Omit an overlay to remove it. The compact **VAR** drawer exposes `display` and the other common chart variables in single-line rows; its muted scrollbar appears only when the panel is too short for the controls.

```text
ticker AAPL
stock MSFT
stock NVDA
range 5D
bars 5Min
refresh 60s
layout separate
plot volume
display columns
watermark off
overlay SMA(8)
overlay EMA(21)
overlay Bollinger(20, 2.5)
study MACD(8, 21, 5)
```

`range` accepts `1D`, `5D`, `1M`, `3M`, `6M`, `YTD`, or `1Y`. `bars` accepts `1Min`, `5Min`, `15Min`, `1Hour`, `1Day`, or `1Week`; this is the aggregation width of an Alpaca source bar even when the display draws a smooth line. To bound historical requests, `1Min` supports up to `5D`, `5Min` up to `1M`, and `15Min` up to `3M`. A one-day range needs bars narrower than `1Day`; `1Week` bars need at least a one-month range. `refresh` accepts `off`, `30s`, `60s`, `5m`, or `15m`; it is the polling interval, independent of bar width. New chart scripts default to `60s`; explicit `off` disables polling. Automatic refresh stops when the panel is removed or reconfigured.

`plot` chooses the main plotted value: `open`, `high`, `low`, `close`, `volume`, `vwap`, `trades`, or `Custom(name)` from a saved R indicator. `study Field(volume)` (or another named Alpaca field) draws an observed field as bars in the lower pane; `plot close` with `study Field(volume)` gives a price-and-volume graph. Alpaca does not always provide optional VWAP/trade-count fields; TALG reports missing fields instead of inventing them. Indicator numbers are periods measured in **bars**. Thus `SMA(20)` means 20 five-minute bars on a `5Min` chart, but 20 trading-day bars on a `1Day` chart. Overlays `SMA(period)`, `EMA(period)`, and `Bollinger(period, standard-deviation multiplier)` are calculated on the selected `plot` value. The single lower `study` may be `none`, `return`, `RSI(period)`, `MACD(fast, slow, signal)`, `Volatility(period)`, `Field(name)`, or `Custom(name)`. `return` is the plotted value's bar-to-bar percent change. `Volatility` is the rolling standard deviation of that per-bar change, **not annualized**. Periods must be 2–500, the Bollinger multiplier must be above zero and at most 10, and MACD fast must be below slow.

`display` independently chooses how that value is drawn: `line`, `step`, `area`, `points`, `columns`, or `lollipop`. The price-only styles `candles`, `hollow_candles`, `ohlc`, and `heikin_ashi` require `plot close` and complete, internally consistent observed open/high/low/close bars. They do not substitute the close for a missing OHLC field. Green/red/grey mark up/down/unchanged bars. Hollow up candles retain a green outline; Heikin-Ashi is explicitly labeled as a **derived** transformation of observed OHLC, not a directly observed price. SMA/EMA/Bollinger still use the observed close. `area` and `lollipop` fill/stem to the minimum observed value in the displayed window, while `columns` use a zero baseline. Changing display style never changes the fetched bars, bar width, range, update interval, or panel layout. `watermark on` adds a faint symbol behind the chart; it defaults to `off` and is also in **VAR**. Renko, point-and-figure, range bars, and similar non-time-based transformations are not implemented; displaying ordinary time bars under those names would be misleading.

The main value scale is on the right, with an exact-value tag connected to the final plotted bar and a compact last-bar OHLC readout. The final observed timestamp is included on the bottom axis; when a lower study is present, only that pane carries time labels. A single chart gives roughly one quarter of its height to the lower study, and compact studies use fewer value ticks to avoid overlap. R renders at the Java panel's actual aspect ratio, and a flat account-equity history uses one exact-value tick instead of several rounded duplicates. These are presentation changes only; no prices or account observations are synthesized.

The **VAR** drawer reconciles linked choices before applying them. The most recently changed range or bar-width control is kept and the other moves to the nearest supported value; likewise, choosing a price-bar display uses `plot close`, while changing the plot to volume or another non-close value changes an incompatible price-bar display to columns or line. Known shorthand (`price`, `vol`, `RSI`, `SMA`, etc.) is expanded, indicator periods are bounded to the supported range, duplicate comparison tickers are removed, and incomplete typed names retain the prior working choice. A small neutral note says what TALG adjusted; quick changes no longer surface parser errors or replace a working chart with an invalid one. If Alpaca returns fewer bars than an SMA, Bollinger, RSI, or volatility window requires, the R graph temporarily uses the longest feasible period and labels the effective value as “auto from” the requested value. The saved script retains the requested period for later, longer histories. This applies to the quick controls and observed-history rendering, not arbitrary code in the full script editor. Missing Alpaca data, a disconnected account, or a missing user indicator still needs a truthful unavailable state rather than a fabricated chart.

Each `stock SYMBOL` (or `compare SYMBOL`) adds a real Alpaca series, up to five stocks total. All series share the panel's range and bar width. `layout overlay` draws them together as indexed comparisons: each starts at 100 at its first finite plotted value, so unlike units are not mixed on one axis. `layout separate` draws an individual chart of the selected raw field for each stock inside the same panel. Its selected overlays and lower study are recalculated independently for every stock. A single stock occupies the full panel; two charts sit side by side; three to five use a compact, space-filling grid. No absent values are filled in, and the request fails visibly if any stock lacks sufficient bars or Alpaca history exceeds the 20,000-bar safety bound.

## User-authored indicators and models

Choose **Analysis studio** as a panel type to author local R code. Name the script with letters, digits, and underscores, starting with a letter. **Save** checks R syntax and stores the code under `work/indicators/<name>.R` or `work/models/<name>.R` on the TALG USB; it does not execute the code. Existing scripts require confirmation before replacement. These files are intentionally local and remain on the drive. Custom R code is **trusted code**, not sandboxed: review it before running it, especially if generated by an AI assistant. A separate R process fetches Alpaca data with credentials and writes a temporary observed-data file. Rendering and custom indicator/model processes receive that data with Alpaca credential environment variables removed. These processes can still access the local machine as the current user, so run only code you trust.

An indicator must define `talg_indicator <- function(bars) ...` and return one numeric value per observed bar (NA is allowed for warm-up periods, but at least one value must be finite; `plot Custom(name)` needs two finite values). `bars` includes UTC `date`, `open`, `high`, `low`, `close`, `volume`, `vwap`, and `trades`; optional fields may be missing. Once saved, use `overlay Custom(my_indicator)` for a main-axis line, `study Custom(my_indicator)` for a lower pane, or `plot Custom(my_indicator)` as the main value. Every stock in `layout separate` gets its own independently computed indicator values. A main-axis custom overlay should return values in the selected plot's units; TALG cannot infer arbitrary units from user code.

For example, an indicator script named `average_30` could compute a 30-bar mean from the observed closes:

```r
talg_indicator <- function(bars) {
  as.numeric(stats::filter(bars$close, rep(1 / 30, 30), sides = 1))
}
```

A model must define `talg_model <- function(series) ...`, where `series` is a named list of observed Alpaca bar data frames for the comma-separated model input stocks. **Run Model** fetches one year of daily bars, executes that trusted local function, and shows its validated output in the Studio panel. Return a data frame with exactly these columns, in order: `symbol`, `as_of_utc` (UTC ISO timestamp), `metric`, `value` (finite number), `unit`, `horizon`, and `model_version`. This accommodates return, risk, price, score, or other explicitly named model outputs without TALG inventing their meaning. TALG saves the result as a JSON file under `work/models/results/` with the script checksum, input symbols, Alpaca input source, bar interval, feed, and each stock's last observed timestamp. If your code uses other inputs, document those in your model version or source code; TALG cannot automatically identify them. A model's forecast is labeled as model output, never as an observed outcome. The Studio does not place trades.

The older **Modeling board** still accepts the separate strict seven-column one-day forecast CSV format. Python models can continue to produce that CSV; the in-panel script editor and runner currently execute R only.

This design draws on [Fidelity's linked tools and workspace layouts](https://www.fidelity.com/products/atbt/help/ActiveTraderTools_Layouts_Help.html), [thinkorswim's Flexible Grid](https://toslc.thinkorswim.com/center/howToTos/thinkManual/charts/Flexible-Grid) and [study editor](https://toslc.thinkorswim.com/center/howToTos/thinkManual/charts/Using-Studies-and-Strategies), [TradingView's chart and indicator settings](https://www.tradingview.com/support/solutions/43000748166-how-to-configure-your-supercharts/), and [Grafana's per-panel transformations and field settings](https://grafana.com/docs/grafana/latest/visualizations/panels-visualizations/query-transform-data/). TALG's differentiator should be research traceability and statistical assumptions, not simply more indicators.

# TALG

**A research-first trading algorithm laboratory.** Java owns the desk, rule evaluation, and explicitly confirmed Alpaca order ticket; Python collects market observations and annotates supplied text with the Llama API; R renders analytics and runs trusted local indicators/models. The Trade tab's manual paper/live tickets require a preview and separate confirmation. Its separate **Paper strategy** runs a bounded, explicitly armed [20-stock statistical paper experiment](docs/RAPID_PAPER.md) with replaceable model bricks. The fitted regression has no demonstrated profitable edge; the default model-ranked mode tests order volume. Model coefficients, validation, observations and execution records are saved as files.

## Research workflow

```text
Alpaca daily bars ───────────────┐
                                  ├─> your point-in-time statistical model
Supplied articles -> Llama tone ──┘             │
                                               v
                                      model_signals.csv
                                               │
                                               v
                                   Java validation + risk gate
                                               │
                                               v
                                    research candidates only
                                               │
                                               v
                                   R return and tail diagnostics
```

Llama is a *text-feature extractor*, not a market-data provider or a trading oracle. Its `self_reported_confidence` is not statistically calibrated. The Python adapter does not find news by itself: provide timestamped articles from a source you are entitled to use. Alpaca supplies OHLCV bars, including a recorded feed and raw-adjustment flag. [Alpaca documents its historical bars endpoint and pagination](https://docs.alpaca.markets/us/reference/stockbarsingle-1); [Meta's Llama API examples show the native chat-completions request and response](https://github.com/oculus-samples/Unity-SpatialLingo/blob/main/Packages/com.meta.utilities.llamaapi/README.md).

## Free News / Info

The **NEWS** tab collects world events, politics, business, company headlines, and official releases from 65 public RSS/Atom feeds, including broader publisher discovery through Google News. It requires no API keys or paid subscriptions. Open NEWS to start five-minute updates while TALG is running; search and filter the collection, open original articles, and inspect individual feed failures and publication freshness in **Sources & coverage**. Some linked full articles may be paywalled even though their feed excerpts are free.

The collection is stored as readable daily files by source under `data/news/YYYY-MM-DD/`: `.jsonl` preserves collected items and revisions, and `.xml` keeps each day's latest raw feed. Permanent archive files are never automatically deleted; the smaller browsing index lives under `work/news`. **Open collection** opens the folder. **Company financials** separately saves free SEC company-facts and normalized financial observations under `data/financials/`, using a contact email for SEC request identification. News is not automatically sent to Llama or used to submit trades. See the [News / Info guide](docs/NEWS_INFO.md) for coverage, file fields, source configuration, and limitations.

## Run the offline example

From `F:\TALG` in PowerShell:

```powershell
& .\.tools\pixi\pixi.exe run mvn verify
java -jar target\talg-0.1.0-SNAPSHOT.jar
java -jar target\talg-0.1.0-SNAPSHOT.jar evaluate examples\model_signals.csv 2026-09-22T20:00:00Z 100000
& .\.tools\pixi\pixi.exe run python -m pytest -q
& .\.tools\pixi\pixi.exe run Rscript r/risk_report.R examples/returns.csv work/risk-summary.md
```

The CSVs under `examples/` are synthetic fixtures, not investment results. The Java `evaluate` command emits `RESEARCH_CANDIDATE` or `NO_TRADE` and never submits orders; only the desktop Trade tab has an order-submission path.

### Integrated Java market desk

Run `F:\Start TALG.cmd` to open the application. Java owns the window, navigation, market quotes, account settings, research workflow, and explicit Trade tab. A fresh desk starts as one open, scrollable canvas with no preloaded panels or reserved slots; a saved desk restores its own panels. **+** in the thin left rail immediately adds a blank panel. Inside that panel, first choose **Manual** or **AI-assisted** configuration. Manual setup starts with **Panel type**: Portfolio, Stock chart, Market quote, Watchlist, Modeling board, or Models. Only relevant options appear for that type. A stock-chart panel has seven editable graph starters, including comparisons, candlesticks, and volume; select one with **Use Graph**, edit its script, then press **Apply to Panel**. AI-assisted setup is visibly marked as not connected yet—it will not fabricate a chart or silently choose settings. Configured panels use a thin title strip: **VAR** toggles a compact left-side quick-variable drawer over the content, including the stock chart's display style (line, step, area, points, columns, lollipop, candlesticks, hollow candles, OHLC, or Heikin-Ashi). **{}** opens the panel's full configuration or code editor, **...** offers refresh and fit/restore, and **X** closes it. The drawer does not resize the graph. Drag the title strip to move a panel or its lower-right overlay grip to resize it. Scrollbars or middle-mouse drag move around the canvas. The **home** control in the rail returns to the starting area. Java owns each panel frame and hosts the R-rendered portfolio and stock analytics inside it. The Holdings view uses a sortable Java table.

The running desktop never fills empty panels with sample prices or positions. Stock Selection shows the active profile's removable research watchlist (the Default profile has AAPL, MSFT, NVDA); these are **not** holdings. Select one and press **Open Chart** or **Open Quote**, or double-click to open a chart. Connect Alpaca in Settings and select **Apply & Connect** to request real data. **Backfill 1Y** retrieves validated daily bars for the watchlist through the Python Alpaca adapter and writes timestamped CSVs under `data/market/`; it cannot run until credentials are supplied. Until the API responds, fields show `—` or a specific empty or error message. The app uses soft black, purple, and soft grey; green and red are reserved for actual gains and losses.

The slim desk-profile selector sits at the top of Display. A profile saves its watchlist, viewport, panel geometry, active panel configuration, and unfinished panel drafts to `work/profiles/*.desk.json` on the USB. It autosaves during use and reopens the active desk on the next launch. `+` creates a desk, `{ }` edits the whole desk as a declarative JSON script, and `↻` reloads externally edited scripts. TALG does not put Settings credentials or cached market values in these files. See [desk profiles](docs/DESK_PROFILES.md) and the [three-panel example](examples/Research%20Desk.desk.json).

Select **Quant Research** for a ready-made five-panel desk using the supplied `ema_34`, `volume_ratio_20`, and `price_zscore_20` R indicators. The **INDICATORS** tab under **DESK** is the reusable-block library: choose a calculation starter or load one you saved, give it your own name, edit its R function, save it, and test it on observed Alpaca bars. **ADD TO CHART** creates a stock panel using it as a lower study; **USE IN TRADING** adds a named input to the current rule without submitting an order. In **TRADE**, **+ EQUATION** and **+ CONDITION** compose those inputs with observed fields into inspectable Buy/Sell rules; the script remains editable. This is a latest-observation rule evaluator, not a backtest or autonomous trading loop. Rules only propose an order; a fresh preview and explicit paper/live confirmation are required to submit. See the [Trading tab guide](docs/TRADING.md).

The [panel configuration roadmap](docs/PANEL_CONFIGURATION.md) records which controls work now and which research-oriented controls are next.

The Modeling board loads a CSV written by your statistical model, checks it against the strict signal contract below, and shows the validated forecasts, version count, and latest as-of time. It does not train or execute a model, and it never loads the example CSV automatically.

Paste Alpaca credentials into the masked Settings fields and press **Apply & Connect**. TALG stores them encrypted for the current Windows user at `%LOCALAPPDATA%\TALG\alpaca-credentials.dpapi`, outside the USB; on restart it leaves both fields blank and uses the saved keys to reconnect read-only desk panels automatically. **Forget Saved Keys** removes that file and disconnects the session. If no keys are saved, TALG can use `APCA_API_KEY_ID` and `APCA_API_SECRET_KEY` from the operating-system environment without displaying them when you press Connect. It does **not** load `.env` automatically; `.env` is for the Python adapters. Because Windows DPAPI binds this encrypted store to the Windows account/computer, moving the USB to another machine requires re-entering the keys. A separate data-fetch R process receives credentials; chart rendering and user-authored R code receive only observed data with Alpaca credential environment variables removed. This is not a sandbox for local R code. Temporary data and rendered images are deleted after use. Desk views use read-only requests; the separate Trade tab can send confirmed order and cancellation requests. IEX covers one exchange; SIP access depends on your subscription.

### R portfolio display

The embedded R display has Overview, Performance, Allocation, Risk lens, Holdings, and Stock lab views. Portfolio views read the selected Alpaca account's current positions, account equity, and daily account history. Stock lab reads Alpaca bars for the ticker selected from the watchlist or entered directly; it does not require that stock to be held. Each stock panel is configured by its own short chart script, so bar width, refresh cadence, stocks, layout, and indicator periods are independent per panel. The panel title distinguishes bar width from date window; the chart uses observed timestamps and keeps source/feed details in its tooltip, without a redundant plot header or footer. New charts poll every 60 seconds unless the script changes that interval. See [panel configuration](docs/PANEL_CONFIGURATION.md) for the syntax. Multiple stocks can be drawn on one indexed comparison chart or as separate raw-price charts within the same panel.

The **INDICATORS** tab saves named R calculations under `work/indicators/`. The separate **Models** panel is for a `talg_model(series)` R function: it can load saved model code, check syntax, run explicitly on observed Alpaca bars, and show validated, labeled output only after a run. The existing Modeling board continues to load externally produced forecast CSVs, including output from Python models. No user code runs merely because a tab or panel opens.

Account equity history can reflect deposits and withdrawals. The Risk lens labels its equity drawdown accordingly and does not present cash-flow-sensitive VaR or volatility as validated portfolio risk. The `examples/` CSVs and standalone R Shiny prototype remain developer test fixtures; the launcher does not load them.

## Connect live data to the research pipeline

Copy `.env.example` to `.env` and fill your own Alpaca market-data and Llama credentials. `.env` is ignored by Git. Then, for example:

```powershell
& .\.tools\pixi\pixi.exe run python -m talg_py.market_data AAPL 2026-08-01 2026-08-31 data\raw\aapl-bars.csv
& .\.tools\pixi\pixi.exe run python -m talg_py.llama_features examples\articles.csv data\derived\article-features.csv
```

The date range for the Alpaca call is inclusive. `iex` is a single-exchange feed; it should not be mistaken for the consolidated US market. Preserve the bar feed and corporate-action adjustment choice when comparing results. API calls are opt-in and have not been tested with personal credentials.

## Your model's contract

Your Python or R model should join bars and text features *only after they were available*, fit on past data, and export a plain UTF-8 CSV with exactly these columns:

| Column | Meaning |
|:--|:--|
| `symbol` | Uppercase ticker. |
| `as_of_utc` | UTC instant when every input to this forecast was available. |
| `price_usd` | Observed price used for research sizing. |
| `expected_return_1d` | Forecast arithmetic return for the next trading day, decimal units. |
| `forecast_annual_volatility` | Positive annualized volatility forecast, decimal units. |
| `horizon_days` | `1` in this prototype. |
| `model_version` | Immutable identifier for the model and training run. |

See `examples/model_signals.csv`. The strict Java CSV reader disallows quoted fields and commas inside values. An invalid row fails the run rather than silently becoming a candidate.

The separate command-line `evaluate` gate rejects future or more-than-72-hour-old signals and forecasts at or below a 0.1% one-day edge. Its long-only research sizing is capped at 5% of supplied capital per signal and at a 1%-annual-volatility contribution approximation. Those are illustrative controls, not a validated trading policy. The desktop Trade tab has its own independent, explicit order workflow; CLI output is not an order list.

## Statistical and actuarial standard

The R report calculates annualized growth and volatility, maximum drawdown, empirical 95% one-day VaR and expected shortfall, active-return information ratio, and a five-day block-bootstrap interval for the annualized arithmetic mean. It states its assumptions and distinguishes descriptive tail summaries from future-risk guarantees.

Before relying on a strategy—especially with live credentials—the model should have walk-forward evaluation with a frozen holdout; point-in-time corporate actions, ticker history, article availability and model versioning; transaction costs and slippage; benchmark and regime comparisons; and checks for calibration, autocorrelation, tail behavior, concentration and drawdowns. Historical Llama annotations generated today must **not** be assigned an earlier availability timestamp in a backtest.

## Repository map

- `src/main/java/...`: Java signal contract, validation, CLI and research risk gate.
- `src/main/java/.../TalgDesktop.java`: Java market desk, trade navigation, and Alpaca display.
- `src/main/java/.../TradingPanel.java`: explicit rule preview, order ticket, submission, and open-order management.
- `talg_py/`: Alpaca market-data and native Llama text-feature adapters.
- `r/risk_report.R`: descriptive performance and tail-risk report.
- `r/live_portfolio.R`: read-only Alpaca account and daily bars adapter used by the integrated display.
- `r/java_display.R`: R renderer hosted by the Java desktop workspace.
- `r/portfolio_app/`: prototype R data helpers and development dashboard.
- `tests/`, `src/test/`: offline adapter and Java unit tests.
- `examples/`: clearly synthetic fixtures.

The next design decision is yours: specify the statistical model's target, forecast horizon, feature set and training/evaluation method. The adapters and CSV contract are ready for it.

# Trading tab: explicit execution and desk-built rules

The **TRADE** tab sits directly below **DESK** in the thin navigation rail. It uses the connected Alpaca account mode: `Paper account` sends requests only to `paper-api.alpaca.markets`; `Live account` sends requests only to `api.alpaca.markets`. [Alpaca requires distinct credentials and domains for paper and live trading](https://docs.alpaca.markets/us/v1.1/docs/authentication-1). Confirm the mode in the tab before every submission. TALG never changes a live key into a paper key. Switching modes in Settings requires entering that mode's own key and secret; unsaved mode selections do not reroute the current connection. Only one credential pair is stored at a time.

The ticket supports whole-share U.S. stock buys and sells, with a configurable maximum notional capped at $2,500 per order. It uses a DAY limit order, not an unbounded market order. A preview checks Alpaca's market clock, account trading status, buying power or existing long position, and a timestamped quote. It refuses stale quotes, a closed market, delayed SIP data, short-selling through this ticket, and a limit-price notional above the cap. These checks are not a guarantee of execution or price; the broker can reject the order, and an accepted order can remain open or fill later. [Alpaca's order endpoint](https://docs.alpaca.markets/us/v1.1/reference/postorder) documents limit prices, day time-in-force, and order states. TALG does not retry an uncertain submission automatically. Use **Refresh Orders** to inspect open orders, **Check Last Status** to see whether the most recently submitted order has filled or changed state, and **Cancel Selected** to request cancellation; a cancel request is not proof that an order did not fill. [Alpaca's cancellation behavior](https://docs.alpaca.markets/us/reference/deleteorderbyorderid-1).

Live orders require typing `LIVE BUY SYMBOL QTY` or `LIVE SELL SYMBOL QTY` exactly in a confirmation dialog. Paper orders require a separate yes/no confirmation. A preview expires after one minute; submission repeats the preflight checks and rejects a changed limit price. Saving a script, opening a desk, receiving a quote, or evaluating a true rule never submits an order. There is no unattended trading loop in this version.

## Strategy script

Scripts are stored on the USB under `work/strategies/*.strategy`. **Save Script** only saves text. **Evaluate Rule** fetches observed Alpaca bars, computes named custom R indicators, optionally reads recent validated Analysis studio model results, evaluates the Boolean rules, and prepares a trade preview only if exactly one side is true. The rule engine supports numeric `+ - * /`, parentheses, comparison operators `> >= < <= == !=`, and Boolean `and`, `or`, `not`. It does not execute arbitrary Java or R expressions. Custom indicator files are trusted local R code and run in a process without Alpaca credential environment variables; they are not a general sandbox.

```text
name Trend Volume
ticker AAPL
range 6M
bars 1Day
input trend Custom(ema_34)
input pressure Custom(volume_ratio_20)
let edge = close / trend - 1
buy edge > 0.01 and pressure > 1.10
sell edge < -0.01
qty 1
```

Built-in variables are the latest observed `open`, `high`, `low`, `close`, `volume`, `vwap`, and `trades` when Alpaca provides them. `Custom(name)` reads `work/indicators/name.R`, using the same `talg_indicator(bars)` contract as desk charts. The supplied research desk includes `ema_34`, `volume_ratio_20`, and `price_zscore_20`. `Model(name,metric)` reads the latest result produced by **Run Model** in Analysis studio, for example `input forecast Model(my_model,expected_return)`. Its source checksum, stock, feed, and timestamps must match and be recent; if the model has not been run or is stale, evaluation fails instead of making up a number. Strategy inputs and rule values are displayed with their observation time before a trade can be confirmed.

The supplied `Trend Volume` rules are *examples of wiring*, not validated investment signals. In particular, a daily bar may be incomplete during the trading day, IEX is not a consolidated feed, and a signal based on it can change before close. Test your own rules with point-in-time data, transaction costs, slippage, corporate actions, and out-of-sample evaluation before any live use. TALG provides no return guarantee, portfolio-level risk engine, or automatic protective exit.

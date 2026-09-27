# Model workspaces and language

The main **Trade** tab is for positions, open orders and manual tickets. **Model** contains only the trading model. **News** contains the news model and its data. Each opens on **Model**, the actual code editor. A single utility row holds view selection, saving, validation, running and code-generated controls. There is no fixed sentiment/importance scoring form.

**News → Data** displays retained information, the outputs named by the code, model/encoder versions, timestamps, links and the original vector. **Model → Tickers** displays per-symbol retained observations, evaluated values, decisions and the frozen session model. Select a row to explore its nested fields. Missing evaluations are shown as missing, not as neutral scores. Historical timestamps remain visible.

The UI renders the data contract. It does not assign semantic meaning or trading authority to a newly named output. Scalar values, text, booleans, vectors and nested records can be displayed. News data is paginated in groups of 200 with search. The optional sheet block describes data fields and row sources. The app owns every visual setting and automatically fits columns to headings, contents and available space. Old code without a schema gets an explicit unsaved starter data binding; its outputs remain available. News search matches headline/publisher, and sheet sorting applies to the loaded page of 200 records.

## News code

The executable source is `work/models/news-rating.talg`. The engine is `talg_py/model_language.py`; data bindings and provenance are in `talg_py/news_ratings.py`. A minimal model is:

```text
name My news model
param horizon = 1800
param terms = ["supply", "energy"]
input age = age_seconds
input headline_text = headline
input vector = embedding
let relevant = contains_any(headline_text, terms)
output freshness = decay(age, horizon)
output topic_match = relevant
output label = "matched" if relevant else "other"
output vector_copy = vector
output tensor_weight = 1
```

`param` declares literal configuration. The Controls button derives labeled fields from these declarations and edits the corresponding source lines. News parameters can be numbers, quoted text, `True`/`False`, lists or objects. `input` names a supplied observation. `let` defines an intermediate expression. `output` names a retained result. Changing output names/types changes what appears in Data without Java changes.

Available bindings are `headline`, `text` (headline plus supplied excerpt), `publisher`, `age_seconds`, `source_count`, `observation_count`, `ticker_count`, `embedding`, `half_lives_seconds`, and `prior_mass`. Source counts measure distinct feed/source identities, not independent corroboration. Embeddings are latent semantic features, not calibrated probabilities or returns.

For independently supplied features, use `input alias = custom.name`. The optional `work/models/news-inputs.json` provides `{"defaults":{"name":value},"items":{"information-id":{"name":value}}}`. Per-item values override defaults. Missing custom inputs produce an explicit rating error; they are not replaced with zero. Retained values are recorded with each evaluation.

Expressions support arithmetic (`+ - * / ** %`), comparisons, `and/or/not`, conditional expressions (`a if condition else b`), and list/object literals. Pure functions: `abs`, `min`, `max`, `sqrt`, `log`, `exp`, `clamp`, `decay`, `contains`, `contains_any`, `length`, `component`, `dot`, `norm`, `sum`, `mean`, `field`, `column`. `component(vector,index)` is zero-based. AST interpretation has bounded expression sizes and no imports, attributes, arbitrary calls, file/network access, loops or executable Python.

The optional output `tensor_weight` is an explicit adapter contract: a number from 0 to 1 that scales the item's contribution to global and ticker aggregates. Other output names and types are model-defined. Once ratings are enabled, failed, stale-content and unrated items contribute zero until rated; legacy stores without a rating program retain the previous weight of one. Publication-based decay and ticker relevance remain independent inputs to aggregation.

**Run** encodes pending retained news, evaluates the saved code, and writes the tensors. It requires saved code/settings and uses the existing local encoder; it does not fetch full article text or submit trades. A continuous news worker reloads rating code between snapshots and refreshes ratings approximately every 30 seconds. One-shot backfills rate the completed set once. Processing time extends those intervals. A process lock prevents two encoding workers. After a one-shot run exits, outputs are a dated snapshot until another run; a past `LIVE` status is not proof a worker is still running.

`information.sqlite` retains current `ratings`, immutable `rating_programs`, and the first `rating_history` result per information/content/code/encoder combination, including input values and the vector used. Subsequent time-dependent evaluations update current ratings; this is not a complete historical tick series. Original archives remain intact. The starter outputs measured freshness, feed coverage, ticker links, vector length and tensor weight. It does not invent a directional effect, importance or sentiment estimate.

## Trading code

The source editor reads/writes `signal.script` in `work/strategies/rapid-paper.json`; other independent components remain available under Settings. It uses `StrategyScript.java`, the same interpreter as the runner:

```text
name My trading model
ticker AAPL
range 1D
bars 1Min
input forecast Observed(predicted_return)
input cost Observed(trading_cost)
param required_edge = 0.001
output net_edge = forecast - cost
buy net_edge > required_edge
sell net_edge < 0
qty 1
```

`param` declares numeric controls. `let` and `output` create named numeric values; evaluated values are retained and displayed. `Observed(field)` binds supplied scalar fields explicitly. OHLCV/VWAP, `previous_close`, `predicted_return`, `trading_cost` and `prediction_rank` are available in the rapid runner. Quote entry/exit expressions and thresholds remain in the independently editable `signal.bidRules` component. `qty` must match the sizing component's share ceiling.

Scalar expressions support arithmetic, comparisons, logical operations and `min`, `max`, `abs`, `sqrt`, `log`, `exp`, `pow`, `clamp`. The generic rule evaluator also supports its existing Indicator and Model adapters; the rapid runner requires fresh aligned adapters and accepts Observed and News bindings. Unsupported sources fail validation instead of silently becoming inputs. New adapters can be implemented in the visible source; the UI cannot make an unimplemented data source executable.

**Run** in the trading model evaluates the current code against retained ticker inputs and displays draft values/decisions. News bindings read the current materialized symbol outputs; other inputs are retained observations. It fetches no live market observations and sends no orders. **Paper session** contains explicit arming and stop controls. Saving never modifies an armed model snapshot; active or waiting sessions block saved-model replacement. The next explicit arm freezes the saved configuration and predictor. Execution, account routing, freshness, reconciliation and risk guards stay independent of expression code.

The runner now retains each evaluated ticker's named values, decision, evaluation timestamp and minute bars even when execution gates prevent an entry. Older sessions use preserved SIGNAL records where available, and retain missing states where no evaluation exists.

## Symbol-level calls from trading to news

The news source can contain a second program between `symbol` and `end symbol`. It receives the requested `symbol`, `articles` (only successfully rated, content/encoder/code-matching articles associated with that ticker), and `now` (Unix seconds). Each article has `id`, `headline`, `publisher`, `age_seconds`, `rated_at`, and `outputs`. `column(records,"outputs.my_score")` extracts a list; `field(record,"outputs.my_score")` extracts a value. Both reject absent keys. This code defines aggregation and the empty-set result:

```text
symbol
input items = articles
output article_count = length(items)
output rating = mean(column(items, "outputs.freshness")) if length(items) else 0
end symbol
```

This starter's `rating` measures average freshness; it is **not sentiment or a price forecast**. Replace the expression and article outputs to define other ratings. In trading code:

```text
input news_rating News(rating, 120)
```

The adapter passes the current universe ticker, selects the named numeric output, and makes it available as `news_rating`. The second argument is the maximum result age in seconds (1–86400; default 120). `Model → More → News input` inserts this declaration into the draft; use it in your own equations and rules. It does not change rules or arm a model automatically.

News **Run** publishes `work/news-tensor/symbol-ratings.json` alongside ratings and tensors; the continuous worker refreshes it after each rating pass. Trading reads these materialized calls without starting Python in an order scan. Missing symbol/output, nonnumeric outputs, errors, changed news source or stale timestamps reject that model evaluation and new entries. Independent stop, target, timed, reconciliation and account guards remain active. The leg retains the requested ticker, result, version, evaluation time and article identities under `newsModelInputs`. News dependencies intentionally follow the current saved News model and are recorded per evaluation; an armed trading script stays frozen.

**News → More → Symbol lookup** directly evaluates the saved symbol program for one ticker using current retained article ratings. It does not refresh article ratings or the trading snapshot. The CLI is `python -m talg_py.model_workbench news-symbols --root F:/TALG --search AAPL,MSFT --out result.json`. A new ticker with no published result is unavailable to the trading snapshot adapter until the news worker produces one. Keep News refreshed when a strategy requires recent outputs.

## Model data and app-owned presentation

Model code defines inputs, calculations, outputs and optional data schemas. **All visuals belong to the app**: column widths, readable headings, spacing, row height, number formatting, alignment, sorting controls and the inspection pane. There are no visual tuning fields to set in model code. Columns automatically fit the loaded headings/data and available workspace, and adapt when the window or inspection pane changes size. Very wide datasets scroll horizontally rather than squeezing every column into unreadable cells. Hovering shows the unrounded stored value; click a header to sort the loaded page. The app's Inspect button opens the selected record's retained data.

The optional `sheet` / `end sheet` block describes data relationships only: up to 16 named datasets with row sources and fields. For example:

```text
output scenarios = [{"name": "base", "weight": 0.75}, {"name": "stress", "weight": 0.25}]
sheet
{
  "sheets": [{
    "name": "Scenarios",
    "rows": "/outputs/scenarios",
    "columns": [
      {"id": "article", "source": "record", "path": "/headline"},
      {"id": "scenario", "path": "/name"},
      {"id": "weight", "path": "/weight", "type": "number"}
    ]
  }]
}
end sheet
```

`rows` is a JSON pointer relative to each retained record: omit it (or use `""`) for one row per record; arrays expand to one row per item; objects/scalars yield one row; missing/null yield no rows. Field `path` is relative to that row, or the original record when `source` is `"record"`. Use `/outputs/name` for news/selection results and `/evaluation/values/name` for trading results. JSON pointer escapes are `~1` for `/` and `~0` for `~` in a key. Trading expressions currently produce scalars; schemas can also expose existing arrays such as retained bars.

Field declarations contain only unique `id`, `path`, optional `source` (`row`/`record`), and optional data `type` (`auto`/`text`/`number`/`boolean`/`json`). Auto types are inferred from values. These describe data, not appearance. Changing a type does not round or alter the stored calculation. Unknown schema fields fail validation. Old `width`, `format`, `precision`, `align`, `missing`, `label`, `rowHeight`, `details`, and visual `sort` hints are ignored for backward compatibility, including in armed snapshots and retained results; the app never uses them. Editable starter and local model files have been cleaned of those hints; immutable history is preserved.

Draft data bindings can be previewed against retained data (marked “draft schema”) without recalculating outputs. Tables are readouts. Model outputs perform calculations; schema declarations expose them. `More → Data schema example` inserts an example. Editor shortcuts: Ctrl+F find, Ctrl+Z/Ctrl+Y undo/redo, Tab/Shift+Tab indent, with line numbers.

## News-driven reactions

News code can choose importance and route an update:

```text
input affected = tickers
input words = headline
param important_terms = ["halt", "merger"]
output notify = contains_any(words, important_terms)
output notify_symbols = affected
output notify_ttl_seconds = 120
```

The terms above are an example, not a built-in importance classifier. Replace `notify` with your own model expression and `notify_symbols` with your own affected-ticker list. Custom input objects can supply the consuming trading model's interests. `notify` must be boolean; notification routing needs 1–120 symbols. `notify_ttl_seconds` is the event's useful life (1–86400, default 120). The starter leaves notifications **off** until you define the importance rule.

Trading code declares `react news` (or uses **More → React to news** to insert it). New entries then require a fresh news-model event targeting that universe ticker as well as the trading model's normal buy rule and execution checks. Add `input news_rating News(rating, 120)` if the trade rule needs the symbol rating too. The optional news subscription and the numeric news dependency are separate declarations.

After rating, News retains events in SQLite `news_events` and atomically publishes `news-events.json` after symbol outputs are published. Repeating the same article/content/model combination does not repeat an event. Optional text output `notify_key` can identify a new model-defined state of the same article. Changing model code or article content creates a new identity. The desktop checks local notifications every second and wakes the paper evaluator before its next ordinary scan when needed; normal market/risk scans continue. Active worker processing latency still applies—this is not an exchange-speed feed.

Arming begins after the existing stream cursor, so historical events are not replayed. Cursors persist with the session; affected legs retain `newsTrigger`, and `NEWS_UPDATE` logs record events. A reaction is one evaluation opportunity, not a promised order: stale market data, warmup, cooldown, capacity and other guards can reject it. Unmatched, expired or already consumed events do not authorize entries. The published window holds the last 2000 events; the durable SQLite ledger retains all, but an offline consumer does not replay older events outside that window. Independent exits do not need a news notification. Neither the starter nor UI supplies a hidden buy/sell rule.

## Stock selection model

**Stocks** is a separate code-first workspace, with one utility row: Code, Results, Candidates, More, Save, Validate, Run and Use selection. Its source is `work/models/stock-selection.talg`. The old desk watchlist and historical backfill tools remain under More → Desk watchlist.

`param candidates = ["AAPL", "MSFT"]` defines the candidate pool. **Candidates** opens a searchable checkbox picker and edits that exact source declaration. Add accepts stock/ETF ticker symbols, including dotted symbols. The candidate pool is independent of the smaller execution universe; predictor and execution coverage are separate inputs. Predictor coverage is displayed; uncovered symbols require a compatible fitted predictor before arming.

The selection program runs independently for each candidate. It must declare boolean `output include`; optional numeric `output priority` sorts accepted stocks highest first, with candidate order breaking ties. Optional integer `param max_stocks` limits the resulting universe (1–120, default 120). The starter keeps candidates without inventing a stock-picking rule. All other outputs and data schemas are freely named within the shared language; the app sizes and displays them.

Bindings: `symbol`, `predictor_covered`, `news` (matching saved news-symbol outputs), `news_available`, `news_age_seconds` (-1 when unavailable), `retained` (the last retained ticker record, or an empty object), and `custom`. News age is exposed; selection code decides its useful lifetime. `work/models/stock-selection-inputs.json` may provide `defaults` and `symbols` objects; per-symbol values override defaults. Example:

```text
param candidates = ["AAPL", "MSFT"]
param max_stocks = 10
input ready = news_available
input age = news_age_seconds
input ratings = news
output include = ready and age < 120
output priority = field(ratings, "rating") if ready else 0
```

That example ranks the news model's own rating, not a built-in return estimate. Run reads retained local inputs and saves source, inputs, outputs, errors and selections under `work/stock-selection/run-*.json`. Per-symbol errors remain visible and block Use selection. Editing code or candidates invalidates the previous result for use. Save stores this independent model with history and disk-conflict checks.

**Use selection** copies a successfully evaluated, nonempty result into the trading model's `universe` draft, preserving custom code and other components. Save in Model applies it to the next explicitly armed session; the current armed universe stays frozen. Stocks does not place orders, change the desk watchlist, retrain the predictor or silently reconfigure an active strategy.

### All available candidates

In **Stocks → Candidates**, choose **All available**, or declare:

```text
param candidates = "all_available"
param max_stocks = 20
input supported = execution_supported
input covered = predictor_covered
output include = supported and covered
output priority = 0
```

`all_available` is resolved at Run, so it remains dynamic when assets are added or removed. `"all available"` and `"all"` are accepted aliases. It means the complete broker catalogue of active, tradable US equities (stocks and ETFs), not the desk watchlist, predictor coverage, or the current search results. Selecting or clearing the shown checkboxes switches back to an explicit list.

The desktop fetches `/v2/assets?status=active&asset_class=us_equity` using the connected account and retains the public asset catalogue in `work/stocks/available-assets.json`. **More → Refresh available stocks** refreshes it explicitly. All-available Run refreshes while connected; disconnected runs require a previously saved catalogue and show its timestamp. Missing catalogues produce an error; there is no silent fallback to a small local list. CLI runs resolve the saved catalogue. Fetch failures leave the previous file intact and fail that requested refresh/run.

Every available candidate is evaluated before the model's `include`, `priority`, and `max_stocks` rules determine the result. The 120-stock execution limit is not a candidate-scan limit. `asset` exposes broker asset metadata, `predictor_covered` reports fitted-predictor coverage, and `execution_supported` reports whether the current trading adapter supports the ticker syntax. The execution adapter still requires 1–120 supported tickers and predictor coverage; it is not broadened by changing the selection pool. No order endpoint is called when fetching available stocks.


## Model profiles and pinned dependencies

News, Model (trading), and Stocks each have a profile picker in the existing utility row. **More → New profile from saved model** creates an independent named copy of that model's files and links. Save or reload a draft before switching. Selecting a profile in one tab never changes another model's dependency.

**More → Profile links** pins a trading profile to a particular saved news revision and stock-selection revision. Stock profiles can pin their own news revision. Choose “Keep” to retain a lock, or a profile's “latest saved” entry to explicitly repin it. Links are a directed graph: Trade → Stocks → News and Trade → News. Trading and stock selection may intentionally use different news revisions. Source, settings, custom inputs and nested links are captured; later edits do not move existing links. Default legacy dependencies are captured the first time a profile is used. Profiles define data and calculations only; the app continues to own all presentation.

`work/model-profiles/profiles.json` is the readable registry: independent selected IDs, display names, file paths, dependency revision hashes and current published heads. Default profiles use existing model files. New profiles own their files under `work/model-profiles/<kind>/<id>/`. A revision's `manifest.json` contains the exact source/configuration/input text and dependency hashes, stored under `work/model-profiles/revisions/<sha256>/`. The manifest is verified against its content hash before use. Editing a manifest in place is invalid; edit the profile files and create a new revision instead.

News revisions share retained raw evidence and encoded vectors from the news archive, but have separate SQLite ratings/history/events and tensor/symbol output files in their revision's `runtime/` directory. **News → Run** ingests/encodes retained news and rates the selected saved revision. The continuous worker (`tools/start-news-tensor.ps1`) refreshes published news heads, pinned dependencies, and active session dependencies as new evidence arrives. **More → Run linked news** in Model or Stocks refreshes the pinned news revision even if its editable profile has moved on. News-reactive sessions require their pinned event stream to be initialized before arming, so a first historical backfill cannot become a new-event backlog. Keep that worker running for automatic fresh ratings; a one-off Run is not a continuous subscription. Output provenance includes `profile_revision`. The Java news adapter checks the pinned revision as well as source hash, output types, and age. Stock selection reads its own pinned news revision and exposes its age to code.

Stocks Run records the selection profile revision. **Use selection** requires saved code, a current result, and a match with the trading profile's pinned stock revision. If they differ, repin under Model → More → Profile links, then Run Stocks again. It copies the selected universe to the trading draft; the model's `universe` remains explicit and may also be edited directly. Stock profiles retain a `trading` configuration file for predictor coverage; change that model file when selecting a different predictor.

Arming captures the selected trading revision and complete dependency graph along with the existing model and fitted-predictor snapshots. The confirmation is tied to that prepared revision. Switching profiles, editing code, and repinning saved links affect the next arm. Existing sessions retain their revisions through restart and daily repeat, including their news cursor. Unrelated profiles remain editable; a legacy active session without profile provenance retains its old compatibility behavior and protects the default trade file until stopped. A Tickers readout is scoped to the selected profile's retained session; Run can preview another profile on retained observations with provenance. None of these operations authorizes new orders or changes PAPER routing, reconciliation, or execution guards.

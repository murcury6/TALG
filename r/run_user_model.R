# Run a trusted, user-authored local R model against observed Alpaca bars.
source(file.path("r", "live_portfolio.R"), local = TRUE)

validate_model_rows <- function(rows) {
  required <- c("symbol", "as_of_utc", "metric", "value", "unit", "horizon", "model_version")
  if (!is.data.frame(rows) || !identical(names(rows), required) ||
      nrow(rows) < 1L || nrow(rows) > 10000L)
    stop("talg_model must return 1–10,000 rows with exactly: ",
         paste(required, collapse = ", "), call. = FALSE)
  if (inherits(rows$as_of_utc, "POSIXt"))
    rows$as_of_utc <- format(rows$as_of_utc, "%Y-%m-%dT%H:%M:%SZ", tz = "UTC")
  rows$symbol <- as.character(rows$symbol)
  rows$as_of_utc <- as.character(rows$as_of_utc)
  rows$metric <- as.character(rows$metric)
  rows$unit <- as.character(rows$unit)
  rows$horizon <- as.character(rows$horizon)
  rows$model_version <- as.character(rows$model_version)
  if (!is.numeric(rows$value) || any(!is.finite(rows$value)) ||
      any(!grepl("^[A-Z][A-Z0-9.-]{0,9}$", rows$symbol)) ||
      any(!grepl("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$", rows$as_of_utc)) ||
      any(is.na(as.POSIXct(rows$as_of_utc, format = "%Y-%m-%dT%H:%M:%SZ", tz = "UTC"))) ||
      any(!nzchar(rows$metric) | nchar(rows$metric) > 80) ||
      any(!nzchar(rows$unit) | nchar(rows$unit) > 30) ||
      any(!nzchar(rows$horizon) | nchar(rows$horizon) > 30) ||
      any(!nzchar(rows$model_version) | nchar(rows$model_version) > 80))
    stop("Model output has invalid symbols, timestamps, values or metadata.", call. = FALSE)
  rows
}

run_user_model <- function(name, symbol_text, output_path,
                           model_root = file.path("work", "models"), observed_data = NULL) {
  if (!grepl("^[a-z][a-z0-9_]{0,39}$", name)) stop("Invalid model name.", call. = FALSE)
  tickers <- strsplit(symbol_text, ",", fixed = TRUE)[[1L]]
  if (length(tickers) < 1L || length(tickers) > 5L || anyDuplicated(tickers) ||
      any(!grepl("^[A-Z][A-Z0-9.-]{0,9}$", tickers)))
    stop("Enter one to five distinct model input stocks.", call. = FALSE)
  source_path <- file.path(model_root, paste0(name, ".R"))
  if (!file.exists(source_path)) stop(paste("Model script not found:", name), call. = FALSE)
  observed <- if (is.null(observed_data))
    load_live_stock("1Y", tickers[[1L]], "1Day", tickers[-1L]) else observed_data
  if (!is.null(observed$stock_error)) stop(observed$stock_error, call. = FALSE)
  series <- c(setNames(list(observed$stock_bars), tickers[[1L]]), observed$comparison_bars)
  if (any(vapply(series, nrow, integer(1)) < 2L))
    stop("Insufficient observed Alpaca bars for this model run.", call. = FALSE)
  environment <- new.env(parent = globalenv())
  sys.source(source_path, envir = environment)
  if (!is.function(environment$talg_model))
    stop("The model script must define talg_model(series).", call. = FALSE)
  rows <- validate_model_rows(environment$talg_model(series))
  last_observed <- vapply(series, function(bars) as.numeric(max(bars$date)), numeric(1))
  input_last_bar <- vapply(last_observed, function(seconds)
    format(as.POSIXct(seconds, origin = "1970-01-01", tz = "UTC"),
           "%Y-%m-%dT%H:%M:%SZ", tz = "UTC"), character(1))
  payload <- list(model = name, script_md5 = unname(tools::md5sum(source_path)),
                  input_source = "Alpaca stock bars", feed = observed$chart_feed,
                  bar_interval = "1Day", input_symbols = tickers,
                  input_last_bar_utc = as.list(input_last_bar),
                  observed_through_utc = format(as.POSIXct(min(last_observed),
                    origin = "1970-01-01", tz = "UTC"), "%Y-%m-%dT%H:%M:%SZ", tz = "UTC"),
                  rows = rows)
  jsonlite::write_json(payload, output_path, dataframe = "rows", auto_unbox = TRUE,
                       pretty = TRUE, na = "null")
  invisible(payload)
}

if (sys.nframe() == 0L) {
  args <- commandArgs(trailingOnly = TRUE)
  tryCatch({
    if (length(args) != 4L) stop("Invalid model run request.", call. = FALSE)
    run_user_model(args[[1L]], args[[2L]], args[[3L]], observed_data = readRDS(args[[4L]]))
  }, error = function(error) {
    cat("TALG_ERROR:", conditionMessage(error), "\n", sep = "")
    quit(save = "no", status = 1L)
  })
}

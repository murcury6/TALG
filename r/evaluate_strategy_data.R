# Convert observed Alpaca bars and trusted local indicators into bounded numeric inputs.
source(file.path("r", "user_indicators.R"), local = TRUE)

evaluate_strategy_data <- function(observed, input_definitions,
                                   output_path, indicator_root = file.path("work", "indicators")) {
  if (!is.null(observed$stock_error)) stop(observed$stock_error, call. = FALSE)
  bars <- observed$stock_bars
  if (!is.data.frame(bars) || nrow(bars) < 35L || !"date" %in% names(bars))
    stop("At least 35 observed bars are required for strategy indicators.", call. = FALSE)
  bars <- bars[order(bars$date), , drop = FALSE]
  fields <- c("open", "high", "low", "close", "volume", "vwap", "trades")
  values <- list()
  for (field in fields) if (field %in% names(bars)) {
    value <- as.numeric(tail(bars[[field]], 1L))
    if (length(value) == 1L && is.finite(value)) values[[field]] <- value
  }
  for (input in input_definitions) {
    alias <- input$alias
    name <- input$name
    if (!is.character(alias) || length(alias) != 1L ||
        !grepl("^[a-z][a-z0-9_]{0,39}$", alias)) stop("Invalid strategy input alias.", call. = FALSE)
    series <- load_user_indicator(name, bars, indicator_root)
    latest <- tail(series, 1L)
    if (!is.finite(latest))
      stop(paste("Indicator", name, "has no finite value at the last observed bar."), call. = FALSE)
    values[[alias]] <- as.numeric(latest)
  }
  last_time <- as.POSIXct(tail(bars$date, 1L), tz = "UTC")
  payload <- list(symbol = observed$stock_symbols[[1L]],
                  as_of_utc = format(last_time, "%Y-%m-%dT%H:%M:%SZ", tz = "UTC"),
                  feed = observed$chart_feed, bar_count = nrow(bars), values = values)
  jsonlite::write_json(payload, output_path, auto_unbox = TRUE, pretty = TRUE, na = "null")
  invisible(payload)
}

if (sys.nframe() == 0L) {
  args <- commandArgs(trailingOnly = TRUE)
  tryCatch({
    if (length(args) != 3L) stop("Invalid strategy data request.", call. = FALSE)
    observed <- readRDS(args[[1L]])
    inputs <- jsonlite::fromJSON(args[[2L]], simplifyVector = FALSE)
    if (!is.list(inputs) || length(inputs) > 20L)
      stop("Invalid strategy indicator list.", call. = FALSE)
    evaluate_strategy_data(observed, inputs, args[[3L]])
  }, error = function(error) {
    cat("TALG_ERROR:", conditionMessage(error), "\n", sep = "")
    quit(save = "no", status = 1L)
  })
}

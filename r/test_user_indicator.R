# Evaluate a saved indicator on observed Alpaca bars without credential variables.
source(file.path("r", "user_indicators.R"), local = TRUE)

test_user_indicator <- function(name, observed, output_path,
                                root = file.path("work", "indicators")) {
  if (!is.null(observed$stock_error)) stop(observed$stock_error, call. = FALSE)
  bars <- observed$stock_bars
  if (!is.data.frame(bars) || nrow(bars) < 2L || !"date" %in% names(bars))
    stop("At least two observed Alpaca bars are required to test an indicator.", call. = FALSE)
  bars <- bars[order(bars$date), , drop = FALSE]
  values <- load_user_indicator(name, bars, root)
  stamp <- function(date) format(as.POSIXct(date, tz = "UTC"),
                                 "%Y-%m-%dT%H:%M:%SZ", tz = "UTC")
  last <- nrow(bars)
  shown <- seq.int(max(1L, last - 19L), last)
  preview <- data.frame(as_of_utc = vapply(bars$date[shown], stamp, character(1)),
                        close = if ("close" %in% names(bars)) as.numeric(bars$close[shown])
                                else rep(NA_real_, length(shown)),
                        indicator = values[shown])
  payload <- list(name = name, symbol = observed$stock_symbols[[1L]],
                  feed = observed$chart_feed, bar_interval = "1Day",
                  observed_through_utc = stamp(bars$date[[last]]),
                  bar_count = last, finite_count = sum(is.finite(values)),
                  last_value = if (is.finite(values[[last]])) values[[last]] else NULL,
                  preview = preview)
  jsonlite::write_json(payload, output_path, dataframe = "rows", auto_unbox = TRUE,
                       pretty = TRUE, na = "null")
  invisible(payload)
}

if (sys.nframe() == 0L) {
  args <- commandArgs(trailingOnly = TRUE)
  tryCatch({
    if (length(args) != 3L) stop("Invalid indicator test request.", call. = FALSE)
    test_user_indicator(args[[1L]], readRDS(args[[2L]]), args[[3L]])
  }, error = function(error) {
    cat("TALG_ERROR:", conditionMessage(error), "\n", sep = "")
    quit(save = "no", status = 1L)
  })
}

talg_model <- function(series) {
  # Working research baseline from observed daily bars; NOT a forecast.
  # Replace this calculation with your statistical model when ready.
  do.call(rbind, lapply(names(series), function(symbol) {
    bars <- series[[symbol]]
    close <- tail(as.numeric(bars$close), 21L)
    if (length(close) < 21L || any(!is.finite(close)) || any(head(close, -1L) == 0))
      stop(paste("Need 21 valid daily closes for", symbol))
    daily_return <- diff(close) / head(close, -1L)
    data.frame(
      symbol = symbol,
      as_of_utc = format(as.POSIXct(max(bars$date), tz = "UTC"),
                         "%Y-%m-%dT%H:%M:%SZ", tz = "UTC"),
      metric = "historical_mean_return_20",
      value = mean(daily_return), unit = "fraction per daily bar",
      horizon = "past 20 daily bars", model_version = "observed_baseline_v1",
      stringsAsFactors = FALSE
    )
  }))
}

required_columns <- list(
  holdings = c("as_of_utc", "symbol", "company", "sector", "shares",
               "average_cost", "current_price", "previous_close"),
  portfolio = c("date", "portfolio_value", "net_contributions", "benchmark_value"),
  stocks = c("date", "symbol", "close")
)

read_contract_csv <- function(path, expected, label) {
  if (!file.exists(path)) stop(sprintf("%s file not found: %s", label, path))
  data <- read.csv(path, stringsAsFactors = FALSE, check.names = FALSE)
  if (!identical(names(data), expected)) {
    stop(sprintf("Unexpected %s header. Expected: %s", label, paste(expected, collapse = ",")))
  }
  if (nrow(data) == 0L) stop(sprintf("%s data is empty", label))
  data
}

load_portfolio_data <- function(holdings_path, portfolio_path, stocks_path) {
  holdings <- read_contract_csv(holdings_path, required_columns$holdings, "holdings")
  portfolio <- read_contract_csv(portfolio_path, required_columns$portfolio, "portfolio history")
  stocks <- read_contract_csv(stocks_path, required_columns$stocks, "stock history")

  holdings$as_of_utc <- as.POSIXct(holdings$as_of_utc, format = "%Y-%m-%dT%H:%M:%SZ", tz = "UTC")
  holdings$symbol <- trimws(toupper(holdings$symbol))
  holdings$company <- trimws(holdings$company)
  holdings$sector <- trimws(holdings$sector)
  portfolio$date <- as.Date(portfolio$date)
  stocks$date <- as.Date(stocks$date)
  stocks$symbol <- trimws(toupper(stocks$symbol))

  if (anyNA(holdings$as_of_utc) || anyNA(portfolio$date) || anyNA(stocks$date)) {
    stop("All timestamps and dates must be valid")
  }
  if (anyDuplicated(holdings$symbol)) stop("Holdings must contain one row per symbol")
  if (anyDuplicated(portfolio$date)) stop("Portfolio history dates must be unique")
  if (anyDuplicated(paste(stocks$date, stocks$symbol))) {
    stop("Stock history must contain one close per symbol and date")
  }
  if (is.unsorted(portfolio$date, strictly = TRUE)) stop("Portfolio dates must be ascending")
  if (any(!grepl("^[A-Z][A-Z0-9.-]{0,9}$", holdings$symbol))) stop("Invalid holding symbol")
  if (!all(holdings$symbol %in% stocks$symbol)) stop("Every holding needs stock history")

  holding_numbers <- c("shares", "average_cost", "current_price", "previous_close")
  if (any(vapply(holdings[holding_numbers], function(x) any(!is.finite(x) | x <= 0), logical(1)))) {
    stop("Holding shares and prices must be positive and finite")
  }
  portfolio_numbers <- c("portfolio_value", "net_contributions", "benchmark_value")
  if (any(vapply(portfolio[portfolio_numbers], function(x) any(!is.finite(x) | x <= 0), logical(1)))) {
    stop("Portfolio values and contributions must be positive and finite")
  }
  if (any(!is.finite(stocks$close) | stocks$close <= 0)) stop("Stock closes must be positive and finite")

  holdings$market_value <- holdings$shares * holdings$current_price
  holdings$cost_basis <- holdings$shares * holdings$average_cost
  holdings$unrealized_pnl <- holdings$market_value - holdings$cost_basis
  holdings$total_return_pct <- holdings$current_price / holdings$average_cost - 1
  holdings$daily_pnl <- holdings$shares * (holdings$current_price - holdings$previous_close)
  holdings$day_change_pct <- holdings$current_price / holdings$previous_close - 1
  holdings$weight <- holdings$market_value / sum(holdings$market_value)
  holdings <- holdings[order(-holdings$market_value), ]

  portfolio$return <- c(NA_real_, diff(portfolio$portfolio_value) / head(portfolio$portfolio_value, -1L))
  portfolio$benchmark_return <- c(NA_real_, diff(portfolio$benchmark_value) / head(portfolio$benchmark_value, -1L))
  portfolio$portfolio_index <- 100 * portfolio$portfolio_value / portfolio$portfolio_value[[1L]]
  portfolio$benchmark_index <- 100 * portfolio$benchmark_value / portfolio$benchmark_value[[1L]]
  portfolio$drawdown <- portfolio$portfolio_value / cummax(portfolio$portfolio_value) - 1

  list(holdings = holdings, portfolio = portfolio, stocks = stocks)
}

period_start <- function(dates, period) {
  last <- max(dates)
  switch(period,
    "1M" = last - 31,
    "3M" = last - 92,
    "6M" = last - 183,
    "YTD" = as.Date(sprintf("%s-01-01", format(last, "%Y"))),
    "1Y" = last - 366,
    min(dates)
  )
}

filter_period <- function(data, period) {
  data[data$date >= period_start(data$date, period), , drop = FALSE]
}

rolling_mean <- function(x, window) {
  if (length(x) < window) return(rep(NA_real_, length(x)))
  as.numeric(stats::filter(x, rep(1 / window, window), sides = 1))
}

rolling_sd <- function(x, window) {
  result <- rep(NA_real_, length(x))
  if (length(x) >= window) {
    for (index in window:length(x)) result[[index]] <- stats::sd(x[(index - window + 1L):index])
  }
  result
}

ema <- function(x, window) {
  result <- rep(NA_real_, length(x))
  if (!length(x)) return(result)
  alpha <- 2 / (window + 1)
  result[[1L]] <- x[[1L]]
  if (length(x) > 1L) {
    for (index in 2:length(x)) result[[index]] <- alpha * x[[index]] + (1 - alpha) * result[[index - 1L]]
  }
  result
}

rsi <- function(close, window = 7L) {
  change <- c(NA_real_, diff(close))
  gain <- pmax(change, 0, na.rm = FALSE)
  loss <- pmax(-change, 0, na.rm = FALSE)
  gain[[1L]] <- 0
  loss[[1L]] <- 0
  average_gain <- rolling_mean(gain, window)
  average_loss <- rolling_mean(loss, window)
  ratio <- average_gain / average_loss
  result <- 100 - 100 / (1 + ratio)
  result[is.finite(average_gain) & average_loss == 0] <- 100
  result
}

stock_indicators <- function(stock_data) {
  stock_data <- stock_data[order(stock_data$date), , drop = FALSE]
  close <- stock_data$close
  stock_data$sma5 <- rolling_mean(close, 5L)
  stock_data$sma10 <- rolling_mean(close, 10L)
  stock_data$ema5 <- ema(close, 5L)
  middle <- rolling_mean(close, 10L)
  spread <- rolling_sd(close, 10L)
  stock_data$bollinger_upper <- middle + 2 * spread
  stock_data$bollinger_lower <- middle - 2 * spread
  stock_data$rsi7 <- rsi(close, 7L)
  stock_data$macd <- ema(close, 5L) - ema(close, 10L)
  stock_data$macd_signal <- ema(stock_data$macd, 4L)
  stock_data$daily_return <- c(NA_real_, diff(close) / head(close, -1L))
  stock_data$volatility10 <- rolling_sd(stock_data$daily_return, 10L) * sqrt(252)
  stock_data
}

portfolio_metrics <- function(data) {
  holdings <- data$holdings
  history <- data$portfolio
  daily <- history$return[is.finite(history$return)]
  total_value <- sum(holdings$market_value)
  unrealized <- sum(holdings$unrealized_pnl)
  daily_pnl <- sum(holdings$daily_pnl)
  list(
    total_value = total_value,
    unrealized_pnl = unrealized,
    unrealized_pct = unrealized / sum(holdings$cost_basis),
    daily_pnl = daily_pnl,
    daily_pct = daily_pnl / (total_value - daily_pnl),
    max_drawdown = min(history$drawdown, na.rm = TRUE),
    annual_volatility = if (length(daily) > 1L) stats::sd(daily) * sqrt(252) else NA_real_,
    largest_weight = max(holdings$weight),
    effective_positions = 1 / sum(holdings$weight^2)
  )
}

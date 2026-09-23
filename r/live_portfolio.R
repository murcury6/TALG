# Read-only Alpaca account and market-data adapter for the Java portfolio workspace.

alpaca_request <- function(url, key, secret, query = list()) {
  request <- httr2::request(url)
  request <- httr2::req_headers(request,
    "APCA-API-KEY-ID" = key, "APCA-API-SECRET-KEY" = secret,
    "Accept" = "application/json")
  request <- httr2::req_timeout(request, 20)
  request <- httr2::req_error(request, is_error = function(response) FALSE)
  if (length(query)) request <- do.call(httr2::req_url_query, c(list(request), query))
  response <- tryCatch(httr2::req_perform(request), error = function(error) {
    stop("Alpaca could not be reached. Check your connection.", call. = FALSE)
  })
  status <- httr2::resp_status(response)
  if (status != 200L) {
    stop(sprintf("Alpaca returned HTTP %d. Check account mode, credentials and data feed.", status),
         call. = FALSE)
  }
  jsonlite::fromJSON(httr2::resp_body_string(response), simplifyVector = FALSE)
}

number_field <- function(item, field) {
  value <- item[[field]]
  if (is.null(value) || length(value) != 1L) return(NA_real_)
  result <- suppressWarnings(as.numeric(value))
  if (is.finite(result)) result else NA_real_
}

parse_positions <- function(raw) {
  if (!length(raw)) {
    return(data.frame(symbol = character(), qty = numeric(), price = numeric(),
                      value = numeric(), unrealized = numeric(), intraday = numeric(),
                      side = character(), asset_class = character()))
  }
  rows <- lapply(raw, function(position) {
    data.frame(
      symbol = as.character(position$symbol %||% ""),
      qty = number_field(position, "qty"),
      price = number_field(position, "current_price"),
      value = number_field(position, "market_value"),
      unrealized = number_field(position, "unrealized_pl"),
      intraday = number_field(position, "unrealized_intraday_pl"),
      side = as.character(position$side %||% ""),
      asset_class = as.character(position$asset_class %||% "")
    )
  })
  positions <- do.call(rbind, rows)
  positions[order(-abs(positions$value), positions$symbol), , drop = FALSE]
}

parse_history <- function(raw) {
  as_numbers <- function(items) vapply(items %||% list(), function(item) {
    if (is.null(item) || length(item) != 1L) return(NA_real_)
    suppressWarnings(as.numeric(item))
  }, numeric(1))
  times <- as_numbers(raw$timestamp)
  equity <- as_numbers(raw$equity)
  pnl <- as_numbers(raw$profit_loss)
  length_valid <- min(length(times), length(equity))
  if (!length_valid) return(data.frame(date = as.Date(character()), equity = numeric(), pnl = numeric()))
  times <- times[seq_len(length_valid)]
  equity <- equity[seq_len(length_valid)]
  pnl <- pnl[seq_len(min(length(pnl), length_valid))]
  if (length(pnl) < length_valid) pnl <- c(pnl, rep(NA_real_, length_valid - length(pnl)))
  history <- data.frame(date = as.Date(as.POSIXct(times, origin = "1970-01-01", tz = "UTC")),
                        equity = equity, pnl = pnl)
  history <- history[!is.na(history$date) & is.finite(history$equity) & history$equity > 0, ]
  history[order(history$date), , drop = FALSE]
}

parse_stock_bars <- function(raw) {
  bars <- raw$bars %||% list()
  if (!length(bars)) return(data.frame(date = as.POSIXct(character(), tz = "UTC"),
                                      open = numeric(), high = numeric(), low = numeric(),
                                      close = numeric(), volume = numeric(), vwap = numeric(),
                                      trades = numeric()))
  rows <- lapply(bars, function(bar) {
    data.frame(date = as.POSIXct(as.character(bar$t %||% ""),
                                format = "%Y-%m-%dT%H:%M:%OSZ", tz = "UTC"),
               open = number_field(bar, "o"), high = number_field(bar, "h"),
               low = number_field(bar, "l"), close = number_field(bar, "c"),
               volume = number_field(bar, "v"), vwap = number_field(bar, "vw"),
               trades = number_field(bar, "n"))
  })
  result <- do.call(rbind, rows)
  result <- result[!is.na(result$date) & is.finite(result$close) & result$close > 0, ]
  result[order(result$date), , drop = FALSE]
}

`%||%` <- function(left, right) if (is.null(left)) right else left

# Stock charts use market-data credentials and may show watchlist symbols that
# are not held in the Alpaca account. No account or position is inferred here.
load_live_stock <- function(period, symbol, bar_interval = "1Day", comparisons = character()) {
  key <- Sys.getenv("TALG_ALPACA_KEY")
  secret <- Sys.getenv("TALG_ALPACA_SECRET")
  feed <- Sys.getenv("TALG_STOCK_FEED", "iex")
  if (!nzchar(key) || !nzchar(secret)) stop("Connect Alpaca in Settings first.", call. = FALSE)
  if (!period %in% c("1D", "5D", "1M", "3M", "6M", "YTD", "1Y")) stop("Invalid history period.", call. = FALSE)
  if (!grepl("^[A-Z][A-Z0-9.-]{0,9}$", symbol)) stop("Choose a valid chart ticker.", call. = FALSE)
  if (!bar_interval %in% c("1Min", "5Min", "15Min", "1Hour", "1Day", "1Week"))
    stop("Invalid Alpaca bar interval.", call. = FALSE)
  if (length(comparisons) > 4L || any(!grepl("^[A-Z][A-Z0-9.-]{0,9}$", comparisons)) ||
      anyDuplicated(c(symbol, comparisons))) stop("Invalid comparison tickers.", call. = FALSE)
  if (!feed %in% c("iex", "sip", "delayed_sip")) stop("Invalid market data feed.", call. = FALSE)
  chart_feed <- if (feed == "delayed_sip") "iex" else feed
  start_date <- if (period == "YTD") paste0(format(Sys.Date(), "%Y"), "-01-01") else
    as.character(Sys.Date() - switch(period, "1D" = 3, "5D" = 10, "1M" = 45,
                                     "3M" = 105, "6M" = 195, "1Y" = 380))
  stock_bars <- parse_stock_bars(list())
  stock_error <- NULL
  comparison_bars <- list()
  fetch_bars <- function(ticker) {
    next_token <- NULL
    seen <- character()
    pages <- list()
    repeat {
      query <- list(timeframe = bar_interval, start = start_date, limit = 1000,
                    adjustment = "raw", feed = chart_feed, sort = "asc")
      if (!is.null(next_token)) query$page_token <- next_token
      response <- alpaca_request(paste0("https://data.alpaca.markets/v2/stocks/", ticker, "/bars"),
                                 key, secret, query)
      pages[[length(pages) + 1L]] <- parse_stock_bars(response)
      next_token <- response$next_page_token %||% NULL
      if (is.null(next_token) || !nzchar(next_token)) break
      if (next_token %in% seen || length(pages) >= 20L)
        stop("Alpaca bar history exceeded the 20,000-observation safety limit; shorten the range or widen bars.", call. = FALSE)
      seen <- c(seen, next_token)
    }
    result <- do.call(rbind, pages)
    result <- result[!duplicated(result$date), , drop = FALSE]
    if (period %in% c("1D", "5D") && nrow(result)) {
      dates <- tail(unique(as.Date(result$date, tz = "America/New_York")),
                    if (period == "1D") 1L else 5L)
      result <- result[as.Date(result$date, tz = "America/New_York") %in% dates, , drop = FALSE]
    }
    result
  }
  tryCatch({
    stock_bars <- fetch_bars(symbol)
    for (ticker in comparisons) {
      comparison_bars[[ticker]] <- fetch_bars(ticker)
      if (nrow(comparison_bars[[ticker]]) < 2L)
        stop(paste("Alpaca returned insufficient comparison bars for", ticker), call. = FALSE)
    }
  }, error = function(error) {
    stock_error <<- conditionMessage(error)
    stock_bars <<- parse_stock_bars(list())
    comparison_bars <<- list()
  })
  list(account = list(), positions = parse_positions(list()), history = parse_history(list()),
       stock_bars = stock_bars, comparison_bars = comparison_bars,
       stock_error = stock_error, stock_symbols = symbol,
       chart_feed = chart_feed, bar_interval = bar_interval, observed_at = Sys.time())
}

load_live_portfolio <- function(period, selected_stock = "", include_bars = FALSE) {
  key <- Sys.getenv("TALG_ALPACA_KEY")
  secret <- Sys.getenv("TALG_ALPACA_SECRET")
  mode <- Sys.getenv("TALG_ACCOUNT_MODE")
  feed <- Sys.getenv("TALG_STOCK_FEED", "iex")
  if (!nzchar(key) || !nzchar(secret)) stop("Connect Alpaca in Settings first.", call. = FALSE)
  if (!mode %in% c("paper", "live")) stop("Choose a paper or live account.", call. = FALSE)
  if (!period %in% c("1M", "3M", "6M", "YTD", "1Y")) stop("Invalid history period.", call. = FALSE)
  if (!feed %in% c("iex", "sip", "delayed_sip")) stop("Invalid market data feed.", call. = FALSE)
  account_host <- if (mode == "paper") "https://paper-api.alpaca.markets" else "https://api.alpaca.markets"
  account <- alpaca_request(paste0(account_host, "/v2/account"), key, secret)
  positions <- parse_positions(alpaca_request(paste0(account_host, "/v2/positions"), key, secret))
  history_query <- list(timeframe = "1D")
  if (period == "YTD") {
    history_query$start <- paste0(format(Sys.Date(), "%Y"), "-01-01T00:00:00Z")
  } else {
    history_query$period <- if (period == "1Y") "1A" else period
  }
  history_error <- NULL
  history <- tryCatch(
    parse_history(alpaca_request(paste0(account_host, "/v2/account/portfolio/history"),
                                 key, secret, history_query)),
    error = function(error) {
      history_error <<- conditionMessage(error)
      parse_history(list())
    })

  stock_bars <- data.frame(date = as.Date(character()), close = numeric(), volume = numeric())
  stock_error <- NULL
  chart_feed <- if (feed == "delayed_sip") "iex" else feed
  stock_symbols <- positions$symbol[positions$asset_class == "us_equity" &
                                    grepl("^[A-Z][A-Z0-9.-]{0,9}$", positions$symbol)]
  if (include_bars && length(stock_symbols)) {
    symbol <- if (selected_stock %in% stock_symbols) selected_stock else stock_symbols[[1L]]
    start_date <- if (period == "YTD") paste0(format(Sys.Date(), "%Y"), "-01-01") else
      as.character(Sys.Date() - switch(period, "1M" = 45, "3M" = 105,
                                       "6M" = 195, "1Y" = 380))
    tryCatch({
      bars <- alpaca_request(paste0("https://data.alpaca.markets/v2/stocks/", symbol, "/bars"),
                             key, secret,
                             list(timeframe = "1Day", start = start_date, limit = 1000,
                                  adjustment = "raw", feed = chart_feed))
      stock_bars <- parse_stock_bars(bars)
    }, error = function(error) {
      stock_error <<- conditionMessage(error)
    })
  }
  list(account = account, positions = positions, history = history, history_error = history_error,
       stock_bars = stock_bars, stock_error = stock_error, stock_symbols = stock_symbols, mode = mode,
       chart_feed = chart_feed, observed_at = Sys.time())
}

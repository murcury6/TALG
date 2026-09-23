repo_root <- normalizePath(file.path("..", "..", ".."), mustWork = TRUE)
source(file.path(repo_root, "r", "live_portfolio.R"))

testthat::test_that("live adapter rejects missing credentials before making a request", {
  old_key <- Sys.getenv("TALG_ALPACA_KEY", unset = NA_character_)
  old_secret <- Sys.getenv("TALG_ALPACA_SECRET", unset = NA_character_)
  on.exit({
    if (is.na(old_key)) Sys.unsetenv("TALG_ALPACA_KEY") else Sys.setenv(TALG_ALPACA_KEY = old_key)
    if (is.na(old_secret)) Sys.unsetenv("TALG_ALPACA_SECRET") else Sys.setenv(TALG_ALPACA_SECRET = old_secret)
  })
  Sys.unsetenv(c("TALG_ALPACA_KEY", "TALG_ALPACA_SECRET"))
  testthat::expect_error(load_live_portfolio("3M"), "Connect Alpaca")
})

testthat::test_that("Alpaca values are parsed without default positions or prices", {
  empty <- parse_positions(list())
  testthat::expect_equal(nrow(empty), 0L)
  testthat::expect_equal(nrow(parse_history(list())), 0L)

  positions <- parse_positions(list(list(
    symbol = "XYZ", qty = "2.5", current_price = "40.25",
    market_value = "100.625", unrealized_pl = "-3.50",
    unrealized_intraday_pl = "1.25", side = "long", asset_class = "us_equity"
  )))
  testthat::expect_equal(positions$symbol, "XYZ")
  testthat::expect_equal(positions$value, 100.625)
  testthat::expect_equal(positions$unrealized, -3.5)
  testthat::expect_true(is.na(number_field(list(), "current_price")))

  history <- parse_history(list(timestamp = list(1725580800, 1725667200),
                                equity = list(1000, 1015), profit_loss = list(0, 15)))
  testthat::expect_equal(nrow(history), 2L)
  testthat::expect_equal(history$equity[[2L]], 1015)
})

testthat::test_that("all chartable Alpaca bar fields remain observed or missing", {
  bars <- parse_stock_bars(list(bars = list(
    list(t = "2026-09-21T15:30:00Z", o = 100, h = 103, l = 99,
         c = 101, v = 1200, vw = 101.5, n = 83),
    list(t = "2026-09-22T15:30:00Z", c = 102, v = 950)
  )))
  testthat::expect_equal(names(bars), c("date", "open", "high", "low", "close",
                                        "volume", "vwap", "trades"))
  testthat::expect_equal(bars$volume, c(1200, 950))
  testthat::expect_equal(bars$vwap[[1L]], 101.5)
  testthat::expect_equal(bars$trades[[1L]], 83)
  testthat::expect_true(is.na(bars$vwap[[2L]]))
  testthat::expect_true(is.na(bars$open[[2L]]))
})

testthat::test_that("stock charts can load an unheld watchlist ticker", {
  keys <- c("TALG_ALPACA_KEY", "TALG_ALPACA_SECRET", "TALG_STOCK_FEED")
  previous <- Sys.getenv(keys, unset = NA_character_)
  on.exit(for (i in seq_along(keys)) {
    if (is.na(previous[[i]])) Sys.unsetenv(keys[[i]]) else
      do.call(Sys.setenv, setNames(list(previous[[i]]), keys[[i]]))
  })
  Sys.setenv(TALG_ALPACA_KEY = "test-key", TALG_ALPACA_SECRET = "test-secret",
             TALG_STOCK_FEED = "iex")
  adapter <- new.env(parent = globalenv())
  sys.source(file.path(repo_root, "r", "live_portfolio.R"), envir = adapter)
  adapter$alpaca_request <- function(url, key, secret, query) {
    testthat::expect_match(url, "/AAPL/bars$")
    testthat::expect_equal(query$feed, "iex")
    testthat::expect_equal(query$timeframe, "5Min")
    list(bars = list(list(t = "2026-09-21T04:00:00Z", c = 201.5, v = 1000)))
  }
  result <- adapter$load_live_stock("5D", "AAPL", "5Min")
  testthat::expect_equal(result$stock_symbols, "AAPL")
  testthat::expect_equal(result$stock_bars$close, 201.5)
  testthat::expect_equal(nrow(result$positions), 0L)
  testthat::expect_s3_class(result$stock_bars$date, "POSIXct")
})

testthat::test_that("stock bars paginate without silently dropping older observations", {
  keys <- c("TALG_ALPACA_KEY", "TALG_ALPACA_SECRET", "TALG_STOCK_FEED")
  previous <- Sys.getenv(keys, unset = NA_character_)
  on.exit(for (i in seq_along(keys)) {
    if (is.na(previous[[i]])) Sys.unsetenv(keys[[i]]) else
      do.call(Sys.setenv, setNames(list(previous[[i]]), keys[[i]]))
  })
  Sys.setenv(TALG_ALPACA_KEY = "test-key", TALG_ALPACA_SECRET = "test-secret",
             TALG_STOCK_FEED = "iex")
  adapter <- new.env(parent = globalenv())
  sys.source(file.path(repo_root, "r", "live_portfolio.R"), envir = adapter)
  adapter$alpaca_request <- function(url, key, secret, query) {
    if (is.null(query$page_token))
      list(bars = list(list(t = "2026-09-18T15:30:00Z", c = 200, v = 100)),
           next_page_token = "next")
    else {
      testthat::expect_equal(query$page_token, "next")
      list(bars = list(list(t = "2026-09-21T15:30:00Z", c = 201, v = 110)))
    }
  }
  result <- adapter$load_live_stock("5D", "AAPL", "1Day")
  testthat::expect_equal(nrow(result$stock_bars), 2L)
  testthat::expect_equal(result$stock_bars$close, c(200, 201))
})

testthat::test_that("a missing comparison is surfaced instead of drawing a partial chart", {
  keys <- c("TALG_ALPACA_KEY", "TALG_ALPACA_SECRET", "TALG_STOCK_FEED")
  previous <- Sys.getenv(keys, unset = NA_character_)
  on.exit(for (i in seq_along(keys)) {
    if (is.na(previous[[i]])) Sys.unsetenv(keys[[i]]) else
      do.call(Sys.setenv, setNames(list(previous[[i]]), keys[[i]]))
  })
  Sys.setenv(TALG_ALPACA_KEY = "test-key", TALG_ALPACA_SECRET = "test-secret",
             TALG_STOCK_FEED = "iex")
  adapter <- new.env(parent = globalenv())
  sys.source(file.path(repo_root, "r", "live_portfolio.R"), envir = adapter)
  adapter$alpaca_request <- function(url, key, secret, query) {
    if (grepl("/MSFT/bars$", url)) return(list(bars = list()))
    list(bars = list(list(t = "2026-09-18T15:30:00Z", c = 200, v = 100),
                     list(t = "2026-09-21T15:30:00Z", c = 201, v = 110)))
  }
  result <- adapter$load_live_stock("5D", "AAPL", "1Day", "MSFT")
  testthat::expect_equal(nrow(result$stock_bars), 0L)
  testthat::expect_match(result$stock_error, "MSFT")
})

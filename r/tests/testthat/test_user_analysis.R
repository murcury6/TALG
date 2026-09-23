repo_root <- normalizePath(file.path("..", "..", ".."), mustWork = TRUE)

testthat::test_that("trusted custom indicators must return observed-bar-aligned numbers", {
  old_directory <- getwd()
  setwd(repo_root)
  on.exit(setwd(old_directory), add = TRUE)
  viewer <- new.env(parent = globalenv())
  sys.source(file.path(repo_root, "r", "java_display.R"), envir = viewer)
  scripts <- tempfile("talg-indicator-test-")
  dir.create(scripts)
  on.exit(unlink(scripts, recursive = TRUE), add = TRUE)
  writeLines("talg_indicator <- function(bars) bars$close - mean(bars$close)",
             file.path(scripts, "deviation.R"))
  bars <- data.frame(date = as.POSIXct(c("2026-09-18", "2026-09-21"), tz = "UTC"),
                     close = c(100, 105), volume = c(10, 12))
  testthat::expect_equal(viewer$load_user_indicator("deviation", bars, scripts), c(-2.5, 2.5))
  testthat::expect_error(viewer$load_user_indicator("../other", bars, scripts), "Invalid")
  writeLines("talg_indicator <- function(bars) 1", file.path(scripts, "bad.R"))
  testthat::expect_error(viewer$load_user_indicator("bad", bars, scripts), "one numeric value per bar")
})

testthat::test_that("custom model output requires values, units, horizons and provenance", {
  old_directory <- getwd()
  setwd(repo_root)
  on.exit(setwd(old_directory), add = TRUE)
  runner <- new.env(parent = globalenv())
  sys.source(file.path(repo_root, "r", "run_user_model.R"), envir = runner)
  rows <- data.frame(symbol = "AAPL", as_of_utc = "2026-09-21T20:00:00Z",
                     metric = "expected_return", value = 0.03, unit = "fraction",
                     horizon = "5D", model_version = "v1")
  testthat::expect_equal(runner$validate_model_rows(rows)$value, 0.03)
  rows$value <- Inf
  testthat::expect_error(runner$validate_model_rows(rows), "invalid")
})

testthat::test_that("user model runs on real-shaped input bars and emits traceable rows", {
  old_directory <- getwd()
  setwd(repo_root)
  on.exit(setwd(old_directory), add = TRUE)
  runner <- new.env(parent = globalenv())
  sys.source(file.path(repo_root, "r", "run_user_model.R"), envir = runner)
  scripts <- tempfile("talg-model-test-")
  dir.create(scripts)
  on.exit(unlink(scripts, recursive = TRUE), add = TRUE)
  writeLines(c(
    "talg_model <- function(series) {",
    "  aapl <- series[['AAPL']]",
    "  msft <- series[['MSFT']]",
    "  data.frame(symbol = c('AAPL', 'MSFT'),",
    "             as_of_utc = rep('2026-09-21T20:00:00Z', 2),",
    "             metric = rep('expected_return', 2),",
    "             value = c(tail(aapl$close, 1) / aapl$close[1] - 1,",
    "                       tail(msft$close, 1) / msft$close[1] - 1),",
    "             unit = rep('fraction', 2), horizon = rep('5D', 2),",
    "             model_version = rep('test-v1', 2))",
    "}"), file.path(scripts, "research.R"))
  bars <- data.frame(date = as.POSIXct(c("2026-09-18", "2026-09-21"), tz = "UTC"),
                     close = c(100, 105), volume = c(10, 12))
  other <- data.frame(date = bars$date, close = c(200, 220), volume = c(20, 25))
  runner$load_live_stock <- function(period, symbol, bar_interval, comparisons) {
    testthat::expect_equal(comparisons, "MSFT")
    list(stock_bars = bars, comparison_bars = list(MSFT = other),
         stock_error = NULL, chart_feed = "iex")
  }
  output <- tempfile(fileext = ".json")
  on.exit(unlink(output), add = TRUE)
  testthat::expect_silent(runner$run_user_model("research", "AAPL,MSFT", output, scripts))
  result <- jsonlite::fromJSON(output)
  testthat::expect_equal(result$rows$value, c(0.05, 0.10))
  testthat::expect_equal(result$input_symbols, c("AAPL", "MSFT"))
  testthat::expect_equal(names(result$input_last_bar_utc), c("AAPL", "MSFT"))
  testthat::expect_equal(result$input_source, "Alpaca stock bars")
  testthat::expect_match(result$script_md5, "^[0-9a-f]{32}$")
})

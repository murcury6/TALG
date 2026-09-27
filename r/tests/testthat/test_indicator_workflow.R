repo_root <- normalizePath(file.path("..", "..", ".."), mustWork = TRUE)

testthat::test_that("saved indicator is evaluated only on supplied observed bars", {
  previous <- getwd()
  setwd(repo_root)
  on.exit(setwd(previous), add = TRUE)
  runner <- new.env(parent = globalenv())
  sys.source(file.path("r", "test_user_indicator.R"), envir = runner)
  scripts <- tempfile("talg-indicator-tests-")
  dir.create(scripts)
  on.exit(unlink(scripts, recursive = TRUE), add = TRUE)
  writeLines("talg_indicator <- function(bars) bars$close - bars$open",
             file.path(scripts, "bar_change.R"))
  bars <- data.frame(date = as.POSIXct("2026-01-01", tz = "UTC") + 86400 * 0:3,
                     open = c(10, 12, 11, 14), close = c(11, 11, 13, 16))
  observed <- list(stock_error = NULL, stock_bars = bars,
                   stock_symbols = "AAPL", chart_feed = "iex")
  output <- tempfile(fileext = ".json")
  on.exit(unlink(output), add = TRUE)
  result <- runner$test_user_indicator("bar_change", observed, output, scripts)
  testthat::expect_equal(result$last_value, 2)
  testthat::expect_equal(result$finite_count, 4)
  testthat::expect_equal(result$preview$indicator, c(1, -1, 2, 2))
  testthat::expect_true(file.exists(output))
  testthat::expect_equal(jsonlite::read_json(output)$symbol, "AAPL")
  testthat::expect_error(runner$test_user_indicator("missing", observed, output, scripts),
                         "Indicator not found")
})

testthat::test_that("model starter computes a clearly historical metric from observed bars", {
  previous <- getwd()
  setwd(repo_root)
  on.exit(setwd(previous), add = TRUE)
  runner <- new.env(parent = globalenv())
  sys.source(file.path("r", "run_user_model.R"), envir = runner)
  scripts <- tempfile("talg-model-tests-")
  dir.create(scripts)
  on.exit(unlink(scripts, recursive = TRUE), add = TRUE)
  file.copy(file.path("r", "model_starters", "observed_return_baseline.R"),
            file.path(scripts, "baseline.R"))
  bars <- data.frame(date = as.POSIXct("2026-01-01", tz = "UTC") + 86400 * 0:29,
                     close = 100 + 0:29)
  observed <- list(stock_error = NULL, stock_bars = bars,
                   comparison_bars = list(), chart_feed = "iex")
  output <- tempfile(fileext = ".json")
  on.exit(unlink(output), add = TRUE)
  result <- runner$run_user_model("baseline", "AAPL", output, scripts, observed)
  testthat::expect_equal(result$rows$metric, "historical_mean_return_20")
  testthat::expect_equal(result$rows$horizon, "past 20 daily bars")
  testthat::expect_true(is.finite(result$rows$value))
  testthat::expect_equal(result$input_source, "Alpaca stock bars")
})

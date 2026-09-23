repo_root <- normalizePath(file.path("..", "..", ".."), mustWork = TRUE)

testthat::test_that("custom desk indicators and strategy data use observed bars", {
  old_directory <- getwd()
  setwd(repo_root)
  on.exit(setwd(old_directory), add = TRUE)
  source(file.path("r", "evaluate_strategy_data.R"), local = TRUE)
  bars <- data.frame(date = as.POSIXct(seq(Sys.Date() - 79, Sys.Date(), by = "day"), tz = "UTC"),
                     open = seq(99, 178), high = seq(101, 180), low = seq(98, 177),
                     close = seq(100, 179), volume = seq(1000, 1790, by = 10),
                     vwap = seq(100, 179), trades = seq(50, 129))
  observed <- list(stock_error = NULL, stock_bars = bars,
                   stock_symbols = "AAPL", chart_feed = "iex")
  output <- tempfile(fileext = ".json")
  on.exit(unlink(output), add = TRUE)
  result <- evaluate_strategy_data(observed,
    list(list(alias = "trend", name = "ema_34"),
         list(alias = "pressure", name = "volume_ratio_20")), output)
  testthat::expect_true(file.exists(output))
  testthat::expect_equal(result$values$close, 179)
  testthat::expect_true(is.finite(result$values$trend))
  testthat::expect_gt(result$values$pressure, 1)
  testthat::expect_error(evaluate_strategy_data(observed,
    list(list(alias = "bad", name = "not_there")), output), "not found")
})

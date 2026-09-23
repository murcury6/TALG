repo_root <- normalizePath(file.path("..", "..", ".."), mustWork = TRUE)
source(file.path(repo_root, "r", "portfolio_app", "R", "portfolio_data.R"))

testthat::test_that("portfolio contracts load and derive holding metrics", {
  data <- load_portfolio_data(
    file.path(repo_root, "examples", "portfolio_holdings.csv"),
    file.path(repo_root, "examples", "portfolio_history.csv"),
    file.path(repo_root, "examples", "stock_history.csv")
  )
  testthat::expect_equal(nrow(data$holdings), 5L)
  testthat::expect_equal(sum(data$holdings$weight), 1, tolerance = 1e-12)
  testthat::expect_true(all(data$holdings$market_value > 0))
  testthat::expect_true(all(data$portfolio$drawdown <= 0))
})

testthat::test_that("indicator calculations preserve the input rows", {
  data <- load_portfolio_data(
    file.path(repo_root, "examples", "portfolio_holdings.csv"),
    file.path(repo_root, "examples", "portfolio_history.csv"),
    file.path(repo_root, "examples", "stock_history.csv")
  )
  apple <- data$stocks[data$stocks$symbol == "AAPL", ]
  indicators <- stock_indicators(apple)
  testthat::expect_equal(nrow(indicators), nrow(apple))
  testthat::expect_true(is.finite(tail(indicators$sma5, 1L)))
  testthat::expect_true(is.finite(tail(indicators$rsi7, 1L)))
  testthat::expect_true(is.finite(tail(indicators$macd, 1L)))
})

testthat::test_that("period filtering never looks beyond the latest observation", {
  dates <- data.frame(date = seq(as.Date("2025-01-01"), by = "day", length.out = 500L))
  filtered <- filter_period(dates, "1M")
  testthat::expect_lte(nrow(filtered), 32L)
  testthat::expect_equal(max(filtered$date), max(dates$date))
})

testthat::test_that("an indicator longer than available history stays unavailable without crashing", {
  result <- rolling_mean(1:5, 20L)
  testthat::expect_length(result, 5L)
  testthat::expect_true(all(is.na(result)))
})

repo_root <- normalizePath(file.path("..", "..", ".."), mustWork = TRUE)

testthat::test_that("R stock chart renders selected ticker without account metrics", {
  old_directory <- getwd()
  setwd(repo_root)
  on.exit(setwd(old_directory), add = TRUE)
  viewer <- new.env(parent = globalenv())
  sys.source(file.path(repo_root, "r", "java_display.R"), envir = viewer)
  viewer$selected_view <- "Stock lab"
  viewer$selected_stock <- "AAPL"
  viewer$selected_indicator <- "none"
  viewer$selected_overlays <- character()
  viewer$output_path <- tempfile(fileext = ".png")
  on.exit(unlink(viewer$output_path))
  bars <- data.frame(date = seq(Sys.Date() - 49, Sys.Date(), by = "day"),
                     close = seq(100, 149), volume = rep(1000, 50))
  data <- list(account = list(), positions = viewer$parse_positions(list()),
               history = viewer$parse_history(list()), stock_bars = bars,
               stock_error = NULL)
  testthat::expect_silent(viewer$render_view(data))
  testthat::expect_true(file.exists(viewer$output_path))
  testthat::expect_gt(file.info(viewer$output_path)$size, 0)
  unlink(viewer$output_path)
  viewer$selected_indicator <- "rsi"
  viewer$selected_overlays <- c("sma", "ema", "bollinger")
  viewer$chart_parameters <- c(7, 11, 18, 2.5, 9, 8, 21, 5, 12)
  viewer$selected_bars <- "5Min"
  viewer$selected_comparisons <- "MSFT"
  data$comparison_bars <- list(MSFT = data.frame(
    date = bars$date, close = seq(200, 225, length.out = 50), volume = rep(1000, 50)))
  testthat::expect_silent(viewer$render_view(data))
  testthat::expect_gt(file.info(viewer$output_path)$size, 0)
  unlink(viewer$output_path)
  viewer$selected_layout <- "separate"
  testthat::expect_silent(viewer$render_view(data))
  testthat::expect_gt(file.info(viewer$output_path)$size, 0)
  unlink(viewer$output_path)
  data$comparison_bars <- setNames(rep(list(data$comparison_bars$MSFT), 4),
                                   c("MSFT", "NVDA", "GOOG", "AMZN"))
  testthat::expect_silent(viewer$render_view(data))
  testthat::expect_gt(file.info(viewer$output_path)$size, 0)
  unlink(viewer$output_path)
  viewer$selected_custom_overlays <- "trend"
  viewer$selected_custom_study <- "trend"
  viewer$selected_indicator <- "custom"
  viewer$load_user_indicator <- function(name, bars, root = NULL) bars$close / 2
  testthat::expect_silent(viewer$render_view(data))
  testthat::expect_gt(file.info(viewer$output_path)$size, 0)
})

testthat::test_that("renderer accepts observed-data handoff without Alpaca credentials", {
  old_directory <- getwd()
  setwd(repo_root)
  on.exit(setwd(old_directory), add = TRUE)
  source(file.path("r", "live_portfolio.R"), local = TRUE)
  bars <- data.frame(date = as.POSIXct(seq(Sys.Date() - 49, Sys.Date(), by = "day"), tz = "UTC"),
                     open = seq(99, 148), high = seq(101, 150), low = seq(98, 147),
                     close = seq(100, 149), volume = rep(1000, 50),
                     vwap = seq(100, 149), trades = rep(50, 50))
  observed <- list(account = list(), positions = parse_positions(list()),
                   history = parse_history(list()), stock_bars = bars,
                   comparison_bars = list(), stock_error = NULL, stock_symbols = "AAPL",
                   chart_feed = "iex")
  input <- tempfile(fileext = ".rds")
  output <- tempfile(fileext = ".png")
  on.exit(unlink(c(input, output)), add = TRUE)
  saveRDS(observed, input)
  call <- c("r/java_display.R", output, "Stock lab", "3M", "AAPL", "field", "sma",
            "1Day", "20,20,20,2,14,12,26,9,20", "", "overlay", "", "volume",
            "close", input)
  result <- suppressWarnings(system2("Rscript", vapply(call, shQuote, character(1)),
                                     stdout = TRUE, stderr = TRUE))
  testthat::expect_null(attr(result, "status"))
  testthat::expect_true(file.exists(output))
  testthat::expect_gt(file.info(output)$size, 0)
  styled <- suppressWarnings(system2("Rscript",
    vapply(c(call, "900", "550", "candles"), shQuote, character(1)),
    stdout = TRUE, stderr = TRUE))
  testthat::expect_null(attr(styled, "status"))
  testthat::expect_gt(file.info(output)$size, 0)
  watermarked <- suppressWarnings(system2("Rscript",
    vapply(c(call, "900", "550", "candles", "on"), shQuote, character(1)),
    stdout = TRUE, stderr = TRUE))
  testthat::expect_null(attr(watermarked, "status"))
  testthat::expect_gt(file.info(output)$size, 0)
  sized <- suppressWarnings(system2("Rscript",
    vapply(c(call, "700", "400", "candles", "off", "350", "200"),
           shQuote, character(1)), stdout = TRUE, stderr = TRUE))
  testthat::expect_null(attr(sized, "status"))
  testthat::expect_gt(file.info(output)$size, 0)
})

testthat::test_that("main chart can plot observed volume and a custom numeric series", {
  old_directory <- getwd()
  setwd(repo_root)
  on.exit(setwd(old_directory), add = TRUE)
  viewer <- new.env(parent = globalenv())
  sys.source(file.path(repo_root, "r", "java_display.R"), envir = viewer)
  viewer$selected_view <- "Stock lab"
  viewer$selected_stock <- "AAPL"
  viewer$selected_plot <- "volume"
  viewer$selected_indicator <- "field"
  viewer$selected_custom_study <- "trades"
  viewer$selected_overlays <- "sma"
  viewer$selected_comparisons <- character()
  viewer$output_path <- tempfile(fileext = ".png")
  on.exit(unlink(viewer$output_path), add = TRUE)
  bars <- data.frame(date = as.POSIXct(seq(Sys.Date() - 49, Sys.Date(), by = "day"), tz = "UTC"),
                     close = seq(100, 149), volume = seq(1000, 1490, by = 10),
                     trades = seq(50, 99))
  data <- list(account = list(), positions = viewer$parse_positions(list()),
               history = viewer$parse_history(list()), stock_bars = bars,
               comparison_bars = list(), stock_error = NULL, chart_feed = "iex")
  prepared <- viewer$prepare_stock_chart(bars)
  testthat::expect_equal(prepared$metric, bars$volume)
  testthat::expect_silent(viewer$render_view(data))
  testthat::expect_gt(file.info(viewer$output_path)$size, 0)

  viewer$selected_plot <- "custom:volume_ratio"
  viewer$selected_indicator <- "none"
  viewer$load_user_indicator <- function(name, input, root = NULL) input$volume / input$trades
  prepared <- viewer$prepare_stock_chart(bars)
  testthat::expect_equal(prepared$metric, bars$volume / bars$trades)
  testthat::expect_silent(viewer$render_view(data))
  testthat::expect_gt(file.info(viewer$output_path)$size, 0)
})

testthat::test_that("all time-series display styles render and price bars use observed OHLC", {
  old_directory <- getwd()
  setwd(repo_root)
  on.exit(setwd(old_directory), add = TRUE)
  viewer <- new.env(parent = globalenv())
  sys.source(file.path(repo_root, "r", "java_display.R"), envir = viewer)
  viewer$selected_view <- "Stock lab"
  viewer$selected_stock <- "AAPL"
  viewer$selected_plot <- "close"
  viewer$selected_indicator <- "none"
  viewer$selected_overlays <- character()
  viewer$selected_comparisons <- character()
  viewer$output_path <- tempfile(fileext = ".png")
  on.exit(unlink(viewer$output_path), add = TRUE)
  close <- 100 + cumsum(rep(c(1, -0.5, 0.25), length.out = 50))
  bars <- data.frame(date = as.POSIXct(seq(Sys.Date() - 49, Sys.Date(), by = "day"), tz = "UTC"),
                     open = close - 0.4, high = close + 0.8, low = close - 1,
                     close = close, volume = rep(1000, 50),
                     vwap = close, trades = rep(50, 50))
  data <- list(account = list(), positions = viewer$parse_positions(list()),
               history = viewer$parse_history(list()), stock_bars = bars,
               comparison_bars = list(), stock_error = NULL, chart_feed = "iex")
  for (style in c("line", "step", "area", "points", "columns", "lollipop",
                  "candles", "hollow_candles", "ohlc", "heikin_ashi")) {
    viewer$selected_display <- style
    testthat::expect_silent(viewer$render_view(data))
    testthat::expect_gt(file.info(viewer$output_path)$size, 0)
  }
  comparison <- bars
  comparison[c("open", "high", "low", "close", "vwap")] <-
    lapply(comparison[c("open", "high", "low", "close", "vwap")], `*`, 2)
  data$comparison_bars <- list(MSFT = comparison)
  viewer$selected_comparisons <- "MSFT"
  viewer$selected_display <- "candles"
  for (layout in c("overlay", "separate")) {
    viewer$selected_layout <- layout
    testthat::expect_silent(viewer$render_view(data))
    testthat::expect_gt(file.info(viewer$output_path)$size, 0)
  }
  viewer$selected_display <- "heikin_ashi"
  transformed <- viewer$price_bar_frame(bars, 1)
  testthat::expect_equal(transformed$close[[1L]], mean(unlist(bars[1L, c("open", "high", "low", "close")])))
  testthat::expect_equal(transformed$open[[2L]],
                         (transformed$open[[1L]] + transformed$close[[1L]]) / 2)
  bars$high[[1L]] <- NA_real_
  testthat::expect_error(viewer$price_bar_frame(bars, 1), "missing or inconsistent")
})

testthat::test_that("financial axes and status use observed values without duplicated time labels", {
  old_directory <- getwd()
  setwd(repo_root)
  on.exit(setwd(old_directory), add = TRUE)
  viewer <- new.env(parent = globalenv())
  sys.source(file.path(repo_root, "r", "java_display.R"), envir = viewer)
  viewer$selected_display <- "candles"
  viewer$selected_plot <- "close"
  viewer$selected_indicator <- "rsi"
  viewer$selected_overlays <- character()
  viewer$selected_comparisons <- character()
  close <- seq(101, 120, length.out = 20)
  bars <- data.frame(date = as.POSIXct(seq(Sys.Date() - 19, Sys.Date(), by = "day"), tz = "UTC"),
                     open = close - 1, high = close + 2, low = close - 2,
                     close = close, volume = rep(1234567, 20),
                     vwap = close, trades = rep(50, 20))
  prepared <- viewer$prepare_stock_chart(bars)
  upper <- viewer$stock_price_plot(prepared, "AAPL", "iex", show_time_axis = FALSE)
  testthat::expect_identical(upper$scales$get_scales("y")$position, "right")
  testthat::expect_s3_class(upper$theme$axis.text.x, "element_blank")
  testthat::expect_match(upper$labels$subtitle, "O 119.00")
  testthat::expect_match(upper$labels$subtitle, "C 120.00")
  testthat::expect_match(upper$labels$subtitle, "Vol 1.23M")
  price_tags <- which(vapply(upper$layers,
                            function(layer) inherits(layer$geom, "GeomLabel"), logical(1)))
  testthat::expect_length(price_tags, 1L)
  testthat::expect_identical(ggplot2::ggplot_build(upper)$data[[price_tags[[1L]]]]$label[[1L]],
                             "$120.00")
  time_ticks <- viewer$stock_x_scale(prepared$date)$breaks(range(prepared$date))
  testthat::expect_equal(tail(time_ticks, 1L), max(prepared$date))
  viewer$selected_watermark <- TRUE
  marked <- viewer$stock_price_plot(prepared, "AAPL", "iex")
  watermarks <- which(vapply(marked$layers,
                            function(layer) inherits(layer$geom, "GeomText"), logical(1)))
  testthat::expect_length(watermarks, 1L)
  testthat::expect_identical(ggplot2::ggplot_build(marked)$data[[watermarks[[1L]]]]$label[[1L]],
                             "AAPL")
  viewer$selected_watermark <- FALSE
  viewer$display_scale <- 0.4
  testthat::expect_gte(viewer$chart_text_pt(8) * viewer$chart_dpi / 72 * viewer$display_scale,
                       12)
  testthat::expect_gte(viewer$chart_label_mm(2) * viewer$chart_dpi / 25.4 * viewer$display_scale,
                       12)
  viewer$display_scale <- 0.5
  viewer$selected_display <- "line"
  viewer$selected_overlays <- c("sma", "ema")
  lines <- Filter(function(layer) inherits(layer$geom, "GeomLine"),
                  viewer$stock_price_plot(prepared, "AAPL", "iex")$layers)
  testthat::expect_true(all(vapply(lines, function(layer) layer$aes_params$linewidth,
                                    numeric(1)) >= 1.1))
  viewer$selected_display <- "candles"
  viewer$selected_overlays <- character()
  lower <- viewer$stock_lower_plot(prepared)
  testthat::expect_identical(lower$scales$get_scales("y")$position, "right")
  flat <- data.frame(date = as.Date("2026-01-01") + 0:9, equity = rep(100000, 10))
  equity <- viewer$equity_chart(flat)
  testthat::expect_equal(equity$scales$get_scales("y")$breaks, 100000)
})

testthat::test_that("short observed history adapts rolling study periods visibly", {
  old_directory <- getwd()
  setwd(repo_root)
  on.exit(setwd(old_directory), add = TRUE)
  viewer <- new.env(parent = globalenv())
  sys.source(file.path(repo_root, "r", "java_display.R"), envir = viewer)
  viewer$selected_plot <- "close"
  viewer$selected_display <- "line"
  viewer$selected_indicator <- "rsi"
  viewer$selected_overlays <- c("sma", "bollinger")
  viewer$selected_comparisons <- character()
  bars <- data.frame(date = as.Date("2026-01-01") + 0:4,
                     open = 100:104, high = 102:106, low = 99:103,
                     close = 101:105, volume = rep(1000, 5))
  prepared <- viewer$prepare_stock_chart(bars)
  testthat::expect_equal(attr(prepared, "effective_parameters")[[1L]], 4)
  testthat::expect_equal(attr(prepared, "effective_parameters")[[5L]], 4)
  testthat::expect_match(viewer$stock_price_plot(prepared, "AAPL", "iex")$labels$subtitle,
                         "SMA 4 \\(auto from 20\\)")
  testthat::expect_match(viewer$stock_lower_plot(prepared)$labels$title,
                         "RSI 4 \\(auto from 14\\)")
})

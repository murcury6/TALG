suppressPackageStartupMessages({
  library(ggplot2)
  library(grid)
})
source(file.path("r", "live_portfolio.R"), local = TRUE)
source(file.path("r", "portfolio_app", "R", "portfolio_data.R"), local = TRUE)
source(file.path("r", "user_indicators.R"), local = TRUE)

args <- commandArgs(trailingOnly = TRUE)
output_path <- if (length(args) >= 1L) args[[1L]] else ""
selected_view <- if (length(args) >= 2L) args[[2L]] else "Overview"
selected_period <- if (length(args) >= 3L) args[[3L]] else "3M"
selected_stock <- if (length(args) >= 4L) toupper(args[[4L]]) else ""
selected_indicator <- if (length(args) >= 5L) args[[5L]] else "rsi"
raw_overlays <- if (length(args) >= 6L) args[[6L]] else "sma,ema,bollinger"
selected_overlays <- if (nzchar(raw_overlays)) {
  intersect(strsplit(raw_overlays, ",", fixed = TRUE)[[1L]],
            c("sma", "ema", "bollinger"))
} else character()
selected_bars <- if (length(args) >= 7L) args[[7L]] else "1Day"
raw_parameters <- if (length(args) >= 8L && nzchar(args[[8L]])) args[[8L]] else
  "20,20,20,2,14,12,26,9,20"
chart_parameters <- suppressWarnings(as.numeric(strsplit(raw_parameters, ",", fixed = TRUE)[[1L]]))
if (length(chart_parameters) != 9L || any(!is.finite(chart_parameters)) ||
    any(chart_parameters[c(1:3, 5:9)] != floor(chart_parameters[c(1:3, 5:9)])) ||
    any(chart_parameters[c(1:3, 5:9)] < 2 | chart_parameters[c(1:3, 5:9)] > 500) ||
    chart_parameters[[4L]] <= 0 || chart_parameters[[4L]] > 10 ||
    chart_parameters[[6L]] >= chart_parameters[[7L]])
  stop("Invalid chart indicator parameters.", call. = FALSE)
selected_comparisons <- if (length(args) >= 9L && nzchar(args[[9L]]))
  strsplit(args[[9L]], ",", fixed = TRUE)[[1L]] else character()
selected_layout <- if (length(args) >= 10L) args[[10L]] else "overlay"
if (!selected_layout %in% c("overlay", "separate"))
  stop("Invalid stock chart layout.", call. = FALSE)
selected_custom_overlays <- if (length(args) >= 11L && nzchar(args[[11L]]))
  strsplit(args[[11L]], ",", fixed = TRUE)[[1L]] else character()
selected_custom_study <- if (length(args) >= 12L) args[[12L]] else ""
selected_plot <- if (length(args) >= 13L) args[[13L]] else "close"
selected_data_file <- if (length(args) >= 14L) args[[14L]] else ""
plot_width <- if (length(args) >= 16L) suppressWarnings(as.integer(args[[15L]])) else 1300L
plot_height <- if (length(args) >= 16L) suppressWarnings(as.integer(args[[16L]])) else 720L
selected_display <- if (length(args) >= 17L) args[[17L]] else "line"
selected_watermark <- if (length(args) >= 18L) args[[18L]] == "on" else FALSE
display_width <- if (length(args) >= 20L) suppressWarnings(as.integer(args[[19L]])) else plot_width / 2
display_height <- if (length(args) >= 20L) suppressWarnings(as.integer(args[[20L]])) else plot_height / 2
if (is.na(plot_width) || is.na(plot_height) || plot_width < 600L || plot_width > 4000L ||
    plot_height < 350L || plot_height > 3000L)
  stop("Invalid chart image dimensions.", call. = FALSE)
if (is.na(display_width) || is.na(display_height) ||
    display_width < 1L || display_height < 1L ||
    display_width > 10000L || display_height > 10000L)
  stop("Invalid displayed chart dimensions.", call. = FALSE)
bar_fields <- c("open", "high", "low", "close", "volume", "vwap", "trades")
if (!selected_plot %in% bar_fields &&
    !grepl("^custom:[a-z][a-z0-9_]{0,39}$", selected_plot))
  stop("Invalid plotted bar field.", call. = FALSE)
price_bar_displays <- c("candles", "hollow_candles", "ohlc", "heikin_ashi")
if (!selected_display %in% c("line", "step", "area", "points", "columns",
                            "lollipop", price_bar_displays))
  stop("Invalid stock chart display style.", call. = FALSE)
if (selected_display %in% price_bar_displays && selected_plot != "close")
  stop("Price-bar displays require plot close.", call. = FALSE)

ink <- list(
  bg = "#14121b", panel = "#221e2d", panel2 = "#2b253b", line = "#463e57",
  white = "#e8e5ef", muted = "#9995a4", purple = "#b192f5", green = "#4bcd8f",
  red = "#f06a79", grey = "#86808f"
)
stroke <- list(price = 1.3, overlay = 1.15, study = 1.25,
               wick = 0.9, body = 0.65, guide = 1.0)
chart_dpi <- 130
display_scale <- max(0.35, min(display_width / plot_width, display_height / plot_height))
chart_text_pt <- function(base, minimum_pixels = 12) {
  max(base, minimum_pixels * 72 / (chart_dpi * display_scale))
}
chart_label_mm <- function(base, minimum_pixels = 12) {
  max(base, minimum_pixels * 25.4 / (chart_dpi * display_scale))
}

money <- function(x, digits = 2L) {
  if (!is.finite(x)) return("—")
  paste0(if (x < 0) "−" else "", "$",
         formatC(abs(x), format = "f", digits = digits, big.mark = ","))
}
pct <- function(x, digits = 2L) {
  if (!is.finite(x)) return("—")
  sprintf(paste0("%+.", digits, "f%%"), 100 * x)
}
metric_tone <- function(value) if (!is.finite(value)) ink$muted else
  if (value < 0) ink$red else if (value > 0) ink$green else ink$white

chart_theme <- function() {
  theme_minimal(base_family = "sans", base_size = chart_text_pt(12)) + theme(
    plot.background = element_rect(fill = ink$bg, color = NA),
    panel.background = element_rect(fill = ink$bg, color = NA),
    panel.grid.major = element_line(color = ink$line, linewidth = 0.3),
    panel.grid.minor = element_blank(),
    axis.text = element_text(color = ink$muted, size = chart_text_pt(11)),
    axis.title = element_text(color = ink$muted),
    plot.title = element_text(color = ink$white, face = "bold", size = chart_text_pt(13)),
    plot.subtitle = element_text(color = ink$muted, size = chart_text_pt(10)),
    plot.caption = element_text(color = ink$muted, size = chart_text_pt(9)),
    plot.margin = margin(3, 5, 2, 3),
    legend.position = "none"
  )
}

stock_future_padding <- function(dates, compact = FALSE) {
  observed <- sort(unique(as.numeric(dates)))
  step <- if (length(observed) > 1L) median(diff(observed)) else 1
  span <- if (length(observed) > 1L) diff(range(observed)) else step
  max(step * 1.5, span * if (compact) 0.18 else 0.10)
}

stock_x_scale <- function(dates, compact = FALSE) {
  window <- selected_period
  observed <- sort(unique(as.numeric(dates)))
  padding <- if (length(observed) > 1L) median(diff(observed)) * 0.6 else 0
  limits <- c(min(dates) - padding, max(dates) + stock_future_padding(dates, compact))
  date_format <- if (window == "1D") "%H:%M" else if (window == "5D") "%a %H:%M" else
    if (window %in% c("1M", "3M", "6M")) "%b %d" else "%b %Y"
  spacing <- if (compact)
    switch(window, "1D" = "4 hours", "5D" = "2 days", "1M" = "2 weeks",
           "3M" = "1 month", "6M" = "2 months", "YTD" = "3 months",
           "1Y" = "3 months", "1 month")
  else
    switch(window, "1D" = "2 hours", "5D" = "1 day", "1M" = "1 week",
           "3M" = "2 weeks", "6M" = "1 month", "YTD" = "2 months",
           "1Y" = "2 months", "1 month")
  time_breaks <- function(limits) {
    regular <- scales::breaks_width(spacing)(limits)
    regular <- regular[regular >= min(dates) & regular < max(dates)]
    span <- as.numeric(max(dates) - min(dates))
    if (length(regular) && span > 0) {
      regular <- regular[as.numeric(regular - min(dates)) > span * 0.05]
      regular <- regular[as.numeric(max(dates) - regular) > span * 0.09]
    }
    c(regular, max(dates))
  }
  if (inherits(dates, "Date"))
    scale_x_date(limits = limits, labels = function(values) {
      output <- format(values, date_format)
      output
    }, breaks = time_breaks,
                 expand = expansion(mult = c(0.002, 0.002)))
  else
    scale_x_datetime(limits = limits, labels = function(values) {
      output <- format(values, date_format, tz = "UTC")
      output
    }, breaks = time_breaks,
                     expand = expansion(mult = c(0.002, 0.002)))
}

place <- function(rows, columns = 1:12) viewport(layout.pos.row = rows, layout.pos.col = columns)

draw_metric <- function(slot, title, value, detail, tone = ink$white) {
  columns <- ((slot - 1L) * 3L + 1L):(slot * 3L)
  pushViewport(place(1:2, columns))
  grid.roundrect(width = 0.95, height = 0.87, r = unit(7, "pt"),
                 gp = gpar(fill = ink$panel2, col = ink$line))
  grid.text(title, x = 0.075, y = 0.74, just = "left",
            gp = gpar(col = ink$muted, fontsize = 9, fontface = "bold"))
  grid.text(value, x = 0.075, y = 0.43, just = "left",
            gp = gpar(col = tone, fontsize = 19, fontface = "bold"))
  grid.text(detail, x = 0.075, y = 0.15, just = "left",
            gp = gpar(col = ink$muted, fontsize = 9))
  popViewport()
}

draw_message <- function(rows, columns, title, detail) {
  pushViewport(place(rows, columns))
  grid.roundrect(width = 0.97, height = 0.94, r = unit(7, "pt"),
                 gp = gpar(fill = ink$panel, col = ink$line))
  grid.text(title, x = 0.07, y = 0.58, just = "left",
            gp = gpar(col = ink$white, fontsize = 17, fontface = "bold"))
  grid.text(detail, x = 0.07, y = 0.40, just = "left",
            gp = gpar(col = ink$muted, fontsize = 10))
  popViewport()
}

draw_plot <- function(plot, rows, columns) print(plot, vp = place(rows, columns))

equity_chart <- function(history, title = "Account equity") {
  finite_equity <- history$equity[is.finite(history$equity)]
  flat <- length(finite_equity) > 0L && diff(range(finite_equity)) < 0.005
  equity_scale <- if (flat)
    scale_y_continuous(breaks = finite_equity[[1L]],
                       labels = scales::label_dollar(big.mark = ","),
                       limits = finite_equity[[1L]] + c(-1, 1) *
                         max(1, abs(finite_equity[[1L]]) * 0.005))
  else scale_y_continuous(labels = scales::label_dollar(big.mark = ","))
  ggplot(history, aes(date, equity)) +
    geom_ribbon(aes(ymin = min(equity), ymax = equity), fill = ink$purple, alpha = 0.12) +
    geom_line(color = ink$purple, linewidth = stroke$price) +
    equity_scale +
    labs(title = title, subtitle = "Alpaca account history • daily observations",
         x = NULL, y = NULL) + chart_theme()
}

allocation_chart <- function(positions) {
  plotted <- positions[is.finite(positions$value) & positions$value != 0, , drop = FALSE]
  plotted$weight <- abs(plotted$value) / sum(abs(plotted$value))
  ggplot(plotted, aes(weight, reorder(symbol, weight))) +
    geom_col(fill = ink$purple, width = 0.60) +
    geom_text(aes(label = scales::percent(weight, accuracy = 0.1)), hjust = 1.10,
              color = ink$white, size = 3.4) +
    scale_x_continuous(labels = scales::label_percent(), expand = expansion(mult = c(0, 0.06))) +
    labs(title = "Position weights", subtitle = "Share of gross market exposure",
         x = NULL, y = NULL) + chart_theme()
}

position_pnl_chart <- function(positions, field, title) {
  plotted <- positions[is.finite(positions[[field]]), , drop = FALSE]
  plotted$value_to_plot <- plotted[[field]]
  plotted$tone <- ifelse(plotted$value_to_plot > 0, "gain",
                         ifelse(plotted$value_to_plot < 0, "loss", "neutral"))
  ggplot(plotted, aes(value_to_plot, reorder(symbol, value_to_plot),
                      fill = tone)) +
    geom_col(width = 0.58) +
    scale_fill_manual(values = c(gain = ink$green, loss = ink$red, neutral = ink$grey)) +
    scale_x_continuous(labels = scales::label_dollar(big.mark = ",")) +
    labs(title = title, x = NULL, y = NULL) + chart_theme()
}

ema_valid <- function(values, period) {
  result <- rep(NA_real_, length(values))
  valid <- which(is.finite(values))
  if (length(valid)) result[valid] <- ema(values[valid], period)
  result
}

prepare_stock_chart <- function(bars) {
  p <- chart_parameters
  bars <- bars[order(bars$date), , drop = FALSE]
  effective_limit <- max(2L, nrow(bars) - 1L)
  p[c(1L, 3L, 5L, 9L)] <- pmin(p[c(1L, 3L, 5L, 9L)], effective_limit)
  custom_names <- unique(c(selected_custom_overlays,
                           if (selected_indicator == "custom") selected_custom_study,
                           if (startsWith(selected_plot, "custom:")) sub("^custom:", "", selected_plot)))
  for (name in custom_names) {
    if (nzchar(name)) bars[[paste0("custom_", name)]] <- load_user_indicator(name, bars)
  }
  if (startsWith(selected_plot, "custom:")) {
    bars$metric <- bars[[paste0("custom_", sub("^custom:", "", selected_plot))]]
    if (sum(is.finite(bars$metric)) < 2L)
      stop("The selected custom plot has fewer than two finite values.", call. = FALSE)
  } else {
    bars$metric <- bars[[selected_plot]]
    if (any(!is.finite(bars$metric)))
      stop(paste("Alpaca did not provide", selected_plot, "for every bar in this chart."), call. = FALSE)
  }
  bars$sma <- rolling_mean(bars$metric, p[[1L]])
  bars$ema <- ema_valid(bars$metric, p[[2L]])
  spread <- rolling_sd(bars$metric, p[[3L]])
  middle <- rolling_mean(bars$metric, p[[3L]])
  bars$upper <- middle + p[[4L]] * spread
  bars$lower <- middle - p[[4L]] * spread
  bars$rsi <- rsi(bars$metric, p[[5L]])
  bars$macd <- ema_valid(bars$metric, p[[6L]]) - ema_valid(bars$metric, p[[7L]])
  bars$signal <- ema_valid(bars$macd, p[[8L]])
  bars$bar_return <- c(NA_real_, diff(bars$metric) / head(bars$metric, -1L))
  bars$bar_return[!is.finite(bars$bar_return)] <- NA_real_
  bars$volatility <- rolling_sd(bars$bar_return, p[[9L]])
  attr(bars, "effective_parameters") <- p
  bars
}

indicator_label <- function(name, effective, requested) {
  paste0(name, " ", effective,
         if (effective != requested) paste0(" (auto from ", requested, ")") else "")
}

price_bar_frame <- function(bars, factor) {
  fields <- c("open", "high", "low", "close")
  if (!all(fields %in% names(bars)) ||
      any(!vapply(bars[fields], function(field) all(is.finite(field)), logical(1))) ||
      any(bars$high < pmax(bars$open, bars$close)) ||
      any(bars$low > pmin(bars$open, bars$close)))
    stop("Alpaca OHLC bars are missing or inconsistent; cannot draw price bars.", call. = FALSE)
  if (selected_display == "heikin_ashi") {
    transformed_close <- rowMeans(bars[fields])
    transformed_open <- numeric(nrow(bars))
    transformed_open[[1L]] <- (bars$open[[1L]] + bars$close[[1L]]) / 2
    if (nrow(bars) > 1L) for (index in 2:nrow(bars))
      transformed_open[[index]] <- (transformed_open[[index - 1L]] +
                                      transformed_close[[index - 1L]]) / 2
    bars$high <- pmax(bars$high, transformed_open, transformed_close)
    bars$low <- pmin(bars$low, transformed_open, transformed_close)
    bars$open <- transformed_open
    bars$close <- transformed_close
  }
  for (field in fields) bars[[field]] <- bars[[field]] * factor
  bars$tone <- ifelse(bars$close > bars$open, "gain",
                      ifelse(bars$close < bars$open, "loss", "neutral"))
  steps <- diff(sort(unique(as.numeric(bars$date))))
  half_width <- if (length(steps)) median(steps) * 0.37 else 0.37
  bars$left <- bars$date - half_width
  bars$right <- bars$date + half_width
  bars$body_low <- pmin(bars$open, bars$close)
  bars$body_high <- pmax(bars$open, bars$close)
  doji <- bars$body_low == bars$body_high
  if (any(doji)) {
    visible_body <- max(diff(range(c(bars$high, bars$low))) * 0.001,
                        max(abs(bars$close)) * 0.00001)
    bars$body_low[doji] <- bars$body_low[doji] - visible_body / 2
    bars$body_high[doji] <- bars$body_high[doji] + visible_body / 2
  }
  bars
}

short_volume <- function(value) {
  if (!is.finite(value)) return("—")
  if (abs(value) >= 1e9) return(sprintf("%.2fB", value / 1e9))
  if (abs(value) >= 1e6) return(sprintf("%.2fM", value / 1e6))
  if (abs(value) >= 1e3) return(sprintf("%.1fK", value / 1e3))
  formatC(value, format = "f", digits = 0, big.mark = ",")
}

stock_status <- function(bars, plotted, ticker, comparing = FALSE, compact = FALSE) {
  last <- nrow(bars)
  stamp <- format(bars$date[[last]],
                  if (selected_bars %in% c("1Day", "1Week")) "%Y-%m-%d" else "%m-%d %H:%MZ",
                  tz = "UTC")
  if (comparing) {
    value <- formatC(plotted[[last]], format = "f", digits = 2)
    return(if (compact) paste(ticker, value, "idx") else
      paste(ticker, "index", value, "(base 100) ·", stamp))
  }
  fields <- c("open", "high", "low", "close")
  if (selected_plot == "close" && all(fields %in% names(bars)) &&
      all(vapply(bars[fields], function(field) is.finite(field[[last]]), logical(1)))) {
    ohlc <- paste(paste0(c("O", "H", "L", "C"), " ",
                         formatC(unlist(bars[last, fields]), format = "f", digits = 2)),
                  collapse = if (compact) "  " else "   ")
    if (compact) return(ohlc)
    volume <- if ("volume" %in% names(bars))
      paste("Vol", short_volume(bars$volume[[last]])) else ""
    return(paste(c(stamp, ohlc, volume)[nzchar(c(stamp, ohlc, volume))], collapse = "   ·   "))
  }
  value <- plotted[[last]]
  label <- if (selected_plot %in% c("open", "high", "low", "close", "vwap"))
    money(value) else if (selected_plot == "volume") short_volume(value) else
      formatC(value, format = "fg", digits = 4)
  if (compact) paste(toupper(selected_plot), label) else
    paste(toupper(selected_plot), label, "·", stamp)
}

stock_price_plot <- function(bars, ticker, feed, comparison_bars = list(),
                             show_time_axis = TRUE, compact = FALSE) {
  p <- attr(bars, "effective_parameters") %||% chart_parameters
  comparing <- length(comparison_bars) > 0L
  base_value <- bars$metric[which(is.finite(bars$metric))[[1L]]]
  if (comparing && base_value <= 0)
    stop("Indexed comparison requires a positive first plotted value; use layout separate.", call. = FALSE)
  factor <- if (comparing) 100 / base_value else 1
  bars$plotted <- bars$metric * factor
  bars$sma_plotted <- bars$sma * factor
  bars$ema_plotted <- bars$ema * factor
  bars$upper_plotted <- bars$upper * factor
  bars$lower_plotted <- bars$lower * factor
  price <- ggplot(bars, aes(date, plotted))
  if (selected_watermark) {
    price <- price + annotate("text", x = bars$date[[ceiling(nrow(bars) / 2)]],
      y = mean(range(bars$plotted, na.rm = TRUE)), label = ticker,
      color = ink$white, alpha = 0.11, fontface = "bold", size = if (compact) 10 else 14)
  }
  price_bars <- if (selected_display %in% price_bar_displays) price_bar_frame(bars, factor) else NULL
  if (selected_plot %in% c("open", "high", "low", "close", "vwap")) {
    reference <- if (!is.null(price_bars) && selected_display == "heikin_ashi")
      tail(price_bars$close, 1L) else tail(bars$plotted, 1L)
    price <- price + geom_hline(yintercept = reference, color = ink$grey,
                                linetype = "dotted", linewidth = stroke$guide)
  }
  observed_steps <- diff(sort(unique(as.numeric(bars$date))))
  column_width <- if (length(observed_steps)) median(observed_steps) * 0.74 else 0.74
  if ("bollinger" %in% selected_overlays) price <- price +
    geom_ribbon(aes(ymin = lower_plotted, ymax = upper_plotted),
                fill = ink$purple, alpha = 0.10, na.rm = TRUE)
  if (selected_display %in% price_bar_displays) {
    price <- price + geom_segment(data = price_bars,
      aes(x = date, xend = date, y = low, yend = high, color = tone),
      inherit.aes = FALSE, linewidth = stroke$wick)
    if (selected_display == "ohlc") {
      price <- price +
        geom_segment(data = price_bars,
          aes(x = left, xend = date, y = open, yend = open, color = tone),
          inherit.aes = FALSE, linewidth = stroke$overlay) +
        geom_segment(data = price_bars,
          aes(x = date, xend = right, y = close, yend = close, color = tone),
          inherit.aes = FALSE, linewidth = stroke$overlay)
    } else {
      fills <- c(gain = ink$green, loss = ink$red, neutral = ink$grey)
      if (selected_display == "hollow_candles") fills[["gain"]] <- ink$bg
      price <- price + geom_rect(data = price_bars,
        aes(xmin = left, xmax = right, ymin = body_low, ymax = body_high,
            fill = tone, color = tone), inherit.aes = FALSE, linewidth = stroke$body) +
        scale_fill_manual(values = fills)
    }
    price <- price + scale_color_manual(values = c(gain = ink$green,
                                                   loss = ink$red, neutral = ink$grey))
  } else if (selected_display == "step") {
    price <- price + geom_step(color = ink$white, linewidth = stroke$price, direction = "hv")
  } else if (selected_display == "area") {
    baseline <- min(bars$plotted, na.rm = TRUE)
    price <- price + geom_ribbon(aes(ymin = baseline, ymax = plotted),
                                 fill = ink$grey, alpha = 0.22) +
      geom_line(color = ink$white, linewidth = stroke$price)
  } else if (selected_display == "points") {
    price <- price + geom_point(color = ink$white, size = 1.6, alpha = 0.9)
  } else if (selected_display == "columns") {
    price <- price + geom_col(fill = ink$grey, width = column_width)
  } else if (selected_display == "lollipop") {
    baseline <- min(bars$plotted, na.rm = TRUE)
    price <- price + geom_segment(aes(xend = date, y = baseline, yend = plotted),
                                  color = ink$grey, linewidth = stroke$wick) +
      geom_point(color = ink$white, size = 1.5)
  } else {
    price <- price + geom_line(color = ink$white, linewidth = stroke$price, na.rm = TRUE)
  }
  if ("sma" %in% selected_overlays) price <- price +
    geom_line(aes(y = sma_plotted), color = ink$purple, linewidth = stroke$overlay, na.rm = TRUE)
  if ("ema" %in% selected_overlays) price <- price +
    geom_line(aes(y = ema_plotted), color = ink$grey, linewidth = stroke$overlay, na.rm = TRUE)
  custom_colors <- c("#68c8cc", "#e4b861", "#d39ee8", "#6b9eee")
  custom_color_names <- c("cyan", "amber", "mauve", "blue")
  for (index in seq_along(selected_custom_overlays)) {
    name <- selected_custom_overlays[[index]]
    column <- paste0("custom_", name)
    custom_line <- data.frame(date = bars$date, value = bars[[column]] * factor)
    price <- price + geom_line(data = custom_line, aes(date, value), inherit.aes = FALSE,
                               color = custom_colors[[(index - 1L) %% length(custom_colors) + 1L]],
                               linewidth = stroke$overlay, na.rm = TRUE)
  }
  comparison_colors <- c("#6b9eee", "#e4b861", "#68c8cc", "#d39ee8")
  comparison_names <- c("blue", "amber", "cyan", "mauve")
  comparison_caption <- character()
  if (comparing) for (index in seq_along(comparison_bars)) {
    other <- comparison_bars[[index]]
    other_base <- other$metric[which(is.finite(other$metric))[[1L]]]
    if (other_base <= 0)
      stop("Indexed comparison requires a positive first plotted value; use layout separate.", call. = FALSE)
    other$indexed <- other$metric / other_base * 100
    price <- price + geom_line(data = other, aes(date, indexed),
                               color = comparison_colors[[index]], linewidth = stroke$overlay)
    comparison_caption <- c(comparison_caption,
                            paste0(names(comparison_bars)[[index]], ": ", comparison_names[[index]]))
  }
  overlay_caption <- c(if (comparing) paste0(ticker, " · white"),
                       if ("sma" %in% selected_overlays)
                         paste0(indicator_label("SMA", p[[1L]], chart_parameters[[1L]]), " · purple"),
                       if ("ema" %in% selected_overlays) paste0("EMA ", p[[2L]], " · grey"),
                       if ("bollinger" %in% selected_overlays)
                         paste0(indicator_label("Bollinger", p[[3L]], chart_parameters[[3L]]),
                                " / ", p[[4L]], " · band"),
                       if (length(selected_custom_overlays))
                         paste0(selected_custom_overlays, " · ",
                                custom_color_names[(seq_along(selected_custom_overlays) - 1L) %%
                                  length(custom_color_names) + 1L]),
                       comparison_caption)
  axis_label <- if (comparing) scales::label_number() else
    if (selected_plot %in% c("open", "high", "low", "close", "vwap")) scales::label_dollar() else
      scales::label_comma()
  marker_series <- if (!is.null(price_bars) && selected_display == "heikin_ashi")
    price_bars$close else bars$plotted
  last_valid <- tail(which(is.finite(marker_series)), 1L)
  if (length(last_valid)) {
    marker_y <- marker_series[[last_valid]]
    prior <- if (last_valid > 1L) marker_series[[last_valid - 1L]] else marker_y
    marker_tone <- if (selected_plot %in% c("open", "high", "low", "close", "vwap"))
      if (marker_y > prior) ink$green else if (marker_y < prior) ink$red else ink$grey
      else ink$grey
    if (!is.null(price_bars))
      marker_tone <- unname(c(gain = ink$green, loss = ink$red,
                              neutral = ink$grey)[price_bars$tone[[last_valid]]])
    marker_x <- max(bars$date) + stock_future_padding(bars$date, compact) * 0.45
    marker_label <- if (comparing) sprintf("%.2f", marker_y) else
      if (selected_plot %in% c("open", "high", "low", "close", "vwap")) money(marker_y) else
        if (selected_plot == "volume") short_volume(marker_y) else
          formatC(marker_y, format = "fg", digits = 4)
    if (selected_display == "heikin_ashi") marker_label <- paste("HA", marker_label)
    price <- price +
      annotate("segment", x = bars$date[[last_valid]], xend = marker_x,
               y = marker_y, yend = marker_y, color = marker_tone,
               linewidth = stroke$wick) +
      annotate("label", x = marker_x, y = marker_y, label = marker_label,
               fill = marker_tone, color = ink$bg, fontface = "bold",
               size = chart_label_mm(3.1),
               label.padding = unit(0.12, "lines"), label.r = unit(2, "pt"), linewidth = 0)
  }
  status <- stock_status(bars, bars$plotted, ticker, comparing, compact)
  subtitle <- paste(c(if (selected_display == "heikin_ashi") "Heikin-Ashi · derived OHLC",
                      status,
                      if (length(overlay_caption)) paste(overlay_caption, collapse = "   ·   ")),
                    collapse = "\n")
  plot <- price + stock_x_scale(bars$date, compact) +
    scale_y_continuous(labels = axis_label, position = "right",
                       breaks = scales::breaks_pretty(n = 5),
                       expand = expansion(mult = c(0.04, 0.06))) +
    labs(title = if (selected_layout == "separate" && length(selected_comparisons)) ticker else NULL,
         subtitle = subtitle,
         caption = NULL,
         x = NULL, y = NULL) + chart_theme() +
    theme(plot.subtitle = element_text(color = ink$white,
                                       size = chart_text_pt(10), lineheight = 0.95))
  if (!show_time_axis) plot <- plot +
    theme(axis.text.x = element_blank(), axis.ticks.x = element_blank(),
          axis.title.x = element_blank())
  plot
}

stock_lower_plot <- function(bars, compact = FALSE) {
  p <- attr(bars, "effective_parameters") %||% chart_parameters
  lower_breaks <- scales::breaks_pretty(n = if (compact) 3 else 5)
  plot <- if (selected_indicator == "field") {
    values <- bars[[selected_custom_study]]
    if (any(!is.finite(values)))
      stop(paste("Alpaca did not provide", selected_custom_study, "for every bar in this study."), call. = FALSE)
    ggplot(data.frame(date = bars$date, value = values), aes(date, value)) +
      geom_col(fill = ink$purple) +
      scale_y_continuous(labels = scales::label_comma(), position = "right",
                         breaks = lower_breaks) +
      labs(title = paste("Alpaca", selected_custom_study), x = NULL, y = NULL) + chart_theme()
  } else if (selected_indicator == "custom") {
    column <- paste0("custom_", selected_custom_study)
    ggplot(bars, aes(date, .data[[column]])) +
      geom_line(color = ink$purple, linewidth = stroke$study, na.rm = TRUE) +
      labs(title = gsub("_", " ", selected_custom_study, fixed = TRUE), x = NULL, y = NULL) + chart_theme()
  } else if (selected_indicator == "macd") {
    ggplot(bars, aes(date)) +
      geom_line(aes(y = macd), color = ink$purple, linewidth = stroke$study) +
      geom_line(aes(y = signal), color = ink$grey, linewidth = stroke$overlay) +
      labs(title = paste("MACD", p[[6L]], "/", p[[7L]], "/", p[[8L]]), x = NULL, y = NULL) + chart_theme()
  } else if (selected_indicator == "return") {
    bars$tone <- ifelse(bars$bar_return > 0, "gain",
                        ifelse(bars$bar_return < 0, "loss", "neutral"))
    ggplot(bars, aes(date, bar_return, fill = tone)) +
      geom_col() + scale_fill_manual(values = c(gain = ink$green, loss = ink$red,
                                                neutral = ink$grey)) +
      scale_y_continuous(labels = scales::label_percent(), position = "right",
                         breaks = lower_breaks) +
      labs(title = paste("Bar-to-bar percent change in", selected_plot), x = NULL, y = NULL) + chart_theme()
  } else if (selected_indicator == "volatility") {
    ggplot(bars, aes(date, volatility)) +
      geom_line(color = ink$purple, linewidth = stroke$study, na.rm = TRUE) +
      scale_y_continuous(labels = scales::label_percent(), position = "right",
                         breaks = lower_breaks) +
      labs(title = paste(indicator_label("Volatility", p[[9L]], chart_parameters[[9L]]),
                         "bar return standard deviation"),
           x = NULL, y = NULL) + chart_theme()
  } else {
    ggplot(bars, aes(date, rsi)) +
      geom_hline(yintercept = c(30, 70), color = ink$grey, linetype = "dashed") +
      geom_line(color = ink$purple, linewidth = stroke$study, na.rm = TRUE) +
      coord_cartesian(ylim = c(0, 100)) +
      labs(title = indicator_label("RSI", p[[5L]], chart_parameters[[5L]]),
           x = NULL, y = NULL) + chart_theme()
  }
  if (!selected_indicator %in% c("field", "return", "volatility"))
    plot <- plot + scale_y_continuous(position = "right",
                                     breaks = if (selected_indicator == "rsi") {
                                       if (compact) c(0, 100) else c(0, 25, 50, 75, 100)
                                     } else lower_breaks)
  plot + stock_x_scale(bars$date, compact) +
    theme(plot.title = element_text(size = chart_text_pt(if (compact) 10 else 13)))
}

render_view <- function(data) {
  account <- data$account
  positions <- data$positions
  history <- data$history
  equity <- number_field(account, "equity")
  last_equity <- number_field(account, "last_equity")
  cash <- number_field(account, "cash")
  daily_change <- if (is.finite(equity) && is.finite(last_equity)) equity - last_equity else NA_real_
  gross <- sum(abs(positions$value[is.finite(positions$value)]))
  count <- nrow(positions)
  unrealized <- if (!count || any(is.finite(positions$unrealized)))
    sum(positions$unrealized[is.finite(positions$unrealized)]) else NA_real_
  png(output_path, width = plot_width, height = plot_height, res = chart_dpi, bg = ink$bg)
  on.exit(dev.off(), add = TRUE)
  grid.newpage()
  grid.rect(gp = gpar(fill = ink$bg, col = NA))
  pushViewport(viewport(layout = grid.layout(12, 12)))

  if (!selected_view %in% c("Overview", "Performance", "Allocation", "Risk lens",
                            "Holdings", "Stock lab")) stop("Unknown portfolio view.", call. = FALSE)

  if (selected_view != "Stock lab") {
    draw_metric(1, "ACCOUNT EQUITY", money(equity), "Current Alpaca account")
    draw_metric(2, "CHANGE VS PRIOR CLOSE", money(daily_change),
                if (is.finite(last_equity) && last_equity != 0) pct(daily_change / last_equity) else "—",
                metric_tone(daily_change))
    draw_metric(3, "GROSS POSITION VALUE", money(gross), paste(count, "open positions"))
    draw_metric(4, "UNREALIZED P&L", money(unrealized), paste("Cash", money(cash)),
                metric_tone(unrealized))
  }

  if (selected_view == "Overview") {
    if (nrow(history) > 1L) draw_plot(equity_chart(history), 3:12, 1:8)
    else draw_message(3:12, 1:8, "Account history unavailable",
                      if (is.null(data$history_error)) "No daily observations for this period."
                      else data$history_error)
    if (count > 0L && gross > 0) draw_plot(allocation_chart(positions), 3:12, 9:12)
    else draw_message(3:12, 9:12, "No open positions", "The selected account currently has no holdings.")
  } else if (selected_view == "Performance") {
    if (nrow(history) > 1L) {
      draw_plot(equity_chart(history), 3:7, 1:12)
      pnl_history <- history[is.finite(history$pnl), , drop = FALSE]
      if (nrow(pnl_history)) {
        pnl_history$tone <- ifelse(pnl_history$pnl > 0, "gain",
                                   ifelse(pnl_history$pnl < 0, "loss", "neutral"))
        pnl <- ggplot(pnl_history, aes(date, pnl, fill = tone)) +
          geom_col() + scale_fill_manual(values = c(gain = ink$green, loss = ink$red,
                                                    neutral = ink$grey)) +
          scale_y_continuous(labels = scales::label_dollar()) +
          labs(title = "Alpaca reported P&L", x = NULL, y = NULL) + chart_theme()
        draw_plot(pnl, 8:12, 1:12)
      } else draw_message(8:12, 1:12, "P&L unavailable", "Alpaca did not provide a P&L series.")
    } else draw_message(3:12, 1:12, "Account history unavailable",
                        if (is.null(data$history_error)) "No daily observations for this period."
                        else data$history_error)
  } else if (selected_view == "Allocation") {
    if (count && gross > 0) {
      draw_plot(allocation_chart(positions), 3:12, 1:8)
      draw_plot(position_pnl_chart(positions, "unrealized", "Unrealized P&L"), 3:12, 9:12)
    } else draw_message(3:12, 1:12, "No open positions", "Allocation appears once positions exist in this account.")
  } else if (selected_view == "Risk lens") {
    if (count && gross > 0) {
      weights <- abs(positions$value[is.finite(positions$value)]) / gross
      largest <- max(weights)
      effective <- 1 / sum(weights^2)
      draw_metric(1, "LARGEST WEIGHT", pct(largest), "Gross exposure share")
      draw_metric(2, "EFFECTIVE POSITIONS", sprintf("%.1f", effective), "1 / sum(weight²)")
      draw_metric(3, "GROSS / EQUITY", if (is.finite(equity) && equity > 0) pct(gross / equity) else "—",
                  "Exposure ratio")
      draw_metric(4, "OPEN POSITIONS", as.character(count), "Current account")
      draw_plot(allocation_chart(positions), 3:12, 8:12)
    } else draw_message(3:12, 8:12, "No exposure", "The account has no open positions.")
    if (nrow(history) > 1L) {
      history$drawdown <- history$equity / cummax(history$equity) - 1
      curve <- ggplot(history, aes(date, drawdown)) +
        geom_area(fill = ink$red, alpha = 0.22) +
        geom_line(color = ink$red, linewidth = stroke$study) +
        scale_y_continuous(labels = scales::label_percent()) +
        labs(title = "Equity drawdown", subtitle = "Cash flows may affect this curve",
             x = NULL, y = NULL) + chart_theme()
      draw_plot(curve, 3:12, 1:7)
    } else draw_message(3:12, 1:7, "Drawdown unavailable", "Daily account equity history is needed.")
  } else if (selected_view == "Holdings") {
    if (count && gross > 0) {
      draw_plot(position_pnl_chart(positions, "unrealized", "Unrealized P&L by position"), 3:12, 1:7)
      draw_plot(position_pnl_chart(positions, "intraday", "Intraday P&L by position"), 3:12, 8:12)
    } else draw_message(3:12, 1:12, "No open positions", "This Alpaca account has no current holdings.")
  } else if (selected_view == "Stock lab") {
    bars <- data$stock_bars
    if (nrow(bars) > 1L) {
      extra <- data$comparison_bars %||% list()
      if (selected_layout == "separate") {
        series <- c(setNames(list(bars), selected_stock), extra)
        count_series <- length(series)
        columns <- if (count_series == 1L) 1L else if (count_series <= 4L) 2L else 3L
        rows <- ceiling(count_series / columns)
        column_width <- 12L / columns
        row_height <- 12L / rows
        for (index in seq_along(series)) {
          stock_bars <- prepare_stock_chart(series[[index]])
          chart_row <- (index - 1L) %/% columns
          chart_col <- (index - 1L) %% columns
          available_rows <- (chart_row * row_height + 1L):((chart_row + 1L) * row_height)
          available_columns <- (chart_col * column_width + 1L):((chart_col + 1L) * column_width)
          if (count_series == 3L && index == 3L) available_columns <- 1:12
          if (count_series == 5L && index >= 4L)
            available_columns <- if (index == 4L) 1:6 else 7:12
          lower_height <- if (row_height == 12L) 3L else 2L
          price_rows <- if (selected_indicator == "none") available_rows else
            head(available_rows, -lower_height)
          compact <- count_series > 1L || plot_width / columns < 1100L
          draw_plot(stock_price_plot(stock_bars, names(series)[[index]], data$chart_feed,
                                     show_time_axis = selected_indicator == "none",
                                     compact = compact),
                    price_rows, available_columns)
          if (selected_indicator != "none")
            draw_plot(stock_lower_plot(stock_bars, compact), tail(available_rows, lower_height),
                      available_columns)
        }
      } else {
        stock_bars <- prepare_stock_chart(bars)
        prepared_extra <- lapply(extra, prepare_stock_chart)
        compact <- plot_width < 1100L
        lower_rows <- if (selected_indicator == "none") integer() else
          if (plot_height < 500L) 9:12 else 10:12
        draw_plot(stock_price_plot(stock_bars, selected_stock, data$chart_feed, prepared_extra,
                                   show_time_axis = selected_indicator == "none", compact = compact),
                  if (!length(lower_rows)) 1:12 else head(1:12, -length(lower_rows)), 1:12)
        if (selected_indicator != "none")
          draw_plot(stock_lower_plot(stock_bars, compact), lower_rows, 1:12)
      }
    } else {
      draw_message(1:12, 1:12, "Price history unavailable",
                   if (is.null(data$stock_error)) "Alpaca returned no bars for this stock and period."
                   else data$stock_error)
    }
  }

  popViewport()
  invisible(NULL)
}

main <- function() {
  if (!nzchar(selected_data_file)) stop("Observed data file is required for rendering.", call. = FALSE)
  data <- readRDS(selected_data_file)
  if (!selected_stock %in% data$stock_symbols && length(data$stock_symbols)) {
    selected_stock <<- data$stock_symbols[[1L]]
  }
  render_view(data)
  if (selected_view == "Stock lab") {
    bars <- data$stock_bars
    chart_meta <- list(count = nrow(bars),
                       last = if (nrow(bars)) format(max(bars$date), "%b %d %H:%M UTC", tz = "UTC") else "",
                       feed = data$chart_feed %||% "")
    cat("TALG_CHART_META_JSON:", jsonlite::toJSON(chart_meta, auto_unbox = TRUE), "\n", sep = "")
  }
  cat("TALG_SYMBOLS:", paste(data$stock_symbols, collapse = ","), "\n", sep = "")
  cat("TALG_POSITIONS_JSON:",
      jsonlite::toJSON(data$positions, dataframe = "rows", auto_unbox = TRUE, na = "null"),
      "\n", sep = "")
}

if (sys.nframe() == 0L) {
  if (!length(args) %in% c(14L, 16L, 17L, 18L, 20L)) stop("Invalid Java display request.", call. = FALSE)
  tryCatch(main(), error = function(error) {
    cat("TALG_ERROR:", conditionMessage(error), "\n", sep = "")
    quit(save = "no", status = 1L)
  })
}

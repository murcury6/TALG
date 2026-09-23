library(shiny)
library(ggplot2)

source(file.path("R", "portfolio_data.R"), local = TRUE)

data_paths <- list(
  holdings = Sys.getenv("TALG_HOLDINGS_CSV", file.path("..", "..", "examples", "portfolio_holdings.csv")),
  portfolio = Sys.getenv("TALG_PORTFOLIO_HISTORY_CSV", file.path("..", "..", "examples", "portfolio_history.csv")),
  stocks = Sys.getenv("TALG_STOCK_HISTORY_CSV", file.path("..", "..", "examples", "stock_history.csv"))
)
portfolio_data <- load_portfolio_data(data_paths$holdings, data_paths$portfolio, data_paths$stocks)

currency <- function(x, digits = 0L) {
  paste0(ifelse(x < 0, "−$", "$"), format(abs(x), big.mark = ",", nsmall = digits,
                                           digits = max(1L, digits), scientific = FALSE, trim = TRUE))
}
percent <- function(x, digits = 1L) sprintf(paste0("%+.", digits, "f%%"), 100 * x)
theme_talg <- function() {
  theme_minimal(base_family = "sans") +
    theme(
      plot.background = element_rect(fill = "#172435", color = NA),
      panel.background = element_rect(fill = "#172435", color = NA),
      panel.grid.major = element_line(color = "#2d4055", linewidth = 0.35),
      panel.grid.minor = element_blank(),
      axis.text = element_text(color = "#9fb2c5"),
      axis.title = element_text(color = "#9fb2c5"),
      plot.title = element_text(color = "#f2f7fb", face = "bold", size = 13),
      plot.subtitle = element_text(color = "#9fb2c5", size = 9),
      legend.position = "bottom",
      legend.text = element_text(color = "#c7d5e2"),
      legend.title = element_blank(),
      legend.background = element_rect(fill = "#172435", color = NA)
    )
}

kpi_card <- function(label, value_id, note_id, tone = "neutral") {
  div(class = paste("kpi-card", tone),
      div(class = "kpi-label", label),
      div(class = "kpi-value", textOutput(value_id, inline = TRUE)),
      div(class = "kpi-note", textOutput(note_id, inline = TRUE)))
}

ui <- fluidPage(
  tags$head(
    tags$title("TALG | Portfolio Analytics"),
    tags$link(rel = "stylesheet", type = "text/css", href = "styles.css")
  ),
  div(class = "app-shell",
    tags$aside(class = "control-rail",
      div(class = "brand", "TALG"),
      div(class = "brand-caption", "PORTFOLIO ANALYTICS"),
      div(class = "rail-section-label", "WORKSPACE"),
      radioButtons("workspace", NULL,
        choices = c("Overview", "Performance", "Allocation", "Risk lens", "Holdings", "Stock lab"),
        selected = "Overview"
      ),
      div(class = "rail-section-label", "ANALYSIS CONTROLS"),
      selectInput("period", "Date range", c("1 month" = "1M", "3 months" = "3M",
        "6 months" = "6M", "Year to date" = "YTD", "1 year" = "1Y", "All history" = "ALL"),
        selected = "ALL"),
      selectInput("performance_view", "Performance view",
        c("Portfolio value" = "value", "Growth of $100" = "index",
          "Profit over contributions" = "profit")),
      selectInput("allocation_group", "Allocation grouping",
        c("Each stock held" = "symbol", "Sector" = "sector")),
      selectInput("stock", "Selected holding", choices = portfolio_data$holdings$symbol),
      checkboxGroupInput("overlays", "Price overlays",
        c("Average cost" = "cost", "SMA 5" = "sma5", "SMA 10" = "sma10",
          "EMA 5" = "ema5", "Bollinger 10" = "bollinger"),
        selected = c("cost", "sma5")),
      radioButtons("indicator", "Lower indicator",
        c("RSI 7" = "rsi", "MACD 5/10/4" = "macd", "Daily return" = "return",
          "Rolling volatility 10" = "volatility"), selected = "rsi"),
      div(class = "data-badge", span(class = "status-dot"), "SYNTHETIC SAMPLE DATA"),
      div(class = "rail-footer", "RESEARCH DISPLAY  •  NO ORDERS")
    ),
    tags$main(class = "main-stage",
      div(class = "topbar",
        div(
          div(class = "eyebrow", "PORTFOLIO COMMAND CENTER"),
          h1(textOutput("page_title", inline = TRUE)),
          p(textOutput("page_subtitle", inline = TRUE))
        ),
        div(class = "as-of",
          div(class = "as-of-label", "VALUATION AS OF"),
          div(class = "as-of-value", textOutput("as_of", inline = TRUE))
        )
      ),
      conditionalPanel("input.workspace == 'Overview'",
        div(class = "kpi-grid",
          kpi_card("PORTFOLIO VALUE", "total_value", "total_value_note"),
          kpi_card("TODAY'S P&L", "daily_pnl", "daily_pnl_note", "dynamic"),
          kpi_card("UNREALIZED P&L", "unrealized_pnl", "unrealized_pnl_note", "dynamic"),
          kpi_card("EFFECTIVE POSITIONS", "effective_positions", "effective_positions_note")
        ),
        div(class = "content-grid wide-left",
          div(class = "panel", div(class = "panel-heading", "PORTFOLIO TRAJECTORY"),
              plotOutput("overview_equity", height = "320px")),
          div(class = "panel", div(class = "panel-heading", "CAPITAL ALLOCATION"),
              plotOutput("overview_allocation", height = "320px"))
        ),
        div(class = "panel", div(class = "panel-heading", "EACH STOCK HELD"),
            uiOutput("holdings_table_overview"))
      ),
      conditionalPanel("input.workspace == 'Performance'",
        div(class = "section-grid",
          div(class = "panel full", div(class = "panel-heading", "PERFORMANCE HISTORY"),
              plotOutput("performance_plot", height = "430px")),
          div(class = "panel full", div(class = "panel-heading", "PERIOD STATISTICS"),
              uiOutput("performance_stats"))
        )
      ),
      conditionalPanel("input.workspace == 'Allocation'",
        div(class = "content-grid equal",
          div(class = "panel", div(class = "panel-heading", "ALLOCATION WEIGHTS"),
              plotOutput("allocation_plot", height = "430px")),
          div(class = "panel", div(class = "panel-heading", "CONCENTRATION PROFILE"),
              plotOutput("concentration_plot", height = "430px"))
        )
      ),
      conditionalPanel("input.workspace == 'Risk lens'",
        div(class = "kpi-grid risk",
          kpi_card("MAX DRAWDOWN", "max_drawdown", "max_drawdown_note", "negative"),
          kpi_card("ANNUAL VOLATILITY", "annual_volatility", "annual_volatility_note"),
          kpi_card("LARGEST POSITION", "largest_weight", "largest_weight_note"),
          kpi_card("DAILY 95% VaR", "var95", "var95_note", "negative")
        ),
        div(class = "content-grid wide-left",
          div(class = "panel", div(class = "panel-heading", "UNDERWATER CURVE"),
              plotOutput("drawdown_plot", height = "330px")),
          div(class = "panel", div(class = "panel-heading", "RETURN DISTRIBUTION"),
              plotOutput("return_distribution", height = "330px"))
        ),
        div(class = "disclosure", "Historical risk diagnostics describe this sample; they do not bound future loss.")
      ),
      conditionalPanel("input.workspace == 'Holdings'",
        div(class = "panel", div(class = "panel-heading", "COMPLETE HOLDINGS REGISTER"),
            uiOutput("holdings_table_full")),
        div(class = "content-grid equal",
          div(class = "panel", div(class = "panel-heading", "UNREALIZED P&L BY HOLDING"),
              plotOutput("holding_pnl_plot", height = "340px")),
          div(class = "panel", div(class = "panel-heading", "TODAY'S P&L BY HOLDING"),
              plotOutput("holding_day_plot", height = "340px"))
        )
      ),
      conditionalPanel("input.workspace == 'Stock lab'",
        div(class = "stock-header",
          div(span(class = "stock-symbol", textOutput("stock_symbol", inline = TRUE)),
              span(class = "stock-company", textOutput("stock_company", inline = TRUE))),
          div(class = "stock-quote", textOutput("stock_price", inline = TRUE),
              span(class = "stock-change", textOutput("stock_change", inline = TRUE)))
        ),
        div(class = "panel", div(class = "panel-heading", "PRICE & SELECTED OVERLAYS"),
            plotOutput("stock_price_plot", height = "380px")),
        div(class = "panel", div(class = "panel-heading", "SELECTED TECHNICAL INDICATOR"),
            plotOutput("stock_indicator_plot", height = "260px")),
        div(class = "disclosure", "Indicators are descriptive transformations of closing prices, not trading signals.")
      )
    )
  )
)

server <- function(input, output, session) {
  metrics <- portfolio_metrics(portfolio_data)
  titles <- list(
    "Overview" = c("Portfolio overview", "Value, P&L and every current holding at a glance."),
    "Performance" = c("Performance analysis", "Change the date range and portfolio view from the control rail."),
    "Allocation" = c("Allocation analysis", "Inspect position and sector weights for concentration."),
    "Risk lens" = c("Risk lens", "Drawdown, volatility, empirical loss and concentration diagnostics."),
    "Holdings" = c("Holdings register", "Every stock held, its cost basis, market value and contribution."),
    "Stock lab" = c("Stock lab", "Select a holding and layer technical indicators onto its history.")
  )
  output$page_title <- renderText(titles[[input$workspace]][[1L]])
  output$page_subtitle <- renderText(titles[[input$workspace]][[2L]])
  output$as_of <- renderText(format(max(portfolio_data$holdings$as_of_utc), "%b %d, %Y  %H:%M UTC", tz = "UTC"))

  output$total_value <- renderText(currency(metrics$total_value, 2L))
  output$total_value_note <- renderText(sprintf("%d stocks held", nrow(portfolio_data$holdings)))
  output$daily_pnl <- renderText(currency(metrics$daily_pnl, 2L))
  output$daily_pnl_note <- renderText(percent(metrics$daily_pct, 2L))
  output$unrealized_pnl <- renderText(currency(metrics$unrealized_pnl, 2L))
  output$unrealized_pnl_note <- renderText(percent(metrics$unrealized_pct, 2L))
  output$effective_positions <- renderText(sprintf("%.1f", metrics$effective_positions))
  output$effective_positions_note <- renderText("1 / sum of squared weights")
  output$max_drawdown <- renderText(percent(metrics$max_drawdown, 2L))
  output$max_drawdown_note <- renderText("Peak-to-trough in loaded history")
  output$annual_volatility <- renderText(percent(metrics$annual_volatility, 1L))
  output$annual_volatility_note <- renderText("Daily returns × √252")
  output$largest_weight <- renderText(percent(metrics$largest_weight, 1L))
  output$largest_weight_note <- renderText(portfolio_data$holdings$symbol[[which.max(portfolio_data$holdings$weight)]])
  portfolio_returns <- portfolio_data$portfolio$return[is.finite(portfolio_data$portfolio$return)]
  empirical_var <- max(0, -as.numeric(quantile(portfolio_returns, 0.05, type = 1)))
  output$var95 <- renderText(percent(empirical_var, 2L))
  output$var95_note <- renderText("One-day historical quantile")

  filtered_portfolio <- reactive(filter_period(portfolio_data$portfolio, input$period))
  selected_stock <- reactive({
    stock <- portfolio_data$stocks[portfolio_data$stocks$symbol == input$stock, , drop = FALSE]
    filter_period(stock_indicators(stock), input$period)
  })
  selected_holding <- reactive(portfolio_data$holdings[portfolio_data$holdings$symbol == input$stock, , drop = FALSE])

  equity_plot <- function(data, compact = FALSE) {
    if (input$performance_view == "value") {
      frame <- rbind(
        data.frame(date = data$date, series = "Portfolio", value = data$portfolio_value),
        data.frame(date = data$date, series = "Benchmark", value = data$benchmark_value)
      )
      y_label <- "Market value ($)"
    } else if (input$performance_view == "profit") {
      frame <- data.frame(date = data$date, series = "Portfolio profit",
                          value = data$portfolio_value - data$net_contributions)
      y_label <- "Value less contributions ($)"
    } else {
      frame <- rbind(
        data.frame(date = data$date, series = "Portfolio", value = 100 * data$portfolio_value / data$portfolio_value[[1L]]),
        data.frame(date = data$date, series = "Benchmark", value = 100 * data$benchmark_value / data$benchmark_value[[1L]])
      )
      y_label <- "Growth of $100"
    }
    ggplot(frame, aes(date, value, color = series)) +
      geom_line(linewidth = 1.15) +
      scale_color_manual(values = c("Portfolio" = "#5bd6bd", "Benchmark" = "#8196ad",
                                    "Portfolio profit" = "#5bd6bd")) +
      scale_y_continuous(labels = scales::label_dollar(big.mark = ",")) +
      labs(x = NULL, y = if (compact) NULL else y_label) + theme_talg()
  }
  output$overview_equity <- renderPlot(equity_plot(filtered_portfolio(), TRUE), res = 120)
  output$performance_plot <- renderPlot(equity_plot(filtered_portfolio()), res = 120)

  allocation_data <- reactive({
    holdings <- portfolio_data$holdings
    group <- if (input$allocation_group == "sector") holdings$sector else holdings$symbol
    totals <- aggregate(holdings$market_value, list(group = group), sum)
    names(totals)[[2L]] <- "market_value"
    totals$weight <- totals$market_value / sum(totals$market_value)
    totals <- totals[order(totals$weight), ]
    totals$group <- factor(totals$group, levels = totals$group)
    totals
  })
  allocation_chart <- function() {
    ggplot(allocation_data(), aes(weight, group, fill = group)) +
      geom_col(width = 0.62, show.legend = FALSE) +
      geom_text(aes(label = scales::percent(weight, accuracy = 0.1)), hjust = -0.12,
                color = "#dce8f2", size = 3.4) +
      scale_x_continuous(labels = scales::label_percent(), expand = expansion(mult = c(0, 0.18))) +
      scale_fill_manual(values = rep(c("#5bd6bd", "#60a5fa", "#a78bfa", "#f6c76a", "#fb7185"), 5)) +
      labs(x = NULL, y = NULL) + theme_talg()
  }
  output$overview_allocation <- renderPlot(allocation_chart(), res = 120)
  output$allocation_plot <- renderPlot(allocation_chart(), res = 120)
  output$concentration_plot <- renderPlot({
    holdings <- portfolio_data$holdings[order(-portfolio_data$holdings$weight), ]
    holdings$cumulative <- cumsum(holdings$weight)
    ggplot(holdings, aes(reorder(symbol, -weight), cumulative, group = 1)) +
      geom_area(fill = "#5bd6bd", alpha = 0.18) + geom_line(color = "#5bd6bd", linewidth = 1.1) +
      geom_point(color = "#f2f7fb", size = 2.5) +
      scale_y_continuous(labels = scales::label_percent(), limits = c(0, 1.04)) +
      labs(x = "Holdings ranked by weight", y = "Cumulative portfolio weight") + theme_talg()
  }, res = 120)

  holdings_table <- function(full = FALSE) {
    h <- portfolio_data$holdings
    headers <- if (full) c("Symbol", "Company", "Sector", "Shares", "Avg cost", "Price", "Market value", "Day", "Unrealized", "Weight")
               else c("Symbol", "Company", "Market value", "Day", "Unrealized", "Weight")
    rows <- lapply(seq_len(nrow(h)), function(index) {
      row <- h[index, ]
      cells <- if (full) list(
        row$symbol, row$company, row$sector, format(row$shares, trim = TRUE), currency(row$average_cost, 2L),
        currency(row$current_price, 2L), currency(row$market_value, 2L), percent(row$day_change_pct, 2L),
        currency(row$unrealized_pnl, 2L), percent(row$weight, 1L)
      ) else list(row$symbol, row$company, currency(row$market_value, 2L), percent(row$day_change_pct, 2L),
                  currency(row$unrealized_pnl, 2L), percent(row$weight, 1L))
      tags$tr(lapply(seq_along(cells), function(column) {
        value <- cells[[column]]
        numeric_tone <- if (grepl("^[+].*%$", value)) "positive" else if (grepl("^[−-]", value)) "negative" else ""
        tags$td(class = if (column == 1L) "ticker-cell" else numeric_tone, value)
      }))
    })
    div(class = "table-wrap", tags$table(class = "holdings-table",
      tags$thead(tags$tr(lapply(headers, tags$th))), tags$tbody(rows)))
  }
  output$holdings_table_overview <- renderUI(holdings_table(FALSE))
  output$holdings_table_full <- renderUI(holdings_table(TRUE))

  output$drawdown_plot <- renderPlot({
    data <- filtered_portfolio()
    ggplot(data, aes(date, drawdown)) +
      geom_area(fill = "#fb7185", alpha = 0.32) + geom_line(color = "#fb7185", linewidth = 0.8) +
      scale_y_continuous(labels = scales::label_percent()) + labs(x = NULL, y = "Drawdown") + theme_talg()
  }, res = 120)
  output$return_distribution <- renderPlot({
    data <- filtered_portfolio()
    data <- data[is.finite(data$return), ]
    ggplot(data, aes(return)) +
      geom_histogram(bins = max(5L, min(14L, nrow(data))), fill = "#5bd6bd", color = "#172435") +
      geom_vline(xintercept = 0, color = "#8196ad", linetype = 2) +
      scale_x_continuous(labels = scales::label_percent(accuracy = 0.1)) +
      labs(x = "Daily return", y = "Observations") + theme_talg()
  }, res = 120)

  output$performance_stats <- renderUI({
    data <- filtered_portfolio()
    ret <- data$return[is.finite(data$return)]
    start_value <- data$portfolio_value[[1L]]
    end_value <- tail(data$portfolio_value, 1L)
    benchmark_growth <- tail(data$benchmark_value, 1L) / data$benchmark_value[[1L]] - 1
    cells <- list(
      c("Portfolio change", percent(end_value / start_value - 1, 2L)),
      c("Benchmark change", percent(benchmark_growth, 2L)),
      c("Average daily return", if (length(ret)) percent(mean(ret), 3L) else "n/a"),
      c("Positive days", if (length(ret)) scales::percent(mean(ret > 0), accuracy = 0.1) else "n/a"),
      c("Best day", if (length(ret)) percent(max(ret), 2L) else "n/a"),
      c("Worst day", if (length(ret)) percent(min(ret), 2L) else "n/a")
    )
    div(class = "stat-grid", lapply(cells, function(cell) div(class = "stat-cell",
      div(class = "stat-label", cell[[1L]]), div(class = "stat-value", cell[[2L]]))))
  })

  holding_bar <- function(field) {
    h <- portfolio_data$holdings
    h$tone <- ifelse(h[[field]] >= 0, "Gain", "Loss")
    ggplot(h, aes(reorder(symbol, .data[[field]]), .data[[field]], fill = tone)) +
      geom_col(width = 0.62) + coord_flip() +
      scale_fill_manual(values = c("Gain" = "#5bd6bd", "Loss" = "#fb7185")) +
      scale_y_continuous(labels = scales::label_dollar()) + labs(x = NULL, y = NULL) + theme_talg()
  }
  output$holding_pnl_plot <- renderPlot(holding_bar("unrealized_pnl"), res = 120)
  output$holding_day_plot <- renderPlot(holding_bar("daily_pnl"), res = 120)

  output$stock_symbol <- renderText(input$stock)
  output$stock_company <- renderText(selected_holding()$company)
  output$stock_price <- renderText(currency(selected_holding()$current_price, 2L))
  output$stock_change <- renderText(percent(selected_holding()$day_change_pct, 2L))
  output$stock_price_plot <- renderPlot({
    data <- selected_stock()
    holding <- selected_holding()
    plot <- ggplot(data, aes(date, close)) +
      geom_line(color = "#f2f7fb", linewidth = 1.2) +
      geom_point(data = tail(data, 1L), color = "#5bd6bd", size = 3) +
      scale_y_continuous(labels = scales::label_dollar()) +
      labs(x = NULL, y = "Close") + theme_talg()
    if ("cost" %in% input$overlays) plot <- plot + geom_hline(yintercept = holding$average_cost, color = "#f6c76a", linetype = 2)
    if ("sma5" %in% input$overlays) plot <- plot + geom_line(aes(y = sma5, color = "SMA 5"), linewidth = 0.9, na.rm = TRUE)
    if ("sma10" %in% input$overlays) plot <- plot + geom_line(aes(y = sma10, color = "SMA 10"), linewidth = 0.9, na.rm = TRUE)
    if ("ema5" %in% input$overlays) plot <- plot + geom_line(aes(y = ema5, color = "EMA 5"), linewidth = 0.9, na.rm = TRUE)
    if ("bollinger" %in% input$overlays) {
      plot <- plot + geom_ribbon(aes(ymin = bollinger_lower, ymax = bollinger_upper),
        fill = "#60a5fa", alpha = 0.12, inherit.aes = TRUE, na.rm = TRUE)
    }
    plot + scale_color_manual(values = c("SMA 5" = "#5bd6bd", "SMA 10" = "#60a5fa", "EMA 5" = "#a78bfa"))
  }, res = 120)
  output$stock_indicator_plot <- renderPlot({
    data <- selected_stock()
    if (input$indicator == "rsi") {
      ggplot(data, aes(date, rsi7)) + geom_hline(yintercept = c(30, 70), color = "#8196ad", linetype = 2) +
        geom_line(color = "#5bd6bd", linewidth = 1, na.rm = TRUE) + coord_cartesian(ylim = c(0, 100)) +
        labs(x = NULL, y = "RSI (7)") + theme_talg()
    } else if (input$indicator == "macd") {
      ggplot(data, aes(date)) + geom_hline(yintercept = 0, color = "#8196ad") +
        geom_col(aes(y = macd - macd_signal, fill = macd >= macd_signal), show.legend = FALSE) +
        geom_line(aes(y = macd, color = "MACD"), linewidth = 0.9) +
        geom_line(aes(y = macd_signal, color = "Signal"), linewidth = 0.9) +
        scale_fill_manual(values = c("TRUE" = "#5bd6bd", "FALSE" = "#fb7185")) +
        scale_color_manual(values = c("MACD" = "#60a5fa", "Signal" = "#f6c76a")) +
        labs(x = NULL, y = "MACD") + theme_talg()
    } else if (input$indicator == "return") {
      ggplot(data, aes(date, daily_return, fill = daily_return >= 0)) + geom_col(show.legend = FALSE) +
        scale_fill_manual(values = c("TRUE" = "#5bd6bd", "FALSE" = "#fb7185")) +
        scale_y_continuous(labels = scales::label_percent()) + labs(x = NULL, y = "Daily return") + theme_talg()
    } else {
      ggplot(data, aes(date, volatility10)) + geom_line(color = "#a78bfa", linewidth = 1, na.rm = TRUE) +
        scale_y_continuous(labels = scales::label_percent()) + labs(x = NULL, y = "Annualized volatility") + theme_talg()
    }
  }, res = 120)
}

shinyApp(ui, server)

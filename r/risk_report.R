#!/usr/bin/env Rscript

# Descriptive risk diagnostics for daily arithmetic strategy and benchmark returns.
args <- commandArgs(trailingOnly = TRUE)
if (length(args) != 2L) {
  stop("Usage: Rscript r/risk_report.R <returns.csv> <report.md>")
}

input <- args[[1L]]
output <- args[[2L]]
data <- read.csv(input, stringsAsFactors = FALSE)
required <- c("date", "strategy_return", "benchmark_return")
if (!identical(names(data), required)) stop("Unexpected returns CSV header")
if (nrow(data) < 20L) stop("At least 20 daily observations are required")
dates <- as.Date(data$date)
if (anyNA(dates) || anyDuplicated(dates) || is.unsorted(dates, strictly = TRUE)) {
  stop("Dates must be valid, unique, and ascending")
}
strategy <- data$strategy_return
benchmark <- data$benchmark_return
if (any(!is.finite(strategy)) || any(!is.finite(benchmark)) ||
    any(strategy <= -1) || any(benchmark <= -1)) {
  stop("Returns must be finite and greater than -100%")
}

annualized_growth <- function(x) prod(1 + x)^(252 / length(x)) - 1
annualized_vol <- function(x) sd(x) * sqrt(252)
max_drawdown <- function(x) {
  wealth <- c(1, cumprod(1 + x))
  min(wealth / cummax(wealth) - 1)
}
tail_cut <- as.numeric(quantile(strategy, 0.05, type = 1, names = FALSE))
var_95 <- max(0, -tail_cut)
es_95 <- max(0, -mean(strategy[strategy <= tail_cut]))
active <- strategy - benchmark
tracking_error <- annualized_vol(active)
information_ratio <- if (tracking_error > 0) mean(active) * 252 / tracking_error else NA_real_

# Five-day moving-block bootstrap: still descriptive, not a predictive guarantee.
set.seed(42)
block_size <- 5L
draw_block <- function() {
  first <- sample.int(length(strategy) - block_size + 1L, 1L)
  strategy[first:(first + block_size - 1L)]
}
bootstrap_means <- replicate(2000L, {
  sampled <- unlist(replicate(ceiling(length(strategy) / block_size), draw_block(),
                              simplify = FALSE), use.names = FALSE)
  mean(head(sampled, length(strategy))) * 252
})
mean_ci <- quantile(bootstrap_means, c(0.025, 0.975), names = FALSE)

fmt_pct <- function(x) sprintf("%.2f%%", 100 * x)
fmt_num <- function(x) if (is.na(x)) "n/a" else sprintf("%.2f", x)
lines <- c(
  "# TALG | Statistical risk review",
  "",
  sprintf("Sample: %d daily observations, %s to %s.", nrow(data), min(dates), max(dates)),
  "",
  "| Diagnostic | Strategy | Benchmark / comparison |",
  "|:--|--:|--:|",
  sprintf("| Annualized geometric return | %s | %s |", fmt_pct(annualized_growth(strategy)), fmt_pct(annualized_growth(benchmark))),
  sprintf("| Annualized volatility | %s | %s |", fmt_pct(annualized_vol(strategy)), fmt_pct(annualized_vol(benchmark))),
  sprintf("| Maximum drawdown | %s | %s |", fmt_pct(max_drawdown(strategy)), fmt_pct(max_drawdown(benchmark))),
  sprintf("| Historical 95%% VaR (one day) | %s | — |", fmt_pct(var_95)),
  sprintf("| Historical 95%% expected shortfall (one day) | %s | — |", fmt_pct(es_95)),
  sprintf("| Worst observed day | %s | %s |", fmt_pct(min(strategy)), fmt_pct(min(benchmark))),
  sprintf("| Information ratio (active return) | %s | — |", fmt_num(information_ratio)),
  "",
  sprintf("Five-day block-bootstrap 95%% interval for annualized **arithmetic mean**: %s to %s.",
          fmt_pct(mean_ci[[1L]]), fmt_pct(mean_ci[[2L]])),
  "",
  paste0("Assumptions: 252 trading days/year, zero transaction costs in this input, ",
         "daily close-to-close arithmetic returns, and no risk-free-rate adjustment."),
  "VaR and expected shortfall are empirical tail summaries; a short sample cannot establish rare-event safety.",
  "The bootstrap interval describes sampling uncertainty under a five-day dependence approximation, not future returns.",
  "",
  "Synthetic example data are not an observed strategy track record."
)
dir.create(dirname(output), recursive = TRUE, showWarnings = FALSE)
writeLines(lines, output, useBytes = TRUE)
cat(sprintf("Wrote %s\n", output))

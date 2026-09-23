# Privileged data-fetch phase. This process receives Alpaca credentials but runs no user script.
source(file.path("r", "live_portfolio.R"), local = TRUE)

if (sys.nframe() == 0L) {
  args <- commandArgs(trailingOnly = TRUE)
  tryCatch({
    if (length(args) != 6L) stop("Invalid display fetch request.", call. = FALSE)
    output <- args[[1L]]
    view <- args[[2L]]
    period <- args[[3L]]
    ticker <- args[[4L]]
    bars <- args[[5L]]
    comparisons <- if (nzchar(args[[6L]]))
      strsplit(args[[6L]], ",", fixed = TRUE)[[1L]] else character()
    data <- if (view == "Stock lab") load_live_stock(period, ticker, bars, comparisons) else
      load_live_portfolio(period)
    saveRDS(data, output)
  }, error = function(error) {
    cat("TALG_ERROR:", conditionMessage(error), "\n", sep = "")
    quit(save = "no", status = 1L)
  })
}

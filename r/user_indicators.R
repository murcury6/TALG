# Trusted local indicators. Callers run in a process with Alpaca key variables removed.
load_user_indicator <- function(name, bars, root = file.path("work", "indicators")) {
  if (length(name) != 1L || !grepl("^[a-z][a-z0-9_]{0,39}$", name))
    stop("Invalid indicator name.", call. = FALSE)
  path <- file.path(root, paste0(name, ".R"))
  if (!file.exists(path)) stop(paste("Indicator not found:", name), call. = FALSE)
  environment <- new.env(parent = globalenv())
  sys.source(path, envir = environment)
  if (!is.function(environment$talg_indicator))
    stop(paste("Indicator", name, "must define talg_indicator(bars)."), call. = FALSE)
  values <- environment$talg_indicator(bars)
  if (!is.numeric(values) || length(values) != nrow(bars) ||
      any(is.infinite(values)) || !any(is.finite(values)))
    stop(paste("Indicator", name, "must return one numeric value per bar, with at least one finite value."),
         call. = FALSE)
  as.numeric(values)
}

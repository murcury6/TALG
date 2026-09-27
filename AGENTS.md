# User's architecture requirements

- Keep trading/research models open, inspectable and language-based. Strategy decisions belong in readable expressions/scripts and declarative files, not hidden special-case Java rules or UI-only state.
- Keep the model a collection of independent, replaceable bricks: data, fitted predictor, signal expressions, sizing, exits, portfolio and execution configuration.
- UI controls must read and write those same files; expose the actual expressions when adding a decision rule. Preserve custom expressions when editing parameters or round-tripping the UI.
- Keep the runtime, feature calculations, supported-language grammar and data/execution guards visible in source and documented. Do not claim unsupported arbitrary language execution or full market depth.
- This account and runner are PAPER only. Keep that routing, reconciliation, freshness and account checks separate from editable strategy logic. Never turn a strategy expression into unrestricted code execution or a switch to live orders.
- Preserve model snapshots, observations, decisions and fills as a collection of local files. State clearly which saved model is armed and when changes take effect.

- The app owns all visuals: automatically fit table widths to headings, data and available workspace. Model code defines calculations, selection, data fields and bindings, never widths, spacing, formatting, alignment or layout.

- News, trading and stock-selection models have independent named profiles. Dependency links pin saved revisions, including nested dependencies; switching or editing a profile must never silently retarget a consuming model or an armed session. Preserve dependency provenance through restart and daily repeat.

package io.github.murcury6.talg;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.border.EmptyBorder;

/** Desk-adjacent order ticket and one-shot strategy evaluator. No unattended trading loop. */
final class TradingPanel extends JPanel {
    private static final Color BG = new Color(20, 18, 27);
    private static final Color CARD = new Color(34, 30, 45);
    private static final Color TEXT = new Color(232, 229, 239);
    private static final Color MUTED = new Color(153, 149, 164);
    private static final Color PURPLE = new Color(177, 146, 245);
    private static final Color RED = new Color(240, 106, 121);
    private final Supplier<AlpacaSettings> settings;
    private final Supplier<String> mode;
    private final StrategyStore strategies;
    private final StrategyDataService data;
    private final AlpacaOrderClient orders;
    private final JComboBox<String> scripts = new JComboBox<>();
    private final JTextArea strategy = new JTextArea();
    private final JTextField ticker = new JTextField();
    private final JTextField quantity = new JTextField("1");
    private final JTextField cap = new JTextField("250");
    private final JComboBox<String> side = new JComboBox<>(new String[]{"Buy", "Sell"});
    private final JLabel account = new JLabel();
    private final JTextArea log = new JTextArea();
    private final DefaultTableModel orderModel = new DefaultTableModel(
            new Object[]{"ID", "SYMBOL", "SIDE", "QTY", "LIMIT", "STATUS"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable orderTable = new JTable(orderModel);
    private final JButton manualPreview = button("New order");
    private final JButton rulePreview = button("EVALUATE RULE");
    private final JButton submit = button("Submit preview");
    private final JButton refresh = button("Refresh");
    private final JButton checkLast = button("Check last order");
    private final JButton cancel = button("Cancel order");
    private Pending pending;
    private UUID lastOrderId;
    private String lastOrderMode;
    private long generation;
    private boolean busy;
    private final PaperTestPanel paperTest;
    private final DefaultTableModel positionModel = new DefaultTableModel(new String[]{"Symbol", "Side", "Shares", "Avg. entry $", "Price $", "Value $", "Unrealized P/L $", "P/L %"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
        @Override public Class<?> getColumnClass(int column) { return column < 2 ? String.class : java.math.BigDecimal.class; }
    };
    private final JTable positionTable = NewsPanel.table(positionModel);
    private final JComboBox<String> view = new JComboBox<>(new String[]{"Positions", "Open orders", "Activity"});
    private final java.awt.CardLayout workspaceLayout = new java.awt.CardLayout();
    private final JPanel workspace = NewsPanel.panel(workspaceLayout);
    private final JLabel feedback = NewsPanel.label("Connect an account to load positions", MUTED);
    private JPanel strategyWorkspace;
    private final javax.swing.Timer poll;
    private long accountRevision;
    private Runnable tradeNavigator = () -> {};


    TradingPanel(Supplier<AlpacaSettings> settings, Supplier<String> mode, Supplier<String> selectedTicker) {
        this(settings, mode, selectedTicker, Path.of(""), new AlpacaOrderClient());
    }

    TradingPanel(Supplier<AlpacaSettings> settings, Supplier<String> mode, Supplier<String> selectedTicker,
                 Path root, AlpacaOrderClient orders) {
        super(new BorderLayout(0, 5));
        this.settings = settings;
        this.mode = mode;
        this.orders = orders;
        strategies = new StrategyStore(root.resolve("work/strategies"));
        data = new StrategyDataService(root);
        poll = new javax.swing.Timer(15_000, event -> { if (isShowing() && !busy && settings.get() != null) refreshOrders(); });
        setBackground(BG);
        setBorder(new EmptyBorder(4, 4, 4, 4));
        ticker.setText(selectedTicker.get() == null ? "" : selectedTicker.get());

        JPanel toolbar = NewsPanel.panel(new BorderLayout(8, 0));
        toolbar.setPreferredSize(new Dimension(0, 30));
        JPanel actions = NewsPanel.panel(new java.awt.GridBagLayout());
        var cell = new java.awt.GridBagConstraints(); cell.insets = new java.awt.Insets(0, 3, 0, 3);
        account.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        view.setBackground(CARD); view.setForeground(TEXT); view.getAccessibleContext().setAccessibleName("Trade view");
        for (var control : new java.awt.Component[]{view, account, manualPreview, submit, refresh, cancel, checkLast}) actions.add(control, cell);
        toolbar.add(actions, BorderLayout.WEST); toolbar.add(feedback, BorderLayout.CENTER);
        feedback.addPropertyChangeListener("text", event -> feedback.setToolTipText(feedback.getText()));
        add(toolbar, BorderLayout.NORTH);
        manualPreview.addActionListener(event -> editTicket());
        submit.addActionListener(event -> submitPreview()); refresh.addActionListener(event -> refreshOrders());
        cancel.addActionListener(event -> cancelSelected()); checkLast.addActionListener(event -> checkLastStatus());
        view.addActionListener(event -> { workspaceLayout.show(workspace, String.valueOf(view.getSelectedItem())); updateCancel(); });
        log.setEditable(false); log.setLineWrap(true); log.setWrapStyleWord(true);
        log.setFont(new Font("Segoe UI", Font.PLAIN, 13)); log.setBackground(CARD); log.setForeground(TEXT);
        log.setBorder(new EmptyBorder(8, 10, 8, 10));
        log.setText("Use New order to enter a ticket, then review its preview before submitting.\n");
        orderTable.setBackground(CARD); orderTable.setForeground(TEXT); orderTable.setRowHeight(29);
        orderTable.setFillsViewportHeight(true); orderTable.setAutoCreateRowSorter(true);
        orderTable.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        orderTable.getTableHeader().setBackground(CARD); orderTable.getTableHeader().setForeground(TEXT);
        orderTable.getColumnModel().getColumn(0).setMinWidth(0); orderTable.getColumnModel().getColumn(0).setMaxWidth(0);
        orderTable.getSelectionModel().addListSelectionListener(event -> updateCancel());
        CompactUi.table(positionTable); CompactUi.table(orderTable);
        workspace.add(CompactUi.scroll(positionTable), "Positions");
        workspace.add(CompactUi.scroll(orderTable), "Open orders"); workspace.add(CompactUi.scroll(log), "Activity");
        add(workspace, BorderLayout.CENTER);
        positionTable.setToolTipText("Select a position, then New order to prefill its symbol.");
        positionTable.setDefaultRenderer(java.math.BigDecimal.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override public java.awt.Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                    boolean focus, int row, int column) {
                super.getTableCellRendererComponent(table, value, selected, focus, row, column);
                setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
                setText(value == null ? "" : new java.text.DecimalFormat(column == 2 ? "#,##0.####" : "#,##0.00").format(value));
                setForeground(!selected && column >= 6 && value instanceof java.math.BigDecimal amount
                        ? amount.signum() < 0 ? RED : new Color(143, 210, 167) : TEXT);
                return this;
            }
        });
        JPanel strategyPane = new JPanel(new BorderLayout(0, 5));
        strategyPane.setBackground(BG);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        controls.setBackground(BG);
        scripts.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        scripts.setBackground(CARD);
        scripts.setForeground(TEXT);
        scripts.setPreferredSize(new Dimension(180, 25));
        controls.add(scripts);
        JButton load = button("LOAD");
        load.addActionListener(event -> loadScript());
        controls.add(load);
        JButton save = button("SAVE SCRIPT");
        save.addActionListener(event -> saveScript());
        controls.add(save);
        JButton equation = button("+ EQUATION");
        equation.setToolTipText("Make a named value from two signals; use it in later conditions.");
        equation.addActionListener(event -> buildEquation());
        controls.add(equation);
        JButton build = button("+ CONDITION");
        build.setToolTipText("Combine observed fields, indicator inputs, and equations into a Buy or Sell rule.");
        build.addActionListener(event -> buildCondition());
        controls.add(build);
        rulePreview.addActionListener(event -> previewRule());
        controls.add(rulePreview);
        strategyPane.add(controls, BorderLayout.NORTH);
        strategy.setText(StrategyScript.starter());
        strategy.setFont(new Font("Consolas", Font.PLAIN, 12));
        strategy.setForeground(TEXT);
        strategy.setBackground(CARD);
        strategy.setCaretColor(PURPLE);
        strategy.setTabSize(2);
        strategy.setBorder(new EmptyBorder(7, 8, 7, 8));
        strategyPane.add(CompactUi.scroll(strategy), BorderLayout.CENTER);

        strategyWorkspace = strategyPane;
        paperTest = new PaperTestPanel(settings, mode, root);
        poll.start();

        DocumentListener invalidate = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { invalidatePreview(); }
            @Override public void removeUpdate(DocumentEvent event) { invalidatePreview(); }
            @Override public void changedUpdate(DocumentEvent event) { invalidatePreview(); }
        };
        for (JTextField input : List.of(ticker, quantity, cap)) input.getDocument().addDocumentListener(invalidate);
        strategy.getDocument().addDocumentListener(invalidate);
        side.addActionListener(event -> invalidatePreview());
        settingsChanged();
        refreshScriptNames();
        for (int index = 0; index < scripts.getItemCount(); index++)
            if ("Trend Volume".equals(scripts.getItemAt(index))) {
            scripts.setSelectedItem("Trend Volume");
            loadScript();
            break;
            }
    }

    void settingsChanged() {
        invalidatePreview();
        lastOrderId = null;
        lastOrderMode = null;
        accountRevision++;
        String selected = mode.get();
        account.setText(settings.get() == null ? "Disconnected" : selected.toUpperCase(Locale.ROOT));
        account.setToolTipText("Manual day-limit orders · $2,500 maximum per order");
        account.setForeground("live".equals(selected) ? RED : MUTED);
        orderModel.setRowCount(0); positionModel.setRowCount(0);
        feedback.setText(settings.get() == null ? "Connect in Settings" : "Refresh to load positions");
        checkLast.setEnabled(false);
    }

    void closePaperTest() { poll.stop(); paperTest.closeRunner(); }
    JPanel ruleWorkspace() { return strategyWorkspace; }
    void setTradeNavigator(Runnable navigator) { tradeNavigator = navigator; }
    PaperTestPanel paperWorkspace() { return paperTest; }
    void activate() { if (!busy && settings.get() != null) refreshOrders(); }
    private void updateCancel() { cancel.setEnabled(!busy && view.getSelectedIndex() == 1 && orderTable.getSelectedRow() >= 0); }

    private void editTicket() {
        int row = positionTable.getSelectedRow();
        if (view.getSelectedIndex() == 0 && row >= 0)
            ticker.setText(String.valueOf(positionModel.getValueAt(positionTable.convertRowIndexToModel(row), 0)));
        JPanel fields = NewsPanel.panel(new GridLayout(0, 2, 8, 10));
        String[] names = {"Symbol", "Side", "Shares (1–100)", "Maximum order value ($)"};
        javax.swing.JComponent[] controls = {ticker, side, quantity, cap};
        for (int i = 0; i < names.length; i++) {
            JLabel label = NewsPanel.label(names[i], TEXT); label.setLabelFor(controls[i]);
            controls[i].getAccessibleContext().setAccessibleName(names[i]);
            controls[i].setBackground(CARD); controls[i].setForeground(TEXT);
            if (controls[i] instanceof JTextField input) input.setCaretColor(TEXT);
            fields.add(label); fields.add(controls[i]);
        }
        fields.setPreferredSize(new Dimension(430, 160));
        if (JOptionPane.showConfirmDialog(this, fields, "New " + mode.get() + " order — preview first",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            view.setSelectedItem("Activity"); previewManual();
        }
    }

    void addIndicatorInput(String indicatorName, String testedTicker) {
        if (busy) { append("Finish the current trade operation before editing its rule."); return; }
        try {
            IndicatorInsertion insertion = insertIndicator(strategy.getText(), indicatorName);
            if (!insertion.added()) {
                append("Indicator " + indicatorName + " is already an input in this rule.");
                return;
            }
            strategy.setText(insertion.script());
            strategy.requestFocusInWindow();
            append("Added reusable input " + insertion.alias() + " from Indicator("
                    + indicatorName + "). Use " + insertion.alias()
                    + " in a buy/sell equation, then Evaluate Rule. No order was sent.");
            if (!Pattern.compile("(?m)^ticker\\s+" + Pattern.quote(testedTicker) + "\\s*$")
                    .matcher(insertion.script()).find())
                append("The rule's ticker was not changed; check its ticker line before evaluation.");
        } catch (IllegalArgumentException error) { append(error.getMessage()); }
    }

    static IndicatorInsertion insertIndicator(String source, String rawName) {
        String name = IndicatorRepository.name(rawName);
        Pattern existing = Pattern.compile("(?im)^\\s*input\\s+[a-z][a-z0-9_]*\\s+"
                + "(?:Indicator|Custom)\\(" + Pattern.quote(name) + "\\)\\s*$");
        if (existing.matcher(source).find()) return new IndicatorInsertion(source, name, false);
        Set<String> builtins = Set.of("open", "high", "low", "close", "volume", "vwap", "trades");
        String alias = name;
        int suffix = 2;
        while (builtins.contains(alias) || Pattern.compile("(?im)^\\s*(?:input|let)\\s+"
                + Pattern.quote(alias) + "(?:\\s|$)").matcher(source).find())
            alias = name.substring(0, Math.min(name.length(), 40 - ("_" + suffix).length()))
                    + "_" + suffix++;
        String line = "input " + alias + " Indicator(" + name + ")\n";
        Matcher next = Pattern.compile("(?m)^\\s*(?:let|buy|sell)\\s+").matcher(source);
        int at = next.find() ? next.start() : source.length();
        String before = source.substring(0, at);
        if (!before.isEmpty() && !before.endsWith("\n")) before += "\n";
        return new IndicatorInsertion(before + line + source.substring(at), alias, true);
    }

    record IndicatorInsertion(String script, String alias, boolean added) {}

    private void buildEquation() {
        if (busy) { append("Finish the current trade operation before editing its rule."); return; }
        try {
            List<String> signals = StrategyBlocks.signals(strategy.getText());
            JTextField variable = new JTextField();
            JComboBox<String> left = new JComboBox<>(signals.toArray(String[]::new));
            JComboBox<String> operation = new JComboBox<>(
                    new String[]{"% difference", "+", "-", "*", "/"});
            JComboBox<String> right = new JComboBox<>(signals.toArray(String[]::new));
            right.setEditable(true);
            right.setSelectedItem("1");
            JPanel fields = new JPanel(new GridLayout(0, 2, 8, 6));
            fields.add(new JLabel("New signal name")); fields.add(variable);
            fields.add(new JLabel("First signal")); fields.add(left);
            fields.add(new JLabel("Calculation")); fields.add(operation);
            fields.add(new JLabel("Other signal or number")); fields.add(right);
            JPanel dialog = new JPanel(new BorderLayout(0, 8));
            dialog.add(new JLabel("Build a named calculation, then use it in Buy or Sell conditions."),
                    BorderLayout.NORTH);
            dialog.add(fields, BorderLayout.CENTER);
            dialog.add(new JLabel("% difference = (first / other − 1) × 100; 1 means +1%."), BorderLayout.SOUTH);
            if (JOptionPane.showConfirmDialog(this, dialog, "Add equation block",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            String changed = StrategyBlocks.addEquation(strategy.getText(), variable.getText(),
                    String.valueOf(left.getSelectedItem()), String.valueOf(operation.getSelectedItem()),
                    String.valueOf(right.getSelectedItem()));
            strategy.setText(changed);
            append("Equation " + variable.getText().trim() + " added as a reusable signal."
                    + " Add a condition or Evaluate Rule; no order was sent.");
        } catch (IllegalArgumentException error) {
            append("Cannot add equation: " + error.getMessage());
        }
    }

    private void buildCondition() {
        if (busy) { append("Finish the current trade operation before editing its rule."); return; }
        try {
            List<String> signals = StrategyBlocks.signals(strategy.getText());
            JComboBox<String> ruleSide = new JComboBox<>(new String[]{"Buy", "Sell"});
            JComboBox<String> left = new JComboBox<>(signals.toArray(String[]::new));
            JComboBox<String> comparison = new JComboBox<>(new String[]{">", "<", ">=", "<=", "==", "!="});
            JComboBox<String> right = new JComboBox<>(signals.toArray(String[]::new));
            right.setEditable(true);
            right.setSelectedItem("0");
            JComboBox<String> connector = new JComboBox<>(new String[]{"AND", "OR"});
            JPanel fields = new JPanel(new GridLayout(0, 2, 8, 6));
            fields.add(new JLabel("Rule")); fields.add(ruleSide);
            fields.add(new JLabel("Observed field or input")); fields.add(left);
            fields.add(new JLabel("Comparison")); fields.add(comparison);
            fields.add(new JLabel("Other signal or number")); fields.add(right);
            fields.add(new JLabel("If rule already exists, connect with")); fields.add(connector);
            JPanel dialog = new JPanel(new BorderLayout(0, 8));
            dialog.add(new JLabel("Build one condition from the strategy's current signals."), BorderLayout.NORTH);
            dialog.add(fields, BorderLayout.CENTER);
            dialog.add(new JLabel("This edits the visible script only. Evaluate Rule fetches data; Submit requires confirmation."),
                    BorderLayout.SOUTH);
            if (JOptionPane.showConfirmDialog(this, dialog, "Add rule condition",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            String changed = StrategyBlocks.addCondition(strategy.getText(),
                    String.valueOf(ruleSide.getSelectedItem()), String.valueOf(left.getSelectedItem()),
                    String.valueOf(comparison.getSelectedItem()), String.valueOf(right.getSelectedItem()),
                    String.valueOf(connector.getSelectedItem()));
            strategy.setText(changed);
            append("Condition added to " + String.valueOf(ruleSide.getSelectedItem()).toLowerCase(Locale.ROOT)
                    + " rule. Review the script, then Evaluate Rule. No order was sent.");
        } catch (IllegalArgumentException error) {
            append("Cannot add condition: " + error.getMessage());
        }
    }

    private static JPanel field(String label, javax.swing.JComponent input) {
        JPanel panel = new JPanel(new BorderLayout(0, 2));
        panel.setBackground(BG);
        JLabel heading = new JLabel(label);
        heading.setForeground(MUTED);
        heading.setFont(new Font("Segoe UI", Font.BOLD, 9));
        panel.add(heading, BorderLayout.NORTH);
        input.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        input.setForeground(TEXT);
        input.setBackground(CARD);
        panel.add(input, BorderLayout.CENTER);
        return panel;
    }

    private static JButton button(String title) {
        JButton result = new JButton(title);
        result.setFont(new Font("Segoe UI", Font.BOLD, 10));
        result.setForeground(TEXT);
        result.setBackground(CARD);
        result.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(70, 62, 87)), new EmptyBorder(5, 7, 5, 7)));
        result.setFocusPainted(false);
        return result;
    }

    private void invalidatePreview() {
        generation++;
        pending = null;
        submit.setEnabled(false);
    }

    private AlpacaSettings requireSettings() {
        AlpacaSettings current = settings.get();
        if (current == null) throw new IllegalArgumentException("Connect a paper or live Alpaca account in Settings first.");
        return current;
    }

    private double cap() { return Double.parseDouble(cap.getText().trim()); }

    private void previewManual() {
        try {
            AlpacaSettings credentials = requireSettings();
            AlpacaOrderClient.Intent intent = new AlpacaOrderClient.Intent(
                    ticker.getText().trim().toUpperCase(Locale.ROOT),
                    side.getSelectedIndex() == 0 ? "buy" : "sell",
                    Integer.parseInt(quantity.getText().trim()), cap());
            String requestedMode = mode.get();
            long request = ++generation;
            invalidatePreview();
            append("Checking " + requestedMode + " ticket against market clock, account, position, and quote…");
            task(() -> orders.preview(intent, credentials, requestedMode), preview -> {
                if (request + 1 != generation) return;
                pending = new Pending(preview, "manual ticket", null);
                submit.setEnabled(true);
                displayPreview(preview, "manual ticket");
            });
        } catch (RuntimeException error) { append("Ticket error: " + error.getMessage()); }
    }

    private void previewRule() {
        try {
            AlpacaSettings credentials = requireSettings();
            StrategyScript.Parsed parsed = StrategyScript.parse(strategy.getText());
            double maximum = cap();
            String requestedMode = mode.get();
            long request = ++generation;
            invalidatePreview();
            append("Fetching observed " + parsed.ticker() + " bars for " + parsed.name() + "…");
            task(() -> {
                StrategyDataService.Result result = data.evaluate(parsed, credentials, requestedMode);
                String direction = result.evaluation().side();
                AlpacaOrderClient.Preview preview = direction.equals("none") ? null :
                        orders.preview(new AlpacaOrderClient.Intent(parsed.ticker(), direction,
                                parsed.quantity(), maximum), credentials, requestedMode);
                return new RuleOutcome(result, preview);
            }, outcome -> {
                if (request + 1 != generation) return;
                append("Rule " + parsed.name() + " at " + outcome.result().asOf()
                        + " → " + outcome.result().evaluation().side().toUpperCase(Locale.ROOT));
                for (String detail : outcome.result().provenance()) append(detail);
                outcome.result().evaluation().values().forEach((key, value) ->
                        append(key + " = " + String.format(Locale.US, "%.6f", value)));
                if (outcome.preview() != null) {
                    pending = new Pending(outcome.preview(), parsed.name(), outcome.result().asOf());
                    submit.setEnabled(true);
                    displayPreview(outcome.preview(), parsed.name());
                } else append("No order is proposed. Nothing was submitted.");
            });
            tradeNavigator.run(); view.setSelectedItem("Activity");
        } catch (RuntimeException error) { append("Rule error: " + error.getMessage()); }
    }

    private void displayPreview(AlpacaOrderClient.Preview preview, String basis) {
        view.setSelectedItem("Activity");
        append(preview.mode().toUpperCase(Locale.ROOT) + " PREVIEW • " + basis + " • "
                + preview.intent().side().toUpperCase(Locale.ROOT) + " " + preview.intent().quantity()
                + " " + preview.intent().symbol() + " • day limit $" + preview.limitPrice()
                + " • maximum $" + preview.maxValue() + " • quote " + preview.quoteTime()
                + ". Not submitted or filled.");
    }

    private void submitPreview() {
        Pending selected = pending;
        if (selected == null || busy) return;
        AlpacaSettings credentials;
        try { credentials = requireSettings(); }
        catch (RuntimeException error) { append(error.getMessage()); return; }
        AlpacaOrderClient.Preview preview = selected.preview();
        if (!mode.get().equals(preview.mode())) { invalidatePreview(); append("Account mode changed; preview again."); return; }
        String detail = preview.intent().side().toUpperCase(Locale.ROOT) + " "
                + preview.intent().quantity() + " " + preview.intent().symbol()
                + " at DAY LIMIT $" + preview.limitPrice() + " (max $" + preview.maxValue() + ")";
        if (preview.mode().equals("live")) {
            String phrase = "LIVE " + preview.intent().side().toUpperCase(Locale.ROOT) + " "
                    + preview.intent().symbol() + " " + preview.intent().quantity();
            String typed = JOptionPane.showInputDialog(this,
                    "REAL-MONEY ORDER: " + detail + "\nType exactly " + phrase + " to submit.",
                    "Confirm live Alpaca order", JOptionPane.WARNING_MESSAGE);
            if (!phrase.equals(typed)) { append("Live order not confirmed."); return; }
        } else if (JOptionPane.showConfirmDialog(this,
                "Send PAPER order? " + detail + "\nSubmission is not a fill.",
                "Confirm paper order", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        invalidatePreview(); // One-shot: never reuse a preview or retry a request automatically.
        append("Submitting " + preview.mode() + " order once…");
        task(() -> orders.submit(preview, credentials, preview.mode()), result -> {
            lastOrderId = result.orderId();
            lastOrderMode = result.mode();
            checkLast.setEnabled(result.mode().equals(mode.get()));
            append("Alpaca accepted order " + result.orderId() + " • status " + result.status()
                    + " • client ID " + result.clientOrderId()
                    + ". Check Last Status for fills; Refresh Orders shows only open orders.");
            refreshOrders();
        });
    }

    private void checkLastStatus() {
        if (lastOrderId == null || !mode.get().equals(lastOrderMode)) {
            append("No submitted order is available for this account mode.");
            return;
        }
        try {
            AlpacaSettings credentials = requireSettings();
            UUID id = lastOrderId;
            String requestedMode = lastOrderMode;
            task(() -> orders.status(id, credentials, requestedMode), result -> {
                if (requestedMode.equals(mode.get()))
                    append(requestedMode + " order " + id + " • Alpaca status " + result + ".");
            });
        } catch (RuntimeException error) { append(error.getMessage()); }
    }

    private void refreshOrders() {
        try {
            AlpacaSettings credentials = requireSettings();
            String requestedMode = mode.get();
            long revision = accountRevision;
            task(() -> new Holdings(orders.positions(credentials, requestedMode), orders.openOrders(credentials, requestedMode)), result -> {
                if (revision != accountRevision || !requestedMode.equals(mode.get()) || !credentials.equals(settings.get())) return;
                positionModel.setRowCount(0);
                for (var p : result.positions()) positionModel.addRow(new Object[]{p.symbol(), p.side(), p.quantity(),
                        p.averagePrice(), p.currentPrice(), p.marketValue(), p.unrealizedProfit(), p.unrealizedPercent()});
                orderModel.setRowCount(0);
                for (AlpacaOrderClient.OpenOrder row : result.orders())
                    orderModel.addRow(new Object[]{row.id().toString(), row.symbol(), row.side(),
                            row.quantity(), row.limitPrice(), row.status()});
                feedback.setText(result.positions().size() + " positions · " + result.orders().size() + " orders · "
                        + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
            });
        } catch (RuntimeException error) { append(error.getMessage()); }
    }

    private void cancelSelected() {
        int row = orderTable.getSelectedRow();
        if (row < 0) { append("Select an open order first."); return; }
        UUID id = UUID.fromString(String.valueOf(orderModel.getValueAt(orderTable.convertRowIndexToModel(row), 0)));
        String requestedMode = mode.get();
        if (JOptionPane.showConfirmDialog(this, "Request cancellation of " + requestedMode
                + " order " + id + "? A fill may already be in progress.",
                "Cancel order", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        try {
            AlpacaSettings credentials = requireSettings();
            task(() -> { orders.cancel(id, credentials, requestedMode); return id; }, ignored -> {
                append("Cancellation requested for " + id + ". Refresh to verify its status.");
                refreshOrders();
            });
        } catch (RuntimeException error) { append(error.getMessage()); }
    }

    private void refreshScriptNames() {
        try {
            String selected = (String) scripts.getSelectedItem();
            scripts.removeAllItems();
            for (String name : strategies.names()) scripts.addItem(name);
            if (selected != null) scripts.setSelectedItem(selected);
        } catch (Exception error) { append("Could not list strategy scripts: " + error.getMessage()); }
    }

    private void loadScript() {
        String selected = (String) scripts.getSelectedItem();
        if (selected == null) return;
        if (!strategy.getText().equals(StrategyScript.starter())
                && JOptionPane.showConfirmDialog(this, "Replace the current strategy editor text?",
                "Load script", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        try { strategy.setText(strategies.load(selected)); }
        catch (Exception error) { append("Could not load strategy: " + error.getMessage()); }
    }

    private void saveScript() {
        try {
            StrategyScript.Parsed parsed = StrategyScript.parse(strategy.getText());
            Path target = strategies.path(parsed.name());
            if (java.nio.file.Files.exists(target) && JOptionPane.showConfirmDialog(this,
                    "Replace " + target.getFileName() + "?", "Save strategy",
                    JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            strategies.save(strategy.getText());
            refreshScriptNames();
            scripts.setSelectedItem(parsed.name());
            append("Saved " + target + ". Saving does not arm or submit an order.");
        } catch (Exception error) { append("Could not save strategy: " + error.getMessage()); }
    }

    private <T> void task(Callable<T> work, Consumer<T> success) {
        if (busy) return;
        setBusy(true);
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return work.call(); }
            @Override protected void done() {
                setBusy(false);
                try { success.accept(get()); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); append("Operation interrupted."); }
                catch (ExecutionException error) {
                    Throwable cause = error.getCause();
                    append("Operation failed: " + (cause == null ? "unknown error" : cause.getMessage()));
                }
            }
        }.execute();
    }

    private void setBusy(boolean value) {
        busy = value;
        manualPreview.setEnabled(!value);
        rulePreview.setEnabled(!value);
        refresh.setEnabled(!value);
        checkLast.setEnabled(!value && lastOrderId != null && mode.get().equals(lastOrderMode));
        updateCancel();
        submit.setEnabled(!value && pending != null);
    }

    private void append(String message) {
        if (log.getText().length() > 20_000) log.setText("");
        log.append(message + "\n");
        feedback.setText(message);
        log.setCaretPosition(log.getDocument().getLength());
    }

    private record Holdings(List<AlpacaOrderClient.Position> positions, List<AlpacaOrderClient.OpenOrder> orders) {}

    private record Pending(AlpacaOrderClient.Preview preview, String basis, Instant signalAsOf) {}
    private record RuleOutcome(StrategyDataService.Result result, AlpacaOrderClient.Preview preview) {}
}

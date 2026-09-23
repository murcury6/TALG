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
import java.util.UUID;
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
    private final StrategyStore strategies = new StrategyStore(Path.of("work", "strategies"));
    private final StrategyDataService data = new StrategyDataService(Path.of(""));
    private final AlpacaOrderClient orders = new AlpacaOrderClient();
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
    private final JButton manualPreview = button("PREVIEW TICKET");
    private final JButton rulePreview = button("EVALUATE RULE");
    private final JButton submit = button("SUBMIT PREVIEWED ORDER");
    private final JButton refresh = button("REFRESH ORDERS");
    private final JButton checkLast = button("CHECK LAST STATUS");
    private final JButton cancel = button("CANCEL SELECTED");
    private Pending pending;
    private UUID lastOrderId;
    private String lastOrderMode;
    private long generation;
    private boolean busy;

    TradingPanel(Supplier<AlpacaSettings> settings, Supplier<String> mode, Supplier<String> selectedTicker) {
        super(new BorderLayout(0, 5));
        this.settings = settings;
        this.mode = mode;
        setBackground(BG);
        setBorder(new EmptyBorder(7, 9, 7, 9));
        ticker.setText(selectedTicker.get() == null ? "" : selectedTicker.get());

        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setBackground(BG);
        account.setFont(new Font("Segoe UI", Font.BOLD, 11));
        top.add(account, BorderLayout.NORTH);
        JPanel ticket = new JPanel(new GridLayout(1, 5, 6, 0));
        ticket.setBackground(BG);
        ticket.add(field("SYMBOL", ticker));
        ticket.add(field("SIDE", side));
        ticket.add(field("SHARES", quantity));
        ticket.add(field("MAX $ / ORDER", cap));
        manualPreview.addActionListener(event -> previewManual());
        ticket.add(field("", manualPreview));
        top.add(ticket, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

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
        strategyPane.add(new JScrollPane(strategy), BorderLayout.CENTER);

        JPanel lower = new JPanel(new BorderLayout(0, 5));
        lower.setBackground(BG);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        actions.setBackground(BG);
        submit.setForeground(PURPLE);
        submit.addActionListener(event -> submitPreview());
        actions.add(submit);
        refresh.addActionListener(event -> refreshOrders());
        actions.add(refresh);
        checkLast.addActionListener(event -> checkLastStatus());
        actions.add(checkLast);
        cancel.setForeground(RED);
        cancel.addActionListener(event -> cancelSelected());
        actions.add(cancel);
        lower.add(actions, BorderLayout.NORTH);
        log.setEditable(false);
        log.setFont(new Font("Consolas", Font.PLAIN, 11));
        log.setBackground(CARD);
        log.setForeground(TEXT);
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        log.setText("Preview a ticket or evaluate a rule. No order is sent until you confirm Submit.\n");
        JScrollPane logScroll = new JScrollPane(log);
        logScroll.setPreferredSize(new Dimension(0, 95));
        JPanel report = new JPanel(new BorderLayout(0, 5));
        report.setBackground(BG);
        report.add(logScroll, BorderLayout.NORTH);
        orderTable.setBackground(CARD);
        orderTable.setForeground(TEXT);
        orderTable.setSelectionBackground(new Color(66, 54, 92));
        orderTable.setRowHeight(23);
        orderTable.getTableHeader().setBackground(CARD);
        orderTable.getTableHeader().setForeground(TEXT);
        report.add(new JScrollPane(orderTable), BorderLayout.CENTER);
        lower.add(report, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, strategyPane, lower);
        split.setBorder(null);
        split.setResizeWeight(0.56);
        split.setDividerSize(5);
        add(split, BorderLayout.CENTER);
        javax.swing.SwingUtilities.invokeLater(() -> split.setDividerLocation(0.56));

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
        String selected = mode.get();
        account.setText((settings.get() == null ? "DISCONNECTED (" + selected.toUpperCase(Locale.ROOT) + ")"
                : selected.toUpperCase(Locale.ROOT))
                + "  •  day-limit stock orders  •  explicit confirmation  •  $2,500 hard cap");
        account.setForeground("live".equals(selected) ? RED : MUTED);
        orderModel.setRowCount(0);
        checkLast.setEnabled(false);
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
        } catch (RuntimeException error) { append("Rule error: " + error.getMessage()); }
    }

    private void displayPreview(AlpacaOrderClient.Preview preview, String basis) {
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
            task(() -> orders.openOrders(credentials, requestedMode), rows -> {
                if (!requestedMode.equals(mode.get())) return;
                orderModel.setRowCount(0);
                for (AlpacaOrderClient.OpenOrder row : rows)
                    orderModel.addRow(new Object[]{row.id().toString(), row.symbol(), row.side(),
                            row.quantity(), row.limitPrice(), row.status()});
                append(rows.size() + " open " + requestedMode + " order(s) retrieved from Alpaca.");
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
        cancel.setEnabled(!value);
        submit.setEnabled(!value && pending != null);
    }

    private void append(String message) {
        if (log.getText().length() > 20_000) log.setText("");
        log.append(message + "\n");
        log.setCaretPosition(log.getDocument().getLength());
    }

    private record Pending(AlpacaOrderClient.Preview preview, String basis, Instant signalAsOf) {}
    private record RuleOutcome(StrategyDataService.Result result, AlpacaOrderClient.Preview preview) {}
}

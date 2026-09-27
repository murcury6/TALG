package io.github.murcury6.talg;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;

/** A reusable-indicator library: write, syntax-check, test on observed bars, then use. */
final class IndicatorStudioPanel extends JPanel {
    private static final Color BG = new Color(20, 18, 27);
    private static final Color CARD = new Color(34, 30, 45);
    private static final Color TEXT = new Color(232, 229, 239);
    private static final Color MUTED = new Color(153, 149, 164);
    private static final Color PURPLE = new Color(177, 146, 245);
    private static final Color RED = new Color(240, 106, 121);
    private final IndicatorRepository repository = new IndicatorRepository(Path.of(""));
    private final IndicatorTestService tester = new IndicatorTestService(Path.of(""));
    private final Supplier<AlpacaSettings> settings;
    private final Supplier<String> accountMode;
    private final Consumer<Selection> addToChart;
    private final Consumer<Selection> useInTrading;
    private final DefaultListModel<String> savedNames = new DefaultListModel<>();
    private final JList<String> saved = new JList<>(savedNames);
    private final JTextField name = new JTextField();
    private final JTextField ticker = new JTextField();
    private final JComboBox<Starter> starter = new JComboBox<>(Starter.values());
    private final JTextArea code = new JTextArea();
    private final JLabel status = label("Choose a starter or load a saved indicator.", MUTED, 12);
    private final JButton save = button("SAVE", true);
    private final JButton test = button("SAVE & TEST", true);
    private final JButton chart = button("ADD TO CHART", false);
    private final JButton trading = button("USE IN TRADING", false);
    private final JButton load = button("LOAD", false);
    private final JButton fresh = button("NEW", false);
    private final DefaultTableModel previewRows = new DefaultTableModel(
            new Object[]{"Observed bar (UTC)", "Close", "Indicator value"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JScrollPane preview;
    private final JPanel footer = new JPanel(new BorderLayout(0, 4));
    private String savedName = "";
    private String savedSource = Starter.AVERAGE.source();
    private String testedName = "";
    private String testedSource = "";
    private String testedTicker = "";

    record Selection(String name, String ticker) {}

    IndicatorStudioPanel(Supplier<AlpacaSettings> settings, Supplier<String> accountMode,
                         String initialTicker, Consumer<Selection> addToChart,
                         Consumer<Selection> useInTrading) {
        super(new BorderLayout(0, 5));
        this.settings = settings;
        this.accountMode = accountMode;
        this.addToChart = addToChart;
        this.useInTrading = useInTrading;
        setBackground(BG);
        setBorder(new EmptyBorder(7, 9, 7, 9));
        ticker.setText(initialTicker == null ? "" : initialTicker);
        code.setText(Starter.AVERAGE.source());

        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setOpaque(false);
        top.add(label("INDICATORS   1  Choose or load   →   2  Edit & name   →   3  Save & test   →   4  Use",
                TEXT, 12), BorderLayout.NORTH);
        JPanel inputs = new JPanel(new GridLayout(1, 3, 7, 0));
        inputs.setOpaque(false);
        inputs.add(field("INDICATOR NAME", name));
        inputs.add(field("CALCULATION STARTER", starter));
        inputs.add(field("TEST STOCK", ticker));
        top.add(inputs, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        JPanel library = new JPanel(new BorderLayout(0, 5));
        library.setBackground(BG);
        library.setPreferredSize(new Dimension(205, 0));
        library.add(label("SAVED INDICATORS", TEXT, 11), BorderLayout.NORTH);
        saved.setBackground(CARD);
        saved.setForeground(TEXT);
        saved.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        saved.setFixedCellHeight(27);
        library.add(new JScrollPane(saved), BorderLayout.CENTER);
        JPanel libraryActions = new JPanel(new GridLayout(1, 2, 4, 0));
        libraryActions.setOpaque(false);
        load.addActionListener(event -> loadSelected());
        libraryActions.add(load);
        fresh.addActionListener(event -> newIndicator());
        libraryActions.add(fresh);
        library.add(libraryActions, BorderLayout.SOUTH);

        code.setFont(new Font("Consolas", Font.PLAIN, 14));
        code.setForeground(TEXT);
        code.setBackground(CARD);
        code.setCaretColor(PURPLE);
        code.setTabSize(2);
        code.setBorder(new EmptyBorder(8, 9, 8, 9));
        JScrollPane source = new JScrollPane(code);
        source.setBorder(BorderFactory.createLineBorder(new Color(70, 62, 87)));
        JSplitPane editor = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, library, source);
        editor.setDividerSize(5);
        editor.setResizeWeight(0);
        editor.setBorder(null);
        add(editor, BorderLayout.CENTER);

        JTable table = new JTable(previewRows);
        table.setBackground(CARD);
        table.setForeground(TEXT);
        table.setGridColor(new Color(70, 62, 87));
        table.setRowHeight(23);
        table.getTableHeader().setBackground(CARD);
        table.getTableHeader().setForeground(TEXT);
        preview = new JScrollPane(table);
        preview.setBorder(BorderFactory.createLineBorder(new Color(70, 62, 87)));
        preview.setPreferredSize(new Dimension(0, 175));
        preview.setVisible(false);
        footer.setOpaque(false);
        footer.add(preview, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        actions.setOpaque(false);
        save.addActionListener(event -> start(false));
        test.addActionListener(event -> start(true));
        chart.addActionListener(event -> sendTo(addToChart, false));
        trading.addActionListener(event -> sendTo(useInTrading, true));
        actions.add(save);
        actions.add(test);
        actions.add(chart);
        actions.add(trading);
        JPanel actionLine = new JPanel(new BorderLayout());
        actionLine.setOpaque(false);
        actionLine.add(actions, BorderLayout.WEST);
        actionLine.add(status, BorderLayout.CENTER);
        footer.add(actionLine, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);

        starter.addActionListener(event -> {
            Starter choice = (Starter) starter.getSelectedItem();
            if (choice == null || code.getText().equals(choice.source())) return;
            if (code.getText().isBlank() || JOptionPane.showConfirmDialog(this,
                    "Replace the code in the editor with this calculation starter?",
                    "Use starter", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                code.setText(choice.source());
                code.setCaretPosition(0);
                preview.setVisible(false);
            }
        });
        refreshNames();
    }

    private void newIndicator() {
        if (!canDiscard()) return;
        saved.clearSelection();
        name.setText("");
        code.setText(((Starter) starter.getSelectedItem()).source());
        savedName = "";
        savedSource = code.getText();
        testedName = "";
        preview.setVisible(false);
        showStatus("Enter a descriptive name, edit the calculation, then Save & Test.", MUTED);
        name.requestFocusInWindow();
    }

    private void loadSelected() {
        String chosen = saved.getSelectedValue();
        if (chosen == null) { showStatus("Choose an indicator from the saved list first.", RED); return; }
        if (!canDiscard()) return;
        try {
            String source = repository.read(chosen);
            name.setText(chosen);
            code.setText(source);
            code.setCaretPosition(0);
            savedName = chosen;
            savedSource = source;
            testedName = "";
            preview.setVisible(false);
            showStatus("Loaded " + chosen + ". Test it on observed bars or add it to a chart.", MUTED);
        } catch (Exception error) { showStatus(error.getMessage(), RED); }
    }

    private boolean canDiscard() {
        return name.getText().trim().equals(savedName) && code.getText().equals(savedSource)
                || JOptionPane.showConfirmDialog(this, "Discard unsaved indicator edits?",
                "Unsaved edits", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }

    private void start(boolean execute) {
        final String indicator;
        final String stock;
        try {
            indicator = IndicatorRepository.name(name.getText());
            stock = ticker.getText().trim().toUpperCase(java.util.Locale.ROOT);
            if (execute && !stock.matches("[A-Z][A-Z0-9.-]{0,9}"))
                throw new IllegalArgumentException("Enter a stock ticker to test the indicator.");
            if (execute && settings.get() == null)
                throw new IllegalArgumentException("Connect Alpaca in Settings before testing.");
            Path existing = repository.path(indicator);
            if (Files.isRegularFile(existing) && !repository.read(indicator).equals(code.getText())
                    && JOptionPane.showConfirmDialog(this, "Replace saved indicator " + indicator + "?",
                    "Replace indicator", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        } catch (Exception error) { showStatus(error.getMessage(), RED); return; }
        String source = code.getText();
        if (execute || !indicator.equals(testedName) || !source.equals(testedSource)) testedName = "";
        AlpacaSettings credentials = execute ? settings.get() : null;
        String mode = execute ? accountMode.get() : "";
        setBusy(true);
        showStatus(execute ? "Saving and testing on observed Alpaca bars…" :
                "Checking R syntax and saving…", MUTED);
        new SwingWorker<JsonNode, Void>() {
            @Override protected JsonNode doInBackground() throws Exception {
                repository.save(indicator, source);
                return execute ? tester.test(indicator, stock, credentials, mode) : null;
            }

            @Override protected void done() {
                setBusy(false);
                try {
                    JsonNode result = get();
                    savedName = indicator;
                    savedSource = source;
                    name.setText(indicator);
                    refreshNames();
                    saved.setSelectedValue(indicator, true);
                    if (result == null) {
                        preview.setVisible(false);
                        showStatus("Saved " + indicator + ". Syntax valid; not run yet.", MUTED);
                    } else {
                        testedName = indicator;
                        testedSource = source;
                        testedTicker = stock;
                        showPreview(result);
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    showStatus("Indicator test was interrupted.", RED);
                } catch (ExecutionException error) {
                    Throwable cause = error.getCause();
                    String detail = cause == null ? "unknown error" : cause.getMessage();
                    try {
                        if (Files.isRegularFile(repository.path(indicator))
                                && repository.read(indicator).equals(source)) {
                            savedName = indicator;
                            savedSource = source;
                            refreshNames();
                            saved.setSelectedValue(indicator, true);
                            showStatus("Saved " + indicator + ", but test failed: " + detail, RED);
                            return;
                        }
                    } catch (Exception ignored) { /* Show the original failure below. */ }
                    showStatus("Indicator could not be saved or tested: " + detail, RED);
                }
            }
        }.execute();
    }

    private void showPreview(JsonNode result) {
        previewRows.setRowCount(0);
        for (JsonNode row : result.path("preview"))
            previewRows.addRow(new Object[]{row.path("as_of_utc").asText(),
                    cell(row.path("close")), cell(row.path("indicator"))});
        preview.setVisible(true);
        footer.revalidate();
        String latest = cell(result.path("last_value"));
        showStatus(result.path("symbol").asText() + "  ·  " + result.path("feed").asText()
                + "  ·  " + result.path("observed_through_utc").asText()
                + "  ·  latest " + latest + "  ·  " + result.path("finite_count").asInt()
                + " valid / " + result.path("bar_count").asInt() + " bars", MUTED);
    }

    private static String cell(JsonNode node) {
        return node.isNumber() ? String.format(java.util.Locale.ROOT, "%.4f", node.asDouble()) : "—";
    }

    private void sendTo(Consumer<Selection> destination, boolean requireTest) {
        try {
            String indicator = IndicatorRepository.name(name.getText());
            if (!Files.isRegularFile(repository.path(indicator))
                    || !repository.read(indicator).equals(code.getText())) {
                showStatus("Save the current indicator before using it elsewhere.", RED);
                return;
            }
            String stock = ticker.getText().trim().toUpperCase(java.util.Locale.ROOT);
            if (!stock.matches("[A-Z][A-Z0-9.-]{0,9}")) {
                showStatus("Enter a stock ticker to use this indicator.", RED);
                return;
            }
            if (requireTest && (!indicator.equals(testedName) || !code.getText().equals(testedSource)
                    || !stock.equals(testedTicker))) {
                showStatus("Save & Test this indicator on the chosen stock before adding it to a trading rule.", RED);
                return;
            }
            destination.accept(new Selection(indicator, stock));
        } catch (Exception error) { showStatus(error.getMessage(), RED); }
    }

    private void refreshNames() {
        try {
            savedNames.clear();
            for (String item : repository.names()) savedNames.addElement(item);
        } catch (Exception error) { showStatus(error.getMessage(), RED); }
    }

    private void setBusy(boolean busy) {
        for (JButton button : List.of(save, test, chart, trading, load, fresh)) button.setEnabled(!busy);
        for (javax.swing.JComponent field : List.of(name, ticker, starter, code, saved))
            field.setEnabled(!busy);
    }

    private void showStatus(String text, Color tone) {
        status.setForeground(tone);
        status.setText(text == null ? "Indicator operation failed." : text);
        status.setToolTipText(status.getText());
    }

    private static JPanel field(String title, javax.swing.JComponent input) {
        JPanel row = new JPanel(new BorderLayout(0, 3));
        row.setOpaque(false);
        row.add(label(title, MUTED, 10), BorderLayout.NORTH);
        input.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        input.setForeground(TEXT);
        input.setBackground(CARD);
        input.setPreferredSize(new Dimension(110, 27));
        row.add(input, BorderLayout.CENTER);
        return row;
    }

    private static JButton button(String title, boolean primary) {
        JButton result = new JButton(title);
        result.setFont(new Font("Segoe UI", Font.BOLD, 11));
        result.setForeground(primary ? BG : TEXT);
        result.setBackground(primary ? PURPLE : CARD);
        result.setFocusPainted(false);
        result.setBorder(new EmptyBorder(6, 10, 6, 10));
        return result;
    }

    private static JLabel label(String text, Color tone, int size) {
        JLabel result = new JLabel(text);
        result.setFont(new Font("Segoe UI", Font.PLAIN, size));
        result.setForeground(tone);
        return result;
    }

    enum Starter {
        AVERAGE("20-bar close average", "talg_indicator <- function(bars) {\n"
                + "  # Trailing average of 20 observed closing prices.\n"
                + "  as.numeric(stats::filter(bars$close, rep(1 / 20, 20), sides = 1))\n}\n"),
        RETURN("Close return (fraction)", "talg_indicator <- function(bars) {\n"
                + "  # Change from the previous observed close; 0.01 means +1%.\n"
                + "  close <- as.numeric(bars$close)\n"
                + "  c(NA_real_, diff(close) / head(close, -1L))\n}\n"),
        RELATIVE_VOLUME("20-bar relative volume", "talg_indicator <- function(bars) {\n"
                + "  # Volume divided by its trailing 20-bar average.\n"
                + "  volume <- as.numeric(bars$volume)\n"
                + "  baseline <- as.numeric(stats::filter(volume, rep(1 / 20, 20), sides = 1))\n"
                + "  ifelse(is.finite(baseline) & baseline > 0, volume / baseline, NA_real_)\n}\n"),
        ZSCORE("20-bar close z-score", "talg_indicator <- function(bars) {\n"
                + "  # Distance from a trailing mean, in trailing standard deviations.\n"
                + "  close <- as.numeric(bars$close)\n"
                + "  avg <- as.numeric(stats::filter(close, rep(1 / 20, 20), sides = 1))\n"
                + "  avg_sq <- as.numeric(stats::filter(close^2, rep(1 / 20, 20), sides = 1))\n"
                + "  spread <- sqrt(pmax(0, avg_sq - avg^2))\n"
                + "  ifelse(is.finite(spread) & spread > 0, (close - avg) / spread, NA_real_)\n}\n");

        private final String title;
        private final String source;
        Starter(String title, String source) { this.title = title; this.source = source; }
        String source() { return source; }
        @Override public String toString() { return title; }
    }
}

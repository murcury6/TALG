package io.github.murcury6.talg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;

/** Model authoring on observed Alpaca bars; only an explicit Run executes code. */
final class AnalysisStudioPanel extends JPanel {
    private static final Color BG = new Color(20, 18, 27);
    private static final Color CARD = new Color(34, 30, 45);
    private static final Color TEXT = new Color(232, 229, 239);
    private static final Color MUTED = new Color(153, 149, 164);
    private static final Color PURPLE = new Color(177, 146, 245);
    private static final Color RED = new Color(240, 106, 121);
    private final Supplier<AlpacaSettings> settings;
    private final Supplier<String> accountMode;
    private final JComboBox<String> savedModels = new JComboBox<>();
    private final JTextField name = new JTextField();
    private final JTextField symbols = new JTextField();
    private final JTextArea code = new JTextArea();
    private final JButton save = button("SAVE MODEL", true);
    private final JButton run = button("RUN ON ALPACA BARS", false);
    private final JLabel status = label("Starter = historical return baseline, not a forecast. Save or run only when ready.", MUTED);
    private final JScrollPane resultScroll;
    private final DefaultTableModel results = new DefaultTableModel(
            new Object[]{"Symbol", "As of (UTC)", "Metric", "Value", "Unit", "Horizon", "Version"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };

    AnalysisStudioPanel(Supplier<AlpacaSettings> settings, Supplier<String> accountMode,
                        String initialTicker) {
        super(new BorderLayout(0, 5));
        this.settings = settings;
        this.accountMode = accountMode;
        setBackground(BG);
        setBorder(new EmptyBorder(7, 9, 7, 9));
        JPanel fields = new JPanel(new GridLayout(1, 3, 6, 0));
        fields.setOpaque(false);
        fields.add(field("MODEL NAME", name));
        fields.add(field("INPUT STOCKS", symbols));
        fields.add(field("SAVED MODELS", savedModels));
        symbols.setText(initialTicker == null ? "" : initialTicker);
        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.setOpaque(false);
        top.add(fields, BorderLayout.NORTH);
        top.add(label("1  Name & write talg_model(series)   →   2  Save syntax   →   3  Run on observed bars   →   4  Inspect output", MUTED), BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        code.setText(modelTemplate());
        code.setFont(new Font("Consolas", Font.PLAIN, 14));
        code.setForeground(TEXT);
        code.setBackground(CARD);
        code.setCaretColor(PURPLE);
        code.setTabSize(2);
        code.setBorder(new EmptyBorder(7, 8, 7, 8));
        JScrollPane editor = new JScrollPane(code);
        editor.setBorder(BorderFactory.createLineBorder(new Color(70, 62, 87)));
        JTable table = new JTable(results);
        table.setBackground(CARD);
        table.setForeground(TEXT);
        table.setGridColor(new Color(70, 62, 87));
        table.setRowHeight(25);
        table.getTableHeader().setBackground(CARD);
        table.getTableHeader().setForeground(TEXT);
        resultScroll = new JScrollPane(table);
        resultScroll.getViewport().setBackground(CARD);
        resultScroll.setBorder(BorderFactory.createLineBorder(new Color(70, 62, 87)));
        resultScroll.setPreferredSize(new Dimension(0, 175));
        resultScroll.setVisible(false);
        JPanel work = new JPanel(new BorderLayout(0, 5));
        work.setOpaque(false);
        work.add(editor, BorderLayout.CENTER);
        work.add(resultScroll, BorderLayout.SOUTH);
        add(work, BorderLayout.CENTER);

        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 7, 0));
        actions.setOpaque(false);
        JButton load = button("LOAD MODEL", false);
        load.addActionListener(event -> loadModel());
        actions.add(load);
        actions.add(save);
        actions.add(run);
        save.addActionListener(event -> start(false));
        run.addActionListener(event -> start(true));
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.add(actions, BorderLayout.WEST);
        footer.add(status, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
        refreshModels();
    }

    void focusCode() { code.requestFocusInWindow(); }

    private void start(boolean executeModel) {
        final String scriptName;
        final List<String> tickers;
        try {
            scriptName = validName(name.getText());
            tickers = executeModel ? parseSymbols(symbols.getText()) : List.of();
        } catch (IllegalArgumentException error) {
            error(error.getMessage());
            return;
        }
        Path source = sourcePath(scriptName);
        if (Files.exists(source) && !sameSource(source, code.getText())
                && JOptionPane.showConfirmDialog(this,
                "Replace the existing script " + source.getFileName() + "?", "Replace script",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        AlpacaSettings credentials = executeModel ? settings.get() : null;
        if (executeModel && credentials == null) {
            error("Connect Alpaca in Settings before running a model.");
            return;
        }
        String sourceText = code.getText();
        String selectedMode = executeModel ? accountMode.get() : "";
        save.setEnabled(false);
        run.setEnabled(false);
        status.setForeground(MUTED);
        status.setText(executeModel ? "Fetching observed bars and running your model…" : "Checking R syntax and saving…");
        new SwingWorker<RunResult, Void>() {
            @Override protected RunResult doInBackground() throws Exception {
                RScriptFiles.save(projectRoot(), source, sourceText);
                if (!executeModel) return new RunResult(source, null, null);
                Path resultDir = projectRoot().resolve("work/models/results");
                Files.createDirectories(resultDir);
                Path output = resultDir.resolve(scriptName + "-" + Instant.now().toEpochMilli() + ".json");
                Path observedData = Files.createTempFile("talg-model-observed-", ".rds");
                try {
                    ProcessBuilder fetch = RRuntime.builder(projectRoot(), "r/fetch_display_data.R",
                            observedData.toString(), "Stock lab", "1Y", tickers.getFirst(), "1Day",
                            String.join(",", tickers.subList(1, tickers.size())));
                    fetch.directory(projectRoot().toFile());
                    AlpacaProcessEnvironment.supply(fetch, credentials, selectedMode);
                    RScriptFiles.run(fetch, 180, credentials);
                    ProcessBuilder builder = RRuntime.builder(projectRoot(), "r/run_user_model.R",
                            scriptName, String.join(",", tickers), output.toString(),
                            observedData.toString());
                    builder.directory(projectRoot().toFile());
                    AlpacaProcessEnvironment.scrub(builder);
                    RScriptFiles.run(builder, 180, credentials);
                    JsonNode json = new ObjectMapper().readTree(output.toFile());
                    if (!json.path("rows").isArray() || json.path("rows").isEmpty())
                        throw new IOException("The model returned no validated rows.");
                    return new RunResult(source, output, json);
                } catch (Exception error) {
                    Files.deleteIfExists(output);
                    throw error;
                } finally {
                    Files.deleteIfExists(observedData);
                }
            }

            @Override protected void done() {
                save.setEnabled(true);
                run.setEnabled(true);
                try {
                    RunResult result = get();
                    results.setRowCount(0);
                    refreshModels();
                    savedModels.setSelectedItem(scriptName);
                    if (result.json() == null) {
                        resultScroll.setVisible(false);
                        status.setText("Saved " + result.source() + " • syntax valid; not executed");
                    } else {
                        for (JsonNode row : result.json().path("rows")) {
                            if (results.getRowCount() >= 1000) break;
                            results.addRow(new Object[]{row.path("symbol").asText(),
                                    row.path("as_of_utc").asText(), row.path("metric").asText(),
                                    row.path("value").asText(), row.path("unit").asText(),
                                    row.path("horizon").asText(), row.path("model_version").asText()});
                        }
                        status.setText("Model output: " + result.output() + " • "
                                + result.json().path("rows").size() + " validated rows");
                        resultScroll.setVisible(true);
                        resultScroll.revalidate();
                    }
                    status.setForeground(MUTED);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    error("Analysis was interrupted.");
                } catch (ExecutionException error) {
                    Throwable cause = error.getCause();
                    error(cause == null ? "Analysis failed." : cause.getMessage());
                }
            }
        }.execute();
    }

    private void refreshModels() {
        String selected = (String) savedModels.getSelectedItem();
        savedModels.removeAllItems();
        Path directory = projectRoot().resolve("work/models");
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.list(directory)) {
            files.filter(Files::isRegularFile).map(file -> file.getFileName().toString())
                    .filter(file -> file.matches("[a-z][a-z0-9_]{0,39}\\.R"))
                    .map(file -> file.substring(0, file.length() - 2)).sorted()
                    .forEach(savedModels::addItem);
            if (selected != null) savedModels.setSelectedItem(selected);
        } catch (IOException exception) { error("Could not list saved models: " + exception.getMessage()); }
    }

    private void loadModel() {
        String selected = (String) savedModels.getSelectedItem();
        if (selected == null) { error("Choose a saved model first."); return; }
        if (!code.getText().equals(modelTemplate()) && !sameSource(sourcePath(selected), code.getText())
                && JOptionPane.showConfirmDialog(this, "Discard current model edits?",
                "Load model", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        try {
            code.setText(Files.readString(sourcePath(selected), StandardCharsets.UTF_8));
            code.setCaretPosition(0);
            name.setText(selected);
            results.setRowCount(0);
            resultScroll.setVisible(false);
            status.setForeground(MUTED);
            status.setText("Loaded " + selected + ". Save syntax or run it on observed bars.");
        } catch (IOException error) { error(error.getMessage()); }
    }

    private static boolean sameSource(Path file, String source) {
        try { return Files.readString(file, StandardCharsets.UTF_8).equals(source); }
        catch (IOException error) { return false; }
    }

    private static String validName(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z][a-z0-9_]{0,39}"))
            throw new IllegalArgumentException("Use a name of 1–40 letters, digits or underscores, starting with a letter.");
        return value;
    }

    private static List<String> parseSymbols(String raw) {
        String[] parts = raw.trim().toUpperCase(Locale.ROOT).split("\\s*,\\s*", -1);
        if (parts.length < 1 || parts.length > 5)
            throw new IllegalArgumentException("Enter one to five comma-separated stock tickers.");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            if (!part.matches("[A-Z][A-Z0-9.-]{0,9}") || result.contains(part))
                throw new IllegalArgumentException("Enter distinct stock tickers, such as AAPL,MSFT.");
            result.add(part);
        }
        return result;
    }

    private static Path sourcePath(String name) {
        return projectRoot().resolve("work/models").resolve(name + ".R");
    }

    private static Path projectRoot() { return Path.of("").toAbsolutePath().normalize(); }

    private void error(String message) {
        results.setRowCount(0);
        status.setForeground(RED);
        status.setText(message == null ? "Analysis failed." : message);
    }

    private static JPanel field(String title, JComponent input) {
        JPanel field = new JPanel(new BorderLayout(0, 3));
        field.setOpaque(false);
        field.add(label(title, MUTED), BorderLayout.NORTH);
        input.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        input.setForeground(TEXT);
        input.setBackground(CARD);
        input.setPreferredSize(new Dimension(100, 27));
        field.add(input, BorderLayout.CENTER);
        return field;
    }

    private static JButton button(String title, boolean primary) {
        JButton button = new JButton(title);
        button.setFont(new Font("Segoe UI", Font.BOLD, 11));
        button.setForeground(primary ? BG : TEXT);
        button.setBackground(primary ? PURPLE : CARD);
        button.setFocusPainted(false);
        button.setBorder(new EmptyBorder(6, 11, 6, 11));
        return button;
    }

    private static JLabel label(String text, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        label.setForeground(color);
        return label;
    }

    private static String modelTemplate() {
        try {
            return Files.readString(projectRoot().resolve("r/model_starters/observed_return_baseline.R"),
                    StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("The bundled model starter could not be read.", error);
        }
    }

    private record RunResult(Path source, Path output, JsonNode json) {}
}

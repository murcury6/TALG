package io.github.murcury6.talg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
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

/** Author local R indicators and models; only an explicit Run executes a model. */
final class AnalysisStudioPanel extends JPanel {
    private static final Color BG = new Color(20, 18, 27);
    private static final Color CARD = new Color(34, 30, 45);
    private static final Color TEXT = new Color(232, 229, 239);
    private static final Color MUTED = new Color(153, 149, 164);
    private static final Color PURPLE = new Color(177, 146, 245);
    private static final Color RED = new Color(240, 106, 121);
    private final Supplier<AlpacaSettings> settings;
    private final JComboBox<String> kind = new JComboBox<>(new String[]{"Indicator (R)", "Model (R)"});
    private final JTextField name = new JTextField();
    private final JTextField symbols = new JTextField();
    private final JTextArea code = new JTextArea();
    private final JButton save = button("SAVE", true);
    private final JButton run = button("RUN MODEL", false);
    private final JLabel status = label("Local R code is trusted and runs with your user permissions.", MUTED);
    private final DefaultTableModel results = new DefaultTableModel(
            new Object[]{"Symbol", "As of (UTC)", "Metric", "Value", "Unit", "Horizon", "Version"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };

    AnalysisStudioPanel(Supplier<AlpacaSettings> settings, String initialTicker) {
        super(new BorderLayout(0, 5));
        this.settings = settings;
        setBackground(BG);
        setBorder(new EmptyBorder(7, 9, 7, 9));
        JPanel fields = new JPanel(new GridLayout(1, 3, 6, 0));
        fields.setOpaque(false);
        fields.add(field("SCRIPT TYPE", kind));
        fields.add(field("NAME", name));
        JPanel symbolsField = field("MODEL INPUT STOCKS", symbols);
        fields.add(symbolsField);
        name.setText("my_indicator");
        symbols.setText(initialTicker == null ? "" : initialTicker);
        kind.addActionListener(event -> {
            boolean modelMode = kind.getSelectedIndex() == 1;
            run.setVisible(modelMode);
            if (modelMode && symbolsField.getParent() == null) fields.add(symbolsField);
            if (!modelMode && symbolsField.getParent() == fields) fields.remove(symbolsField);
            fields.setLayout(new GridLayout(1, modelMode ? 3 : 2, 6, 0));
            fields.revalidate();
            if (modelMode && "my_indicator".equals(name.getText())) name.setText("my_model");
            if (!modelMode && "my_model".equals(name.getText())) name.setText("my_indicator");
            if (code.getText().isBlank() || code.getText().equals(indicatorTemplate())
                    || code.getText().equals(modelTemplate()))
                code.setText(modelMode ? modelTemplate() : indicatorTemplate());
        });
        fields.remove(symbolsField);
        fields.setLayout(new GridLayout(1, 2, 6, 0));
        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.setOpaque(false);
        top.add(fields, BorderLayout.NORTH);
        top.add(label("Indicator: save, then use overlay Custom(name) or study Custom(name). Model: save and run on Alpaca bars.", MUTED), BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        code.setText(indicatorTemplate());
        code.setFont(new Font("Consolas", Font.PLAIN, 12));
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
        JScrollPane resultScroll = new JScrollPane(table);
        resultScroll.getViewport().setBackground(CARD);
        resultScroll.setBorder(BorderFactory.createLineBorder(new Color(70, 62, 87)));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editor, resultScroll);
        split.setResizeWeight(0.68);
        split.setDividerSize(5);
        split.setBorder(null);
        split.setBackground(BG);
        add(split, BorderLayout.CENTER);

        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 7, 0));
        actions.setOpaque(false);
        actions.add(save);
        actions.add(run);
        run.setVisible(false);
        save.addActionListener(event -> start(false));
        run.addActionListener(event -> start(true));
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.add(actions, BorderLayout.WEST);
        footer.add(status, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
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
        boolean model = kind.getSelectedIndex() == 1;
        if (executeModel && !model) return;
        if (code.getText().equals(indicatorTemplate()) || code.getText().equals(modelTemplate())) {
            error("Replace the template's stop(...) line with your own calculation before saving.");
            return;
        }
        Path source = sourcePath(model, scriptName);
        if (Files.exists(source) && JOptionPane.showConfirmDialog(this,
                "Replace the existing script " + source.getFileName() + "?", "Replace script",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        AlpacaSettings credentials = executeModel ? settings.get() : null;
        if (executeModel && credentials == null) {
            error("Connect Alpaca in Settings before running a model.");
            return;
        }
        String sourceText = code.getText();
        save.setEnabled(false);
        run.setEnabled(false);
        status.setForeground(MUTED);
        status.setText(executeModel ? "Fetching observed bars and running your model…" : "Checking R syntax and saving…");
        new SwingWorker<RunResult, Void>() {
            @Override protected RunResult doInBackground() throws Exception {
                saveSource(source, sourceText);
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
                    AlpacaProcessEnvironment.supply(fetch, credentials, "paper");
                    runProcess(fetch, 180, credentials);
                    ProcessBuilder builder = RRuntime.builder(projectRoot(), "r/run_user_model.R",
                            scriptName, String.join(",", tickers), output.toString(),
                            observedData.toString());
                    builder.directory(projectRoot().toFile());
                    AlpacaProcessEnvironment.scrub(builder);
                    runProcess(builder, 180, credentials);
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
                    if (result.json() == null) {
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

    private static void saveSource(Path destination, String contents) throws Exception {
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), "talg-script-", ".R");
        try {
            Files.writeString(temporary, contents, StandardCharsets.UTF_8);
            runProcess(RRuntime.builder(projectRoot(), "-e", "parse(file=commandArgs(TRUE)[1])",
                    temporary.toString()).directory(projectRoot().toFile()), 30);
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void runProcess(ProcessBuilder builder, int timeoutSeconds) throws Exception {
        runProcess(builder, timeoutSeconds, null);
    }

    private static void runProcess(ProcessBuilder builder, int timeoutSeconds,
                                   AlpacaSettings credentials) throws Exception {
        builder.redirectErrorStream(true);
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        Thread reader = Thread.startVirtualThread(() -> {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    if (output.length() < 6000) output.append(line).append('\n');
                }
            } catch (IOException ignored) { /* The process exit code reports failure. */ }
        });
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("R exceeded the " + timeoutSeconds + " second run limit.");
        }
        reader.join(2000);
        if (process.exitValue() != 0) {
            String message = output.toString().trim();
            if (credentials != null) message = message.replace(credentials.apiKey(), "<redacted>")
                    .replace(credentials.apiSecret(), "<redacted>");
            throw new IOException(message.isBlank() ? "R exited with code " + process.exitValue() : message);
        }
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

    private static Path sourcePath(boolean model, String name) {
        return projectRoot().resolve("work").resolve(model ? "models" : "indicators")
                .resolve(name + ".R");
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

    private static String indicatorTemplate() {
        return "talg_indicator <- function(bars) {\n"
                + "  # bars: observed date (UTC), close, volume; return one numeric value per bar.\n"
                + "  stop(\"Replace this line with your indicator calculation\")\n}\n";
    }

    private static String modelTemplate() {
        return "talg_model <- function(series) {\n"
                + "  # series: named list of observed Alpaca bar data frames.\n"
                + "  # Return symbol, as_of_utc, metric, value, unit, horizon, model_version.\n"
                + "  stop(\"Replace this line with your model calculation\")\n}\n";
    }

    private record RunResult(Path source, Path output, JsonNode json) {}
}

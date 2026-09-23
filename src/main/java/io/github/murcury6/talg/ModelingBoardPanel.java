package io.github.murcury6.talg;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;

/** Read-only inspection of real outputs from the user's statistical model. */
final class ModelingBoardPanel extends JPanel {
    private static final Color BG = new Color(20, 18, 27);
    private static final Color CARD = new Color(34, 30, 45);
    private static final Color BORDER = new Color(70, 62, 87);
    private static final Color TEXT = new Color(232, 229, 239);
    private static final Color MUTED = new Color(153, 149, 164);
    private static final Color PURPLE = new Color(177, 146, 245);
    private static final long MAX_FILE_BYTES = 10_000_000L;

    private final JTextField file = new JTextField();
    private final JLabel message = label("Choose a CSV produced by your model. No example data is loaded.",
            11, MUTED, Font.PLAIN);
    private final DefaultTableModel model = new DefaultTableModel(new Object[]{
            "Symbol", "As of (UTC)", "Price USD", "Expected return 1D", "Forecast annual vol", "Model version"
    }, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };

    ModelingBoardPanel(String initialFile) {
        super(new BorderLayout(0, 2));
        setBackground(BG);
        setBorder(new EmptyBorder(2, 3, 2, 3));
        file.setText(initialFile == null ? "" : initialFile);

        JTable table = new JTable(model);
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(28);
        table.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        table.setBackground(CARD);
        table.setForeground(TEXT);
        table.setSelectionBackground(new Color(66, 54, 92));
        table.setGridColor(BORDER);
        table.getTableHeader().setBackground(CARD);
        table.getTableHeader().setForeground(TEXT);
        table.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 10));
        JScrollPane scroll = new JScrollPane(table);
        scroll.getViewport().setBackground(CARD);
        scroll.setBorder(BorderFactory.createLineBorder(BORDER));
        add(scroll, BorderLayout.CENTER);
        add(message, BorderLayout.SOUTH);
        if (!file.getText().isBlank()) SwingUtilities.invokeLater(this::loadFile);
    }

    String currentFile() { return file.getText().trim(); }

    void refresh() { loadFile(); }

    private void loadFile() {
        String input = file.getText().trim();
        if (input.isEmpty()) {
            showError("Choose a model signal CSV first.");
            return;
        }
        final Path path;
        try { path = Path.of(input); }
        catch (RuntimeException error) {
            showError("Invalid file path.");
            return;
        }
        message.setText("Validating model output…");
        new SwingWorker<List<Signal>, Void>() {
            @Override protected List<Signal> doInBackground() throws Exception {
                if (!Files.isRegularFile(path)) throw new IllegalArgumentException("File not found: " + path);
                if (Files.size(path) > MAX_FILE_BYTES) {
                    throw new IllegalArgumentException("File exceeds the 10 MB preview limit.");
                }
                return SignalCsv.read(path);
            }

            @Override protected void done() {
                try { showSignals(get(), path); }
                catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    showError("Validation interrupted.");
                } catch (ExecutionException error) {
                    Throwable cause = error.getCause();
                    showError(cause == null ? "Unable to read model output." : cause.getMessage());
                }
            }
        }.execute();
    }

    private void showSignals(List<Signal> signals, Path path) {
        model.setRowCount(0);
        HashSet<String> versions = new HashSet<>();
        java.time.Instant latest = signals.getFirst().asOfUtc();
        for (Signal signal : signals) {
            versions.add(signal.modelVersion());
            if (signal.asOfUtc().isAfter(latest)) latest = signal.asOfUtc();
            if (model.getRowCount() < 1000) model.addRow(new Object[]{
                    signal.symbol(), signal.asOfUtc().toString(),
                    String.format(Locale.US, "%.2f", signal.priceUsd()),
                    String.format(Locale.US, "%+.2f%%", signal.expectedReturn() * 100),
                    String.format(Locale.US, "%.2f%%", signal.forecastAnnualVolatility() * 100),
                    signal.modelVersion()
            });
        }
        message.setText(signals.size() + " validated signals  •  " + versions.size()
                + " model version(s)  •  latest " + latest + "  •  forecasts, not observed outcomes"
                + (signals.size() > 1000 ? "  •  first 1,000 rows shown" : ""));
        message.setToolTipText("Source: " + path);
    }

    private void showError(String detail) {
        model.setRowCount(0);
        message.setText(detail == null ? "Unable to read model output." : detail);
    }

    private static JLabel label(String text, int size, Color color, int weight) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("Segoe UI", weight, size));
        label.setForeground(color);
        return label;
    }
}

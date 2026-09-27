package io.github.murcury6.talg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/** Java-owned portfolio workspace with an R-rendered analytical canvas. */
final class RPortfolioPanel extends JPanel {
    private static final Color BG = new Color(20, 18, 27);
    private static final Color CARD = new Color(34, 30, 45);
    private static final Color BORDER = new Color(70, 62, 87);
    private static final Color WHITE = new Color(232, 229, 239);
    private static final Color MUTED = new Color(153, 149, 164);
    private static final Color PURPLE = new Color(177, 146, 245);
    private static final Color GREEN = new Color(75, 205, 143);
    private static final Color RED = new Color(240, 106, 121);

    private final JComboBox<String> view = new JComboBox<>(new String[]{
            "Overview", "Performance", "Allocation", "Risk lens", "Holdings"
    });
    private final JComboBox<Choice> period = new JComboBox<>(new Choice[]{
            new Choice("1 month", "1M"), new Choice("3 months", "3M"),
            new Choice("6 months", "6M"), new Choice("Year to date", "YTD"),
            new Choice("1 year", "1Y")
    });
    private final JLabel stock = label("", 12, WHITE, Font.BOLD);
    private final ImageCanvas canvas = new ImageCanvas();
    private final CardLayout contentCards = new CardLayout();
    private final JPanel content = new JPanel(contentCards);
    private final DefaultTableModel positionsModel = new DefaultTableModel(
            new Object[]{"Symbol", "Side", "Quantity", "Last", "Market value", "Day P&L", "Unrealized P&L"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
        @Override public Class<?> getColumnClass(int column) {
            return column >= 2 ? Double.class : String.class;
        }
    };
    private AlpacaSettings settings;
    private String accountMode = "paper";
    private boolean rendering;
    private final boolean stockPanel;
    private final String lowerIndicator;
    private final String overlays;
    private final ChartScript.Config chartConfig;
    private Timer refreshTimer;
    private final Timer resizeTimer;
    private boolean resizePending;
    private boolean closed;

    RPortfolioPanel() {
        this("Overview");
    }

    RPortfolioPanel(String initialView) {
        this(initialView, "3M", "rsi", "sma20,ema20,bollinger20", "");
    }

    RPortfolioPanel(String initialView, String initialPeriod, String initialIndicator) {
        this(initialView, initialPeriod, initialIndicator, "sma20,ema20,bollinger20", "");
    }

    RPortfolioPanel(String initialView, String initialPeriod, String initialIndicator,
                    String initialOverlays, String initialStock) {
        this(initialView, initialPeriod, initialIndicator, initialOverlays, initialStock, null);
    }

    RPortfolioPanel(String initialView, String initialPeriod, String initialIndicator,
                    String initialOverlays, String initialStock, ChartScript.Config chartConfig) {
        super(new BorderLayout(0, 4));
        stockPanel = "Stock lab".equals(initialView);
        this.chartConfig = chartConfig;
        resizeTimer = new Timer(600, event -> {
            if (rendering) resizePending = true;
            else refreshDashboard();
        });
        resizeTimer.setRepeats(false);
        lowerIndicator = initialIndicator;
        overlays = initialOverlays;
        stock.setText(initialStock == null ? "" : initialStock);
        if (stockPanel) view.setModel(new DefaultComboBoxModel<>(new String[]{"Stock lab"}));
        setBackground(BG);
        setBorder(new EmptyBorder(2, 3, 2, 3));
        JPanel center = new JPanel(new BorderLayout());
        center.setOpaque(false);
        canvas.setBorder(BorderFactory.createLineBorder(BORDER));
        canvas.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) {
                if (canvas.hasImage() && settings != null) resizeTimer.restart();
            }
        });
        content.add(canvas, "chart");
        JTable positionsTable = new JTable(positionsModel);
        positionsTable.setAutoCreateRowSorter(true);
        positionsTable.setRowHeight(34);
        positionsTable.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        positionsTable.setBackground(CARD);
        positionsTable.setForeground(WHITE);
        positionsTable.setSelectionBackground(new Color(66, 54, 92));
        positionsTable.setGridColor(BORDER);
        positionsTable.setDefaultRenderer(Double.class, new DefaultTableCellRenderer() {
            @Override public java.awt.Component getTableCellRendererComponent(JTable table, Object value,
                    boolean selected, boolean focus, int row, int column) {
                super.getTableCellRendererComponent(table, value, selected, focus, row, column);
                setHorizontalAlignment(RIGHT);
                int modelColumn = table.convertColumnIndexToModel(column);
                if (value instanceof Number number) {
                    double amount = number.doubleValue();
                    setText(modelColumn == 2 ? String.format(Locale.US, "%,.4f", amount)
                            : String.format(Locale.US, "$%,.2f", amount));
                    if (!selected) setForeground(modelColumn >= 5
                            ? amount > 0 ? GREEN : amount < 0 ? RED : MUTED : MUTED);
                } else {
                    setText("—");
                    if (!selected) setForeground(MUTED);
                }
                return this;
            }
        });
        positionsTable.getTableHeader().setBackground(new Color(43, 37, 59));
        positionsTable.getTableHeader().setForeground(WHITE);
        positionsTable.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 11));
        JScrollPane tableScroll = new JScrollPane(positionsTable);
        tableScroll.getViewport().setBackground(CARD);
        tableScroll.setBorder(BorderFactory.createLineBorder(BORDER));
        content.add(tableScroll, "positions");
        center.add(content, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);
        if (!stockPanel) selectChoice(period, initialPeriod);
        view.setSelectedItem(initialView);
    }

    void connect(AlpacaSettings settings, String mode) {
        if (closed) return;
        if (!"paper".equals(mode) && !"live".equals(mode)) {
            throw new IllegalArgumentException("Unsupported Alpaca account mode");
        }
        this.settings = settings;
        accountMode = mode;
        canvas.setMessage(stockPanel ? "Loading stock history" : "Loading account data",
                stockPanel ? "Requesting " + chartConfig.bars() + " bars from Alpaca."
                        : "Requesting current account, positions and daily history from Alpaca.");
        positionsModel.setRowCount(0);
        if (refreshTimer != null) refreshTimer.stop();
        int interval = stockPanel ? chartConfig.refreshSeconds() : settings.refreshSeconds();
        refreshTimer = interval > 0 ? new Timer(interval * 1000, event -> refreshDashboard()) : null;
        refreshDashboard();
        if (refreshTimer != null) refreshTimer.start();
    }

    void refreshNow() { refreshDashboard(); }

    void close() {
        closed = true;
        if (refreshTimer != null) refreshTimer.stop();
        resizeTimer.stop();
    }

    void disconnect() {
        settings = null;
        if (refreshTimer != null) refreshTimer.stop();
        resizeTimer.stop();
        positionsModel.setRowCount(0);
        contentCards.show(content, "chart");
        canvas.setMessage("Alpaca disconnected", "Connect in Settings to load observed data.");
    }

    private void refreshDashboard() {
        if (closed || settings == null || rendering) return;
        Dimension renderSize = renderDimensions(canvas.getWidth(), canvas.getHeight());
        int requestedWidth = renderSize.width;
        int requestedHeight = renderSize.height;
        int displayWidth = canvas.getWidth() > 0 ? canvas.getWidth() : requestedWidth / 2;
        int displayHeight = canvas.getHeight() > 0 ? canvas.getHeight() : requestedHeight / 2;
        rendering = true;
        String selectedView = String.valueOf(view.getSelectedItem());
        Choice selectedPeriod = (Choice) period.getSelectedItem();
        String requestPeriod = stockPanel ? chartConfig.range() : selectedPeriod.code();
        String selectedStock = stockPanel ? stock.getText().trim().toUpperCase(Locale.ROOT) : "";
        if (stockPanel && !selectedStock.matches("[A-Z][A-Z0-9.-]{0,9}")) {
            rendering = false;
            showError("Enter a valid stock ticker to load a chart.");
            return;
        }
        AlpacaSettings requestSettings = settings;
        String requestMode = accountMode;

        new SwingWorker<RenderResult, Void>() {
            @Override protected RenderResult doInBackground() throws Exception {
                Path imageFile = Files.createTempFile("talg-portfolio-", ".png");
                Path dataFile = null;
                try {
                    dataFile = Files.createTempFile("talg-observed-", ".rds");
                    ProcessBuilder fetch = RRuntime.builder(projectRoot(), "r/fetch_display_data.R",
                            dataFile.toString(), selectedView, requestPeriod, selectedStock,
                            stockPanel ? chartConfig.bars() : "1Day",
                            stockPanel ? chartConfig.comparisons() : "");
                    fetch.directory(projectRoot().toFile());
                    AlpacaProcessEnvironment.supply(fetch, requestSettings, requestMode);
                    runR(fetch, "data fetch", requestSettings);

                    ProcessBuilder display = RRuntime.builder(projectRoot(), "r/java_display.R",
                            imageFile.toString(), selectedView, requestPeriod,
                            selectedStock, lowerIndicator, overlays,
                            stockPanel ? chartConfig.bars() : "1Day",
                            stockPanel ? chartConfig.parameterArgument() : "",
                            stockPanel ? chartConfig.comparisons() : "",
                            stockPanel ? chartConfig.layout() : "overlay",
                            stockPanel ? chartConfig.customOverlays() : "",
                            stockPanel ? chartConfig.customStudy() : "",
                            stockPanel ? chartConfig.plot() : "close",
                            dataFile.toString(),
                            Integer.toString(requestedWidth), Integer.toString(requestedHeight),
                            stockPanel ? chartConfig.display() : "line",
                            stockPanel && chartConfig.watermark() ? "on" : "off",
                            Integer.toString(displayWidth), Integer.toString(displayHeight));
                    display.directory(projectRoot().toFile());
                    AlpacaProcessEnvironment.scrub(display);
                    List<String> output = runR(display, "render", requestSettings);
                    BufferedImage image = ImageIO.read(imageFile.toFile());
                    if (image == null) throw new IOException("R did not return a readable chart.");
                    String positionsJson = output.stream()
                            .filter(line -> line.startsWith("TALG_POSITIONS_JSON:"))
                            .findFirst().map(line -> line.substring("TALG_POSITIONS_JSON:".length()))
                            .orElse("[]");
                    String chartMeta = output.stream()
                            .filter(line -> line.startsWith("TALG_CHART_META_JSON:"))
                            .findFirst().map(line -> line.substring("TALG_CHART_META_JSON:".length()))
                            .orElse("{}");
                    return new RenderResult(image, positionsJson, chartMeta);
                } finally {
                    Files.deleteIfExists(imageFile);
                    if (dataFile != null) Files.deleteIfExists(dataFile);
                }
            }

            @Override protected void done() {
                rendering = false;
                if (closed) return;
                if (settings != requestSettings) {
                    if (settings != null) refreshDashboard();
                    return;
                }
                try {
                    RenderResult result = get();
                    canvas.setImage(result.image());
                    populatePositions(result.positionsJson());
                    if (stockPanel) {
                        JsonNode meta = new ObjectMapper().readTree(result.chartMeta());
                        canvas.setToolTipText("Alpaca " + meta.path("feed").asText("market")
                                + " · " + meta.path("count").asInt(0) + " observed "
                                + chartConfig.bars() + " bars · last " + meta.path("last").asText("unavailable"));
                    }
                    contentCards.show(content, "Holdings".equals(selectedView) ? "positions" : "chart");
                    Dimension actualSize = renderDimensions(canvas.getWidth(), canvas.getHeight());
                    if (Math.abs(actualSize.width - requestedWidth) > 40
                            || Math.abs(actualSize.height - requestedHeight) > 40) resizeTimer.restart();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    showError("R rendering was interrupted.");
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    showError(cause == null ? "R display failed." : cause.getMessage());
                } catch (IOException ex) {
                    showError(ex.getMessage());
                }
                if (resizePending) {
                    resizePending = false;
                    resizeTimer.restart();
                }
            }
        }.execute();
    }

    private void showError(String message) {
        canvas.setMessage(stockPanel ? "Stock history unavailable" : "Portfolio unavailable",
                message == null ? "Alpaca data could not be loaded." : message);
        contentCards.show(content, "chart");
        positionsModel.setRowCount(0);
    }

    /** Keep R's bitmap at the canvas aspect ratio, including unusually narrow panels. */
    static Dimension renderDimensions(int width, int height) {
        if (width < 1 || height < 1) return new Dimension(900, 550);
        double scale = Math.max(2.0, Math.max(600.0 / width, 350.0 / height));
        scale = Math.min(scale, Math.min(4000.0 / width, 3000.0 / height));
        return new Dimension(Math.min(4000, Math.max(600, (int) Math.round(width * scale))),
                Math.min(3000, Math.max(350, (int) Math.round(height * scale))));
    }

    private static List<String> runR(ProcessBuilder builder, String stage, AlpacaSettings credentials)
            throws IOException, InterruptedException {
        builder.redirectErrorStream(true);
        Process process = builder.start();
        List<String> output = java.util.Collections.synchronizedList(new ArrayList<>());
        Thread reader = Thread.startVirtualThread(() -> {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    if (output.size() < 200) output.add(line);
                }
            } catch (IOException ignored) { /* The process exit code reports failure. */ }
        });
        if (!process.waitFor(180, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("R " + stage + " exceeded 180 seconds.");
        }
        reader.join(2000);
        int exit = process.exitValue();
        if (exit != 0) {
            String detail = output.stream().filter(line -> line.startsWith("TALG_ERROR:"))
                    .findFirst().map(line -> line.substring("TALG_ERROR:".length()))
                    .orElse("R returned exit code " + exit);
            detail = detail.replace(credentials.apiKey(), "<redacted>")
                    .replace(credentials.apiSecret(), "<redacted>");
            throw new IOException("R " + stage + " failed: " + detail);
        }
        return output;
    }

    private static Path projectRoot() { return Path.of("").toAbsolutePath().normalize(); }

    private void populatePositions(String json) throws IOException {
        JsonNode rows = new ObjectMapper().readTree(json);
        if (!rows.isArray()) throw new IOException("Alpaca positions response was not a list.");
        positionsModel.setRowCount(0);
        for (JsonNode row : rows) {
            positionsModel.addRow(new Object[]{
                    row.path("symbol").asText(), row.path("side").asText(),
                    numericCell(row.path("qty")), numericCell(row.path("price")),
                    numericCell(row.path("value")), numericCell(row.path("intraday")),
                    numericCell(row.path("unrealized"))
            });
        }
    }

    private static Double numericCell(JsonNode value) {
        return value.isNumber() ? value.asDouble() : null;
    }


    private static JLabel label(String value, int size, Color color, int weight) {
        JLabel label = new JLabel(value);
        label.setForeground(color);
        label.setFont(new Font("Segoe UI", weight, size));
        return label;
    }

    private record Choice(String label, String code) {
        @Override public String toString() { return label; }
    }

    private static void selectChoice(JComboBox<Choice> box, String code) {
        for (int index = 0; index < box.getItemCount(); index++) {
            if (box.getItemAt(index).code().equals(code)) {
                box.setSelectedIndex(index);
                return;
            }
        }
        throw new IllegalArgumentException("Unknown panel setting: " + code);
    }

    private record RenderResult(BufferedImage image, String positionsJson, String chartMeta) {}

    private static final class ImageCanvas extends JComponent {
        private BufferedImage image;
        private String headline = "No account data loaded";
        private String detail = "Connect Alpaca in Settings to see real positions and account history.";

        private ImageCanvas() {
            setOpaque(true);
            setBackground(BG);
            setPreferredSize(new Dimension(980, 550));
        }

        private void setImage(BufferedImage value) { image = value; repaint(); }

        private boolean hasImage() { return image != null; }

        private void setMessage(String headline, String detail) {
            image = null;
            this.headline = headline;
            this.detail = detail;
            setToolTipText(detail);
            repaint();
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            if (image != null) {
                double scale = Math.min((double) getWidth() / image.getWidth(),
                        (double) getHeight() / image.getHeight());
                int width = (int) Math.round(image.getWidth() * scale);
                int height = (int) Math.round(image.getHeight() * scale);
                g.drawImage(image, (getWidth() - width) / 2, (getHeight() - height) / 2,
                        width, height, null);
            } else {
                g.setColor(PURPLE);
                g.setFont(new Font("Segoe UI", Font.BOLD, 24));
                g.drawString(headline, 38, Math.max(70, getHeight() / 2 - 8));
                g.setColor(MUTED);
                g.setFont(new Font("Segoe UI", Font.PLAIN, 13));
                int y = Math.max(95, getHeight() / 2 + 24);
                int maxWidth = Math.max(80, getWidth() - 70);
                StringBuilder line = new StringBuilder();
                int lines = 0;
                for (String word : detail.split("\\s+")) {
                    String next = line.isEmpty() ? word : line + " " + word;
                    if (!line.isEmpty() && g.getFontMetrics().stringWidth(next) > maxWidth) {
                        g.drawString(line.toString(), 38, y + lines * 19);
                        lines++;
                        line.setLength(0);
                    }
                    if (lines >= 3) break;
                    if (!line.isEmpty()) line.append(' ');
                    line.append(word);
                }
                if (lines < 3 && !line.isEmpty()) g.drawString(line.toString(), 38, y + lines * 19);
            }
            g.dispose();
        }
    }
}

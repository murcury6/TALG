package io.github.murcury6.talg;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridLayout;
import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;

/** An independent read-only Alpaca quote, with its own symbol and fetch state. */
final class MarketQuotePanel extends JPanel {
    private static final Color BG = new Color(20, 18, 27);
    private static final Color CARD = new Color(34, 30, 45);
    private static final Color BORDER = new Color(70, 62, 87);
    private static final Color TEXT = new Color(232, 229, 239);
    private static final Color MUTED = new Color(153, 149, 164);
    private static final Color PURPLE = new Color(177, 146, 245);
    private static final Color GREEN = new Color(75, 205, 143);
    private static final Color RED = new Color(240, 106, 121);
    private static final DateTimeFormatter TIME = DateTimeFormatter
            .ofPattern("MMM d, h:mm a z", Locale.US).withZone(ZoneId.systemDefault());

    private final Supplier<AlpacaSettings> settings;
    private final AlpacaMarketDataClient client = new AlpacaMarketDataClient();
    private final String ticker;
    private final JLabel price = label("—", 30, TEXT, Font.BOLD);
    private final JLabel change = label("No price loaded", 13, MUTED, Font.PLAIN);
    private final JLabel feed = label("NO FEED", 10, MUTED, Font.BOLD);
    private final JLabel asOf = label("—", 11, MUTED, Font.PLAIN);
    private final JLabel open = label("—", 16, TEXT, Font.BOLD);
    private final JLabel high = label("—", 16, TEXT, Font.BOLD);
    private final JLabel low = label("—", 16, TEXT, Font.BOLD);
    private final JLabel volume = label("—", 16, TEXT, Font.BOLD);
    private final JLabel message = label("", 11, MUTED, Font.PLAIN);
    private boolean loading;

    MarketQuotePanel(Supplier<AlpacaSettings> settings, String initialTicker) {
        super(new BorderLayout(0, 3));
        this.settings = settings;
        this.ticker = initialTicker == null ? "" : initialTicker.trim().toUpperCase(Locale.ROOT);
        setBackground(BG);
        setBorder(new EmptyBorder(4, 6, 4, 6));

        JPanel center = new JPanel(new BorderLayout(0, 4));
        center.setBackground(BG);
        center.setBorder(new EmptyBorder(4, 6, 4, 6));
        center.add(feed, BorderLayout.NORTH);
        JPanel priceStack = new JPanel();
        priceStack.setOpaque(false);
        priceStack.setLayout(new BoxLayout(priceStack, BoxLayout.Y_AXIS));
        priceStack.add(Box.createVerticalGlue());
        priceStack.add(price);
        priceStack.add(change);
        priceStack.add(Box.createVerticalGlue());
        center.add(priceStack, BorderLayout.CENTER);
        center.add(asOf, BorderLayout.SOUTH);
        add(center, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(0, 5));
        bottom.setOpaque(false);
        JPanel metrics = new JPanel(new GridLayout(1, 4, 6, 0));
        metrics.setOpaque(false);
        metrics.add(metric("OPEN", open));
        metrics.add(metric("HIGH", high));
        metrics.add(metric("LOW", low));
        metrics.add(metric("VOLUME", volume));
        bottom.add(metrics, BorderLayout.CENTER);
        bottom.add(message, BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);
        message.setText(settings.get() == null ? "Connect Alpaca in Settings to load this quote." : "Loading quote…");
        if (settings.get() != null) javax.swing.SwingUtilities.invokeLater(this::refresh);
    }

    String currentTicker() { return ticker; }

    void refresh() {
        AlpacaSettings requestSettings = settings.get();
        String symbol = ticker;
        if (!symbol.matches("[A-Z][A-Z0-9.-]{0,9}")) {
            message.setText("Enter a valid ticker, such as AAPL.");
            return;
        }
        if (requestSettings == null) {
            message.setText("Connect Alpaca in Settings to load this quote.");
            return;
        }
        if (loading) return;
        loading = true;
        message.setText("Loading " + symbol + " from Alpaca…");
        new SwingWorker<MarketSnapshot, Void>() {
            @Override protected MarketSnapshot doInBackground() throws Exception {
                return client.fetch(symbol, requestSettings);
            }

            @Override protected void done() {
                loading = false;
                if (settings.get() != requestSettings) {
                    if (settings.get() != null) refresh();
                    return;
                }
                try {
                    MarketSnapshot snapshot = get();
                    show(snapshot);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    showError("Request interrupted");
                } catch (ExecutionException error) {
                    Throwable cause = error.getCause();
                    showError(cause == null ? "Unknown Alpaca error" : cause.getMessage());
                }
            }
        }.execute();
    }

    void disconnect() {
        price.setText("—");
        change.setText("No price loaded");
        change.setForeground(MUTED);
        feed.setText("NO FEED");
        asOf.setText("—");
        open.setText("—");
        high.setText("—");
        low.setText("—");
        volume.setText("—");
        message.setText("Connect Alpaca in Settings to load this quote.");
    }

    private void show(MarketSnapshot snapshot) {
        price.setText(money(snapshot.price()));
        double percent = snapshot.changePercent();
        change.setText(Double.isFinite(percent)
                ? String.format(Locale.US, "%+.2f%% vs previous close", percent)
                : "Previous close unavailable");
        change.setForeground(!Double.isFinite(percent) || percent == 0 ? MUTED : percent > 0 ? GREEN : RED);
        feed.setText(snapshot.feed().toUpperCase(Locale.ROOT) + "  •  " + snapshot.priceKind());
        asOf.setText(TIME.format(snapshot.priceTime()));
        open.setText(moneyOrDash(snapshot.dayOpen()));
        high.setText(moneyOrDash(snapshot.dayHigh()));
        low.setText(moneyOrDash(snapshot.dayLow()));
        volume.setText(snapshot.dayVolume() < 0 ? "—"
                : NumberFormat.getIntegerInstance(Locale.US).format(snapshot.dayVolume()));
        message.setText("");
    }

    private void showError(String detail) {
        price.setText("—");
        change.setText("Market data unavailable");
        change.setForeground(MUTED);
        feed.setText("NO FEED");
        asOf.setText("—");
        open.setText("—");
        high.setText("—");
        low.setText("—");
        volume.setText("—");
        message.setText(detail == null ? "Alpaca did not return a quote." : detail);
    }

    private static JPanel metric(String title, JLabel value) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(BG);
        panel.setBorder(new EmptyBorder(3, 3, 3, 3));
        panel.add(label(title, 9, MUTED, Font.BOLD), BorderLayout.NORTH);
        panel.add(value, BorderLayout.SOUTH);
        return panel;
    }

    private static JLabel label(String text, int size, Color color, int weight) {
        JLabel label = new JLabel(text);
        label.setForeground(color);
        label.setFont(new Font("Segoe UI", weight, size));
        return label;
    }

    private static String money(double value) {
        return String.format(Locale.US, "$%,.2f", value);
    }

    private static String moneyOrDash(double value) {
        return Double.isFinite(value) && value > 0 ? money(value) : "—";
    }
}

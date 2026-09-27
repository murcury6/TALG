package io.github.murcury6.talg;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.BorderLayout;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TradeModelsPanelTest {
    @TempDir Path root;
    private static final String POSITIONS = """
            [{"symbol":"AAPL","side":"long","qty":"1.5","avg_entry_price":"100","current_price":"110",
            "market_value":"165","unrealized_pl":"15","unrealized_plpc":"0.1"}]
            """;

    @Test void positionsAndIndependentModelWorkspacesRetainDrafts() throws Exception {
        Files.createDirectories(root.resolve("work/models"));
        Files.writeString(root.resolve("work/models/news-tensor.json"), ModelFilesTest.NEWS);
        var client = new AlpacaOrderClient(request -> new AlpacaOrderClient.Response(200,
                request.uri().getPath().endsWith("positions") ? POSITIONS : "[]"), null);
        var loaded = new CountDownLatch(1);
        TradingPanel[] trade = new TradingPanel[1]; ModelsPanel[] models = new ModelsPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                trade[0] = new TradingPanel(() -> new AlpacaSettings("test", "test", "iex", 30), () -> "paper", () -> "AAPL", root, client);
                models[0] = new ModelsPanel(root, trade[0], true);
                JLabel feedback = field(trade[0], "feedback");
                feedback.addPropertyChangeListener("text", event -> { if (feedback.getText().startsWith("1 positions")) loaded.countDown(); });
                trade[0].activate();
            } catch (Exception error) { throw new RuntimeException(error); }
        });
        try {
            assertTrue(loaded.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                try {
                    JTable positions = field(trade[0], "positionTable");
                    assertEquals(1, positions.getRowCount()); assertEquals("AAPL", positions.getValueAt(0, 0));
                    assertFalse(((JButton) field(trade[0], "submit")).isEnabled());
                    assertFalse(((JButton) field(trade[0], "cancel")).isEnabled());
                    assertEquals("Positions", ((JComboBox<?>) field(trade[0], "view")).getSelectedItem());
                    assertEquals("Model", ((JComboBox<?>) field(models[0], "view")).getSelectedItem());
                    JTextArea editor = field(models[0], "newsCode"); editor.append("\n ");
                    String draft = editor.getText();
                    ((JComboBox<?>) field(models[0], "view")).setSelectedItem("Inputs");
                    ((JComboBox<?>) field(models[0], "view")).setSelectedItem("Model");
                    assertEquals(draft, editor.getText());
                    ((JComboBox<?>) field(models[0], "view")).setSelectedItem("Model");
                    assertTrue(((JButton) field(models[0], "save")).isEnabled());
                    for (JPanel panel : new JPanel[]{trade[0], models[0]}) {
                        panel.setSize(990, 620); layout(panel);
                        var border = (BorderLayout) panel.getLayout();
                        assertEquals(30, border.getLayoutComponent(BorderLayout.NORTH).getHeight());
                        assertNull(border.getLayoutComponent(BorderLayout.SOUTH));
                        assertTrue(border.getLayoutComponent(BorderLayout.CENTER).getHeight() > 560);
                    }
                    render(trade[0], "trade-positions.png");
                    ((JComboBox<?>) field(models[0], "view")).setSelectedItem("Model");
                    layout(models[0]); render(models[0], "models-news.png");
                    ModelsPanel tradingModel = new ModelsPanel(root, trade[0]);
                    assertEquals(false, (Boolean) field(tradingModel, "news"));
                    assertEquals(true, (Boolean) field(models[0], "news"));
                    assertEquals("Tickers", ((JToggleButton) field(tradingModel, "dataView")).getText());
                    tradingModel.setSize(990,620); layout(tradingModel); render(tradingModel, "models-trade.png"); tradingModel.close();
                } catch (Exception error) { throw new RuntimeException(error); }
            });
        } finally { SwingUtilities.invokeAndWait(() -> { trade[0].closePaperTest(); models[0].close(); }); }
    }

    @SuppressWarnings("unchecked") private static <T> T field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return (T) field.get(object);
    }
    private static void layout(Container container) {
        container.doLayout(); for (var child : container.getComponents()) if (child instanceof Container sub) layout(sub);
    }
    private static void render(JPanel panel, String filename) throws Exception {
        Path folder = Path.of("target/ui-tests"); Files.createDirectories(folder);
        var image = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); panel.printAll(graphics); graphics.dispose();
        ImageIO.write(image, "png", folder.resolve(filename).toFile());
    }
}

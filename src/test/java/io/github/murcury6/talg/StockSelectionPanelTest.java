package io.github.murcury6.talg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.swing.*;
import static org.junit.jupiter.api.Assertions.*;

class StockSelectionPanelTest {
    @TempDir Path root;
    @Test void candidatesRoundTripWithoutChangingCustomLogic() throws Exception {
        String source = "param candidates = ['AAPL']\nparam threshold = 3\noutput include = threshold > 2\n";
        String changed = StockSelectionPanel.withCandidates(source, List.of("MSFT", "NVDA"));
        assertTrue(changed.contains("param candidates = [\"MSFT\",\"NVDA\"]"));
        assertTrue(changed.endsWith("param threshold = 3\noutput include = threshold > 2\n"));
        assertThrows(IllegalArgumentException.class, () -> StockSelectionPanel.withCandidates(source,List.of()));
        SwingUtilities.invokeAndWait(() -> {
            var picker = new ModelStocksPanel(List.of("MSFT"),List.of("AAPL","MSFT"));
            picker.addStock("nvda"); assertEquals(List.of("MSFT","NVDA"),picker.selected());
            picker.addStock("NVDA"); assertEquals(2,picker.selected().size());
            assertThrows(IllegalArgumentException.class, () -> picker.addStock("BAD TICKER"));
        });
    }
    @Test void allAvailableIsADynamicCodeDeclarationNotAnExpandedList() throws Exception {
        String source = "param candidates = ['AAPL']\nparam max_stocks = 20\noutput include = True\n";
        String all = StockSelectionPanel.withAllAvailable(source);
        assertTrue(all.startsWith("param candidates = \"all_available\"\n"));
        assertTrue(all.endsWith("param max_stocks = 20\noutput include = True\n"));
        assertTrue(StockSelectionPanel.withCandidates(all,List.of("BRK.B")).contains("[\"BRK.B\"]"));
        SwingUtilities.invokeAndWait(() -> {
            var picker = new ModelStocksPanel(List.of(),List.of("AAPL","BRK.B"),List.of("AAPL"),true);
            assertTrue(picker.usesAllAvailable());
            picker.selectShown(true); assertFalse(picker.usesAllAvailable());
            assertEquals(List.of("AAPL","BRK.B"),picker.selected());
        });
    }
    @Test void standaloneWorkspaceUsesOneToolbarAndCannotApplyBeforeEvaluation() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                var panel = new StockSelectionPanel(root, symbols -> fail("Unevaluated selection must not apply"), new JPanel());
                assertThrows(IllegalArgumentException.class, panel::selected);
                panel.setSize(1100,650); layout(panel);
                var border = (java.awt.BorderLayout) panel.getLayout();
                assertEquals(30,border.getLayoutComponent(java.awt.BorderLayout.NORTH).getHeight());
                assertNull(border.getLayoutComponent(java.awt.BorderLayout.SOUTH));
                var image = new java.awt.image.BufferedImage(1100,650,java.awt.image.BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics(); panel.printAll(graphics); graphics.dispose();
                Files.createDirectories(Path.of("target/ui-tests"));
                javax.imageio.ImageIO.write(image,"png",Path.of("target/ui-tests/stock-selection.png").toFile());
                panel.close();
            } catch (Exception error) { throw new RuntimeException(error); }
        });
    }
    @Test void selectionHandoffChangesOnlyTradingDraftAndPreservesCustomCode() throws Exception {
        Path config = root.resolve("work/strategies/rapid-paper.json"); Files.createDirectories(config.getParent());
        String saved = PaperTestRunner.JSON.writeValueAsString(RapidPaperModel.defaults()); Files.writeString(config,saved);
        SwingUtilities.invokeAndWait(() -> {
            try {
                var model = new ModelsPanel(root,null);
                var codeField = ModelsPanel.class.getDeclaredField("tradeCode"); codeField.setAccessible(true);
                var editor = (JTextArea) codeField.get(model); editor.append("\n# preserve my custom source\n");
                String custom = editor.getText();
                model.applyStocks(List.of("MSFT","NVDA"));
                assertEquals(custom,editor.getText());
                var configField = ModelsPanel.class.getDeclaredField("tradeEditor"); configField.setAccessible(true);
                var draft = PaperTestRunner.JSON.readTree(((JTextArea)configField.get(model)).getText());
                assertEquals("[\"MSFT\",\"NVDA\"]",draft.path("universe").toString());
                assertEquals(custom,draft.path("signal").path("script").asText());
                assertEquals(PaperTestRunner.JSON.readTree(saved).path("sizing"),draft.path("sizing"));
                assertEquals(saved,Files.readString(config));
                var saveField = ModelsPanel.class.getDeclaredField("save"); saveField.setAccessible(true);
                assertTrue(((JButton)saveField.get(model)).isEnabled()); model.close();
            } catch (Exception error) { throw new RuntimeException(error); }
        });
    }
    private static void layout(java.awt.Container parent) { parent.doLayout(); for (var child : parent.getComponents()) if (child instanceof java.awt.Container container) layout(container); }
}

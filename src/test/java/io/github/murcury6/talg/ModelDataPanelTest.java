package io.github.murcury6.talg;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class ModelDataPanelTest {
    @Test void modelDefinesDataWhileAppOwnsSizingFormattingAndSorting() throws Exception {
        var payload = PaperTestRunner.JSON.readTree("""
            {"workbook":{"sheets":[{"name":"Scenarios","rows":"/outputs/scenarios","details":true,"rowHeight":100,
              "columns":[{"id":"weight","label":"Chance","path":"/weight","type":"number","format":"percent","precision":1,"width":95},
                         {"id":"headline","source":"record","path":"/headline"}],
              "sort":{"column":"weight","direction":"desc"}},
              {"name":"Raw","columns":[{"id":"data","path":"/outputs","type":"json"}]}]},
             "rows":[{"headline":"Example","outputs":{"scenarios":[{"weight":0.1},{"weight":0.9}]}}]}
            """);
        SwingUtilities.invokeAndWait(() -> {
            try {
                var panel = new ModelDataPanel(); panel.display(payload);
                var field = ModelDataPanel.class.getDeclaredField("table"); field.setAccessible(true);
                JTable table = (JTable) field.get(panel);
                assertEquals(2, table.getRowCount()); assertEquals(0.1, table.getValueAt(0,0));
                assertEquals("Example", table.getValueAt(0,1)); assertEquals("Weight", table.getColumnName(0));
                var cell = (javax.swing.JLabel) table.prepareRenderer(table.getCellRenderer(0,0),0,0);
                assertEquals("0.1", cell.getText());
                assertEquals(26,table.getRowHeight()); assertFalse(panel.inspectControl().isSelected());
                ModelTablePresentation.fit(table,900);
                int narrow = table.getColumnModel().getColumn(1).getWidth();
                assertEquals(900,table.getColumnModel().getTotalColumnWidth());
                assertNotEquals(95,table.getColumnModel().getColumn(0).getWidth());
                ModelTablePresentation.fit(table,1200);
                assertEquals(1200,table.getColumnModel().getTotalColumnWidth());
                assertTrue(table.getColumnModel().getColumn(1).getWidth() > narrow);
                table.getRowSorter().toggleSortOrder(0); table.getRowSorter().toggleSortOrder(0);
                assertEquals(0.9,table.getValueAt(0,0));
                panel.sheetSelector().setSelectedItem("Raw");
                assertEquals(1,table.getRowCount()); assertEquals(1,table.getColumnCount());
            } catch (Exception error) { throw new RuntimeException(error); }
        });
    }
    @Test void rendersArbitraryModelColumnsAndRetainedStructuredValues() throws Exception {
        var payload = PaperTestRunner.JSON.readTree("""
                {"columns":["identity","output.custom_label","output.custom_vector","output.custom_record"],
                "rows":[{"identity":"test","output.custom_label":"category","output.custom_vector":[1,2],
                "output.custom_record":{"evidence":"source"},"retained":{"arbitrary":[true,3]}}]}
                """);
        SwingUtilities.invokeAndWait(() -> {
            try {
                ModelDataPanel panel = new ModelDataPanel(); panel.display(payload);
                var field = ModelDataPanel.class.getDeclaredField("table"); field.setAccessible(true);
                JTable table = (JTable) field.get(panel);
                assertEquals(4, table.getColumnCount()); assertEquals("Custom vector", table.getColumnName(2));
                assertEquals("[1,2]", table.getValueAt(0,2));
                assertEquals("category", table.getValueAt(0,1));
                // Optional visual QA reads real local outputs; ordinary checks use only the fixture above.
                if (Boolean.getBoolean("talg.renderModels")) for (String name : new String[]{"news", "ticker"}) {
                    Path source = Path.of("work/news-tensor/ui-" + name + "-data.json");
                    if (!Files.exists(source)) continue;
                    panel.display(PaperTestRunner.JSON.readTree(source.toFile())); panel.setSize(1320,740); layout(panel);
                    ModelTablePresentation.fit(table,table.getParent().getWidth()); layout(panel);
                    var image = new BufferedImage(1320,740,BufferedImage.TYPE_INT_RGB);
                    var graphics = image.createGraphics(); panel.printAll(graphics); graphics.dispose();
                    Files.createDirectories(Path.of("target/ui-tests"));
                    ImageIO.write(image,"png",Path.of("target/ui-tests/model-" + name + "-data.png").toFile());
                }
            } catch (Exception error) { throw new RuntimeException(error); }
        });
    }
    private static void layout(Container container) { container.doLayout(); for (var child : container.getComponents()) if (child instanceof Container sub) layout(sub); }
}

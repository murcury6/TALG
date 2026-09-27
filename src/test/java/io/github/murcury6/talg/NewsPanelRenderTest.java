package io.github.murcury6.talg;

import java.awt.Container;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Optional offscreen visual check uses actual collected headlines, never synthetic app data. */
@EnabledIfSystemProperty(named = "talg.renderNews", matches = "true")
class NewsPanelRenderTest {
    @Test void renderCollectedNewsAndCoverageAtMinimumAndNormalSizes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                var panel = new NewsPanel(Path.of(""));
                var snapshot = NewsPanel.class.getDeclaredField("snapshot"); snapshot.setAccessible(true);
                snapshot.set(panel, new NewsService(Path.of("")).load());
                var rebuild = NewsPanel.class.getDeclaredMethod("rebuild"); rebuild.setAccessible(true); rebuild.invoke(panel);
                Files.createDirectories(Path.of("work/news"));
                for (int width : new int[]{1320, 990}) {
                    panel.setSize(width, width == 990 ? 620 : 790); layout(panel);
                    var resize = NewsPanel.class.getDeclaredMethod("updateRowHeights"); resize.setAccessible(true); resize.invoke(panel);
                    JTable table = (JTable) field(panel, "table");
                    if (table.getRowCount() > 0) table.setRowSelectionInterval(0, 0);
                    render(panel, "work/news/headlines-" + width + ".png");
                }
                panel.stop();
            } catch (Exception error) { throw new RuntimeException(error); }
        });
    }
    private static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static void layout(Container container) { container.doLayout(); for (var child : container.getComponents()) if (child instanceof Container sub) layout(sub); }
    private static void render(NewsPanel panel, String path) throws Exception {
        BufferedImage image = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); panel.printAll(graphics); graphics.dispose(); ImageIO.write(image, "png", Path.of(path).toFile());
    }
}

package io.github.murcury6.talg;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.awt.Container;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class IndicatorStudioPanelTest {
    @Test void editorLaysOutAtTheDesktopMinimum() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            IndicatorStudioPanel panel = new IndicatorStudioPanel(() -> null, () -> "paper",
                    "AAPL", selection -> {}, selection -> {});
            panel.setSize(1000, 620);
            layoutTree(panel);
            BufferedImage image = new BufferedImage(1000, 620, BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            panel.printAll(graphics);
            graphics.dispose();
            assertFalse(panel.getComponents().length == 0);
            if (Boolean.getBoolean("talg.captureIndicatorUi")) try {
                ImageIO.write(image, "png", Path.of("target/indicator-ui-qa.png").toFile());
            } catch (Exception error) { throw new RuntimeException(error); }
        });
    }

    private static void layoutTree(Container container) {
        container.doLayout();
        for (var child : container.getComponents()) if (child instanceof Container nested)
            layoutTree(nested);
    }
}

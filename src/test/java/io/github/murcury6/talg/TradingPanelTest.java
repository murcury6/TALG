package io.github.murcury6.talg;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class TradingPanelTest {
    @Test void openingTradeTabDoesNotArmOrSubmitAnOrder() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TradingPanel panel = new TradingPanel(() -> null, () -> "live", () -> "AAPL");
            assertFalse(button(panel, "SUBMIT PREVIEWED ORDER").isEnabled());
            assertTrue(label(panel, "DISCONNECTED (LIVE)") != null);
        });
    }

    private static JButton button(Container root, String title) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton value && title.equals(value.getText())) return value;
            if (child instanceof Container container) {
                try { return button(container, title); }
                catch (IllegalArgumentException ignored) { /* Try next branch. */ }
            }
        }
        throw new IllegalArgumentException("Button missing: " + title);
    }

    private static JLabel label(Container root, String prefix) {
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel value && value.getText().startsWith(prefix)) return value;
            if (child instanceof Container container) {
                try { return label(container, prefix); }
                catch (IllegalArgumentException ignored) { /* Try next branch. */ }
            }
        }
        throw new IllegalArgumentException("Label missing: " + prefix);
    }
}

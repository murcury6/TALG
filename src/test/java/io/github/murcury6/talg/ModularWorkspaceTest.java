package io.github.murcury6.talg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Component;
import java.awt.Container;
import java.util.List;

import javax.swing.JPanel;
import javax.swing.JLayeredPane;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class ModularWorkspaceTest {
    @Test void startsEmptyAndAddsFreePositionedPanels() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ModularWorkspace workspace = new ModularWorkspace(() ->
                    new ConfigurablePanel(spec -> new JPanel(), () -> null, panel -> {}));
            assertEquals(0, workspace.panelCount());
            assertEquals(1, workspace.getComponentCount());
            assertInstanceOf(JScrollPane.class, workspace.getComponent(0));
            workspace.addPanel();
            assertEquals(1, workspace.panelCount());
            workspace.addPanel();
            assertEquals(2, workspace.panelCount());
            workspace.addPanel();
            assertEquals(3, workspace.panelCount());
        });
    }

    @Test void restoresPanelPositionsSizesAndActiveConfiguration() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ModularWorkspace workspace = new ModularWorkspace(() ->
                    new ConfigurablePanel(spec -> new JPanel(), () -> "AAPL", panel -> {}));
            ConfigurablePanel chart = workspace.addPanel();
            chart.openStockChart("AAPL");
            ModularWorkspace.PanelState saved = workspace.snapshot().getFirst();
            Rectangle desired = new Rectangle(875, 612, 840, 460);
            workspace.restore(List.of(new ModularWorkspace.PanelState(desired, saved.panel())),
                    new Point(810, 440));
            assertEquals(1, workspace.panelCount());
            assertEquals(desired, workspace.snapshot().getFirst().bounds());
            assertTrue(workspace.snapshot().getFirst().panel().active() != null);
        });
    }

    @Test void titleStripAndOverlayGripLeaveNearlyEntirePanelForContent() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ModularWorkspace workspace = new ModularWorkspace(() ->
                    new ConfigurablePanel(spec -> new JPanel(), () -> "AAPL", panel -> {}));
            workspace.addPanel();
            JScrollPane scroll = (JScrollPane) workspace.getComponent(0);
            Container canvas = (Container) scroll.getViewport().getView();
            Container widget = (Container) canvas.getComponent(0);
            widget.setSize(700, 400);
            widget.doLayout();
            JLayeredPane body = null;
            for (Component child : widget.getComponents())
                if (child instanceof JLayeredPane layered) body = layered;
            assertTrue(body != null);
            body.doLayout();
            ConfigurablePanel panel = null;
            for (Component child : body.getComponents())
                if (child instanceof ConfigurablePanel configured) panel = configured;
            assertTrue(panel != null);
            assertEquals(body.getWidth(), panel.getWidth());
            assertEquals(body.getHeight(), panel.getHeight());
            assertTrue(body.getHeight() >= 380); // At least 95% of a 400px panel.
        });
    }
}

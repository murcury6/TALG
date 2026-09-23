package io.github.murcury6.talg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import org.junit.jupiter.api.Test;

class ConfigurablePanelTest {
    @Test void onlyRelevantFieldsAppearForEachPanelType() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ConfigurablePanel panel = new ConfigurablePanel(spec -> new JPanel(), () -> null, ignored -> {});
            button(panel, "MANUAL").doClick();
            JComboBox<?> type = typeSelector(panel);
            assertTrue(field(panel, "PORTFOLIO VIEW").isVisible());
            assertFalse(field(panel, "TICKER").isVisible());
            assertFalse(field(panel, "CHART SCRIPT").isVisible());
            type.setSelectedIndex(1);
            assertFalse(field(panel, "PORTFOLIO VIEW").isVisible());
            assertFalse(field(panel, "HISTORY").isVisible());
            assertTrue(field(panel, "CHART SCRIPT").isVisible());
            assertFalse(field(panel, "TICKER").isVisible());
            type.setSelectedIndex(2);
            assertTrue(field(panel, "TICKER").isVisible());
            assertFalse(field(panel, "HISTORY").isVisible());
            assertFalse(field(panel, "CHART SCRIPT").isVisible());
            type.setSelectedIndex(3);
            assertFalse(field(panel, "TICKER").isVisible());
            assertFalse(field(panel, "HISTORY").isVisible());
            type.setSelectedIndex(4);
            assertTrue(field(panel, "MODEL SIGNAL CSV (OPTIONAL)").isVisible());
            assertFalse(field(panel, "TICKER").isVisible());
            assertFalse(field(panel, "PORTFOLIO VIEW").isVisible());
            type.setSelectedIndex(5);
            assertFalse(field(panel, "MODEL SIGNAL CSV (OPTIONAL)").isVisible());
            assertFalse(field(panel, "CHART SCRIPT").isVisible());
        });
    }

    @Test void stockChartScriptProducesAnIndependentPanelSpecification() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicReference<ConfigurablePanel.Spec> applied = new AtomicReference<>();
            ConfigurablePanel panel = new ConfigurablePanel(spec -> {
                applied.set(spec);
                return new JPanel();
            }, () -> "AAPL", ignored -> {});
            button(panel, "MANUAL").doClick();
            typeSelector(panel).setSelectedIndex(1);
            script(panel).setText("ticker AAPL\nrange 5D\nbars 5Min\nrefresh 60s\n"
                    + "overlay SMA(7)\noverlay Bollinger(18, 2.5)\n"
                    + "compare MSFT\ncompare NVDA\nstudy MACD(8,21,5)\n");
            button(panel, "APPLY TO PANEL").doClick();
            assertEquals("Stock lab", applied.get().kind());
            assertEquals("macd", applied.get().indicator());
            assertEquals("sma,bollinger", applied.get().overlays());
            assertEquals("AAPL", applied.get().symbol());
            assertEquals("5Min", applied.get().chart().bars());
            assertEquals(60, applied.get().chart().refreshSeconds());
            assertEquals("MSFT,NVDA", applied.get().chart().comparisons());
            assertEquals(7, applied.get().chart().sma());
        });
    }

    @Test void asksConfigurationMethodBeforeBuildingAView() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicInteger created = new AtomicInteger();
            ConfigurablePanel panel = new ConfigurablePanel(spec -> {
                created.incrementAndGet();
                assertEquals("Overview", spec.kind());
                return new JPanel();
            }, () -> null, ignored -> {});
            assertFalse(panel.isConfigured());
            assertEquals(0, created.get());
            assertTrue(panel.getComponent(0).isVisible());
            assertFalse(panel.getComponent(1).isVisible());
            assertFalse(panel.getComponent(2).isVisible());
            button(panel, "AI-ASSISTED").doClick();
            assertTrue(panel.getComponent(2).isVisible());
            assertEquals(0, created.get());
            button(panel, "CONFIGURE MANUALLY").doClick();
            assertTrue(panel.getComponent(1).isVisible());
            button(panel, "CHANGE METHOD").doClick();
            button(panel, "MANUAL").doClick();
            button(panel, "APPLY TO PANEL").doClick();
            assertTrue(panel.isConfigured());
            assertEquals(1, created.get());
        });
    }

    @Test void profileKeepsAnUnfinishedDraftSeparateFromTheLiveChart() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ConfigurablePanel original = new ConfigurablePanel(spec -> new JPanel(), () -> "AAPL", ignored -> {});
            original.openStockChart("AAPL");
            original.showEditor();
            script(original).setText("ticker AAPL\n# work in progress\n");
            ConfigurablePanel.State state = original.snapshot();
            assertTrue(state.active().chartScript().contains("study RSI(14)"));
            assertTrue(state.draft().chartScript().contains("work in progress"));

            ConfigurablePanel restored = new ConfigurablePanel(spec -> new JPanel(), () -> "AAPL", ignored -> {});
            restored.restore(state);
            assertTrue(restored.isConfigured());
            assertTrue(restored.snapshot().draft().chartScript().contains("work in progress"));
            assertTrue(restored.snapshot().active().chartScript().contains("study RSI(14)"));
        });
    }

    @Test void quoteTickerCanBeChangedFromTheQuickDrawerAndSaved() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ConfigurablePanel panel = new ConfigurablePanel(spec -> {
                return new MarketQuotePanel(() -> null, spec.symbol());
            }, () -> "AAPL", ignored -> {});
            panel.openMarketQuote("AAPL");
            panel.toggleQuickVariables();
            Container content = (Container) panel.getComponent(3);
            textField(content, "AAPL").setText("MSFT");
            button(content, "APPLY").doClick();
            assertEquals("MSFT", panel.snapshot().active().symbol());
            assertEquals("MSFT", panel.snapshot().draft().symbol());
        });
    }

    @Test void incompleteQuoteTickerKeepsTheWorkingSymbolWithoutAnError() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ConfigurablePanel panel = new ConfigurablePanel(spec -> new JPanel(), () -> "AAPL", ignored -> {});
            panel.openMarketQuote("AAPL");
            panel.toggleQuickVariables();
            Container content = (Container) panel.getComponent(3);
            textField(content, "AAPL").setText("??");
            button(content, "APPLY").doClick();
            assertEquals("AAPL", panel.snapshot().active().symbol());
            assertTrue(textAreaContains(content, "Kept AAPL"));
        });
    }

    @Test void quickDrawerChangesChartWithoutShrinkingItsCanvas() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicReference<ConfigurablePanel.Spec> applied = new AtomicReference<>();
            AtomicReference<JPanel> live = new AtomicReference<>();
            ConfigurablePanel panel = new ConfigurablePanel(spec -> {
                applied.set(spec);
                JPanel view = new JPanel();
                live.set(view);
                return view;
            }, () -> "AAPL", ignored -> {});
            panel.openStockChart("AAPL");
            JLayeredPane content = (JLayeredPane) panel.getComponent(3);
            content.setSize(700, 430);
            content.doLayout();
            Rectangle fullSize = live.get().getBounds();
            panel.toggleQuickVariables();
            content.doLayout();
            assertEquals(fullSize, live.get().getBounds());
            assertEquals(700, live.get().getWidth());
            assertEquals(430, live.get().getHeight());
            textField(content, "AAPL").setText("NVDA");
            button(content, "APPLY").doClick();
            assertEquals("NVDA", applied.get().chart().ticker());
            assertEquals("NVDA", panel.snapshot().active().symbol());
            content.doLayout();
            assertEquals(fullSize, live.get().getBounds());
            panel.toggleQuickVariables();
            content.doLayout();
            assertEquals(fullSize, live.get().getBounds());
        });
    }

    @Test void quickChangesPreserveAnUnfinishedCodeDraft() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ConfigurablePanel panel = new ConfigurablePanel(spec -> new JPanel(), () -> "AAPL", ignored -> {});
            panel.openStockChart("AAPL");
            panel.showConfigurationEditor();
            script(panel).setText("ticker AAPL\n# unfinished draft\n");
            panel.toggleQuickVariables();
            Container content = (Container) panel.getComponent(3);
            textField(content, "AAPL").setText("MSFT");
            button(content, "APPLY").doClick();
            assertEquals("MSFT", ChartScript.parse(panel.snapshot().active().chartScript()).ticker());
            assertTrue(panel.snapshot().draft().chartScript().contains("unfinished draft"));
        });
    }

    @Test void compactQuickDrawerChangesDisplayStyleAndDoesNotUseAnAccentScrollbar() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ConfigurablePanel panel = new ConfigurablePanel(spec -> new JPanel(), () -> "AAPL", ignored -> {});
            panel.openStockChart("AAPL");
            JLayeredPane content = (JLayeredPane) panel.getComponent(3);
            content.setSize(700, 700);
            panel.toggleQuickVariables();
            content.doLayout();
            JScrollPane drawer = null;
            for (Component child : content.getComponents())
                if (child instanceof JScrollPane scroll) drawer = scroll;
            assertTrue(drawer != null);
            assertTrue(drawer.getHeight() < content.getHeight());
            assertEquals(6, drawer.getVerticalScrollBar().getPreferredSize().width);
            JComboBox<?> style = null;
            for (Component child : field(drawer, "DISPLAY").getComponents())
                if (child instanceof JComboBox<?> combo) style = combo;
            assertTrue(style != null);
            assertEquals(10, style.getItemCount());
            style.setSelectedItem("hollow_candles");
            button(content, "APPLY").doClick();
            assertEquals("hollow_candles", ChartScript.parse(panel.snapshot().active().chartScript()).display());
        });
    }

    @Test void quickVariablesResolveConflictsWithoutShowingAValidationError() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ConfigurablePanel panel = new ConfigurablePanel(spec -> new JPanel(), () -> "AAPL", ignored -> {});
            panel.openStockChart("AAPL");
            panel.toggleQuickVariables();
            Container content = (Container) panel.getComponent(3);
            combo(content, "WINDOW").setSelectedItem("1D");
            combo(content, "DISPLAY").setSelectedItem("candles");
            textField(content, "close").setText("volume");
            button(content, "APPLY").doClick();
            ChartScript.Config chart = ChartScript.parse(panel.snapshot().active().chartScript());
            assertEquals("1D", chart.range());
            assertEquals("1Hour", chart.bars());
            assertEquals("volume", chart.plot());
            assertEquals("columns", chart.display());
            assertTrue(textAreaContains(content, "Bar width changed"));
            assertFalse(textAreaContains(content, "Line "));
        });
    }

    @Test void modelingBoardFileCanBeChangedWithoutADataToolbar() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicReference<ConfigurablePanel.Spec> applied = new AtomicReference<>();
            ConfigurablePanel panel = new ConfigurablePanel(spec -> {
                applied.set(spec);
                return new JPanel();
            }, () -> null, ignored -> {});
            ConfigurablePanel.FormState form = new ConfigurablePanel.FormState(
                    "Modeling board", "Overview", "3M", "", "", "first.csv");
            panel.restore(new ConfigurablePanel.State(form, form));
            panel.toggleQuickVariables();
            Container content = (Container) panel.getComponent(3);
            textField(content, "first.csv").setText("second.csv");
            button(content, "APPLY").doClick();
            assertEquals("second.csv", applied.get().modelFile());
            assertEquals("second.csv", panel.snapshot().active().modelFile());
        });
    }

    private static JButton button(Container root, String label) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton candidate && candidate.getText().contains(label)) return candidate;
            if (child instanceof Container container) {
                try { return button(container, label); }
                catch (IllegalArgumentException ignored) { /* Search the next branch. */ }
            }
        }
        throw new IllegalArgumentException("Button not found: " + label);
    }

    private static JComboBox<?> typeSelector(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JComboBox<?> candidate && candidate.getItemCount() == 6
                    && "Portfolio".equals(String.valueOf(candidate.getItemAt(0)))) return candidate;
            if (child instanceof Container container) {
                try { return typeSelector(container); }
                catch (IllegalArgumentException ignored) { /* Search the next branch. */ }
            }
        }
        throw new IllegalArgumentException("Panel type selector not found");
    }

    private static Container field(Container root, String title) {
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel label && title.equals(label.getText())) {
                // The graph preset controls nest the chart label one level below its optional row.
                return "CHART SCRIPT".equals(title) ? label.getParent().getParent() : label.getParent();
            }
            if (child instanceof Container container) {
                try { return field(container, title); }
                catch (IllegalArgumentException ignored) { /* Search the next branch. */ }
            }
        }
        throw new IllegalArgumentException("Field not found: " + title);
    }

    private static JComboBox<?> combo(Container root, String title) {
        for (Component child : field(root, title).getComponents())
            if (child instanceof JComboBox<?> choice) return choice;
        throw new IllegalArgumentException("Combo box not found: " + title);
    }

    private static boolean textAreaContains(Container root, String fragment) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTextArea area && area.getText().contains(fragment)) return true;
            if (child instanceof Container container && textAreaContains(container, fragment)) return true;
        }
        return false;
    }

    private static JTextArea script(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTextArea candidate) return candidate;
            if (child instanceof Container container) {
                try { return script(container); }
                catch (IllegalArgumentException ignored) { /* Search the next branch. */ }
            }
        }
        throw new IllegalArgumentException("Chart script not found");
    }

    private static JTextField textField(Container root, String value) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTextField candidate && value.equals(candidate.getText())) return candidate;
            if (child instanceof Container container) {
                try { return textField(container, value); }
                catch (IllegalArgumentException ignored) { /* Search the next branch. */ }
            }
        }
        throw new IllegalArgumentException("Text field not found: " + value);
    }
}

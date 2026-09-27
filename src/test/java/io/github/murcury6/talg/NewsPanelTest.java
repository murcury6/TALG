package io.github.murcury6.talg;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.BorderLayout;
import java.awt.Container;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NewsPanelTest {
    @TempDir Path root;

    @Test void filteringAndIncomingArticlesKeepReadingStable() throws Exception {
        var service = new NewsService(root);
        service.saveSources(List.of(new NewsService.Source("test", "Example", "Example feed", "World", "https://example.org/feed", true)));
        SwingUtilities.invokeAndWait(() -> {
            NewsPanel panel = new NewsPanel(root);
            try {
                var first = article("first", "Energy supply update", "A publisher excerpt.", 200);
                var second = article("second", "Technology report", "Other text.", 100);
                setSnapshot(panel, List.of(first, second));
                JTable list = field(panel, "table");
                JTextPane reader = field(panel, "details");
                assertEquals(second, list.getValueAt(0, 0), "Newest first even if input was not sorted");
                assertTrue(reader.getText().startsWith(second.title()), "First article should already be open");
                list.setRowSelectionInterval(1, 1);
                setSnapshot(panel, List.of(article("new", "New arrival", "Latest", 0), second, first));
                assertEquals(first, list.getValueAt(list.getSelectedRow(), 0));
                assertTrue(reader.getText().startsWith(first.title()), "Refresh must not replace the article being read");
                assertFalse(reader.getText().contains("First collected:"));

                JTextField search = field(panel, "search");
                search.setText("technology"); invoke(panel, "filter");
                assertEquals(1, list.getRowCount());
                assertTrue(reader.getText().startsWith(second.title()));
                search.setText("no-matching-story"); invoke(panel, "filter");
                assertEquals(0, list.getRowCount());
                assertTrue(reader.getText().contains("No matching articles"));
                assertFalse(((JButton) field(panel, "open")).isEnabled());
                ((JButton) field(panel, "clear")).doClick();
                assertEquals(3, list.getRowCount());
                assertTrue(((JButton) field(panel, "open")).isEnabled());

                JComboBox<?> category = field(panel, "categories");
                category.setSelectedItem("Technology");
                assertEquals(0, list.getRowCount());
                assertEquals("Filters (1)", ((JButton) field(panel, "filterToggle")).getText());
                ((JButton) field(panel, "clear")).doClick();
                assertEquals("Filters", ((JButton) field(panel, "filterToggle")).getText());
            } catch (Exception error) { throw new RuntimeException(error); }
            finally { panel.stop(); }
        });
    }

    @Test void toolbarStaysOneRowAndWorkspaceFillsTheRest() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            NewsPanel panel = new NewsPanel(root);
            try {
                for (int width : new int[]{990, 1320}) {
                    panel.setSize(width, 620); layout(panel);
                    BorderLayout layout = (BorderLayout) panel.getLayout();
                    var toolbar = layout.getLayoutComponent(BorderLayout.NORTH);
                    var workspace = layout.getLayoutComponent(BorderLayout.CENTER);
                    assertEquals(30, toolbar.getHeight());
                    assertTrue(workspace.getHeight() > 570);
                    assertNull(layout.getLayoutComponent(BorderLayout.SOUTH));
                    JTextField search = field(panel, "search");
                    assertTrue(search.getWidth() > 130);
                    assertEquals("Search news", search.getAccessibleContext().getAccessibleName());
                    JButton open = field(panel, "open");
                    assertTrue(open.getWidth() >= open.getPreferredSize().width);
                }
            } catch (Exception error) { throw new RuntimeException(error); }
            finally { panel.stop(); }
        });
    }

    private static NewsService.Article article(String id, String title, String summary, int secondsAgo) {
        String time = Instant.now().minusSeconds(secondsAgo).toString();
        return new NewsService.Article("test", "Example", "World", title, "https://example.org/" + id,
                summary, time, time, time);
    }
    private static void setSnapshot(NewsPanel panel, List<NewsService.Article> articles) throws Exception {
        var field = NewsPanel.class.getDeclaredField("snapshot"); field.setAccessible(true);
        field.set(panel, new NewsService.Snapshot(articles, List.of())); invoke(panel, "rebuild");
    }
    @SuppressWarnings("unchecked") private static <T> T field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return (T) field.get(object);
    }
    private static void invoke(Object object, String name) throws Exception {
        var method = object.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(object);
    }
    private static void layout(Container container) {
        container.doLayout();
        for (var child : container.getComponents()) if (child instanceof Container sub) layout(sub);
    }
}

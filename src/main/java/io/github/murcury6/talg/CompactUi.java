package io.github.murcury6.talg;

import java.awt.Color;
import java.awt.Dimension;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;

/** Flat workspace controls shared by Trade and Models. */
final class CompactUi {
    private CompactUi() {}
    static void table(JTable table) {
        table.setShowVerticalLines(false); table.setGridColor(NewsPanel.BG);
        table.setSelectionBackground(new Color(59, 47, 80)); table.setSelectionForeground(NewsPanel.TEXT);
        table.getTableHeader().setDefaultRenderer(new DefaultTableCellRenderer() {
            @Override public java.awt.Component getTableCellRendererComponent(JTable t, Object value, boolean selected,
                    boolean focus, int row, int column) {
                super.getTableCellRendererComponent(t, value, selected, focus, row, column);
                setBackground(NewsPanel.BG); setForeground(NewsPanel.MUTED);
                setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8)); return this;
            }
        });
    }
    static JScrollPane scroll(java.awt.Component content) {
        JScrollPane pane = NewsPanel.scroll(content);
        for (var bar : new JScrollBar[]{pane.getHorizontalScrollBar(), pane.getVerticalScrollBar()}) {
            bar.setUI(new javax.swing.plaf.basic.BasicScrollBarUI() {
                @Override protected void configureScrollBarColors() { thumbColor = new Color(86, 76, 104); trackColor = NewsPanel.CARD; }
                @Override protected JButton createDecreaseButton(int orientation) { return zero(); }
                @Override protected JButton createIncreaseButton(int orientation) { return zero(); }
                private JButton zero() { JButton b = new JButton(); b.setPreferredSize(new Dimension()); return b; }
            });
            bar.setUnitIncrement(24);
        }
        pane.getVerticalScrollBar().setPreferredSize(new Dimension(9, 0));
        pane.getHorizontalScrollBar().setPreferredSize(new Dimension(0, 9)); return pane;
    }
    static void tab(JToggleButton tab) {
        tab.setUI(new javax.swing.plaf.basic.BasicToggleButtonUI() {
            @Override protected void paintButtonPressed(java.awt.Graphics g, AbstractButton button) {
                g.setColor(new Color(59, 47, 80)); g.fillRect(0, 0, button.getWidth(), button.getHeight());
                g.setColor(NewsPanel.PURPLE); g.fillRect(0, button.getHeight() - 2, button.getWidth(), 2);
            }
        });
        tab.setBorder(BorderFactory.createEmptyBorder(6, 13, 6, 13));
    }
}

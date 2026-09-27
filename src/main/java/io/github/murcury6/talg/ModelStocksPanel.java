package io.github.murcury6.talg;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;

/** Edits the model universe; selecting a stock neither places an order nor alters an armed snapshot. */
final class ModelStocksPanel extends JPanel {
    private final Set<String> coverage;
    private boolean bulkSelection;
    private final DefaultTableModel rows = new DefaultTableModel(new String[]{"Use", "Stock", "Predictor"}, 0) {
        @Override public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int row, int column) { return column == 0; }
    };
    private final JTable table = NewsPanel.table(rows);
    private final TableRowSorter<DefaultTableModel> sorter;
    private final JTextField search = new JTextField(14);
    private final JCheckBox allAvailable = new JCheckBox("All available");
    private final JLabel count = NewsPanel.label("", NewsPanel.MUTED);

    ModelStocksPanel(Collection<String> selected, Collection<String> available) {
        this(selected,available,available,false);
    }
    ModelStocksPanel(Collection<String> selected, Collection<String> available, Collection<String> covered, boolean all) {
        super(new BorderLayout(0, 6)); setBackground(NewsPanel.BG);
        coverage = Set.copyOf(covered);
        var candidates = new LinkedHashSet<>(selected); available.stream().sorted().forEach(candidates::add);
        for (String symbol : candidates) rows.addRow(new Object[]{selected.contains(symbol), symbol, coverage.contains(symbol) ? "Covered" : "Not covered"});
        sorter = new TableRowSorter<>(rows);
        CompactUi.table(table); table.setRowSorter(sorter);
        table.getColumnModel().getColumn(0).setMaxWidth(48);
        table.getAccessibleContext().setAccessibleName("Candidate stocks for the selection model");
        JPanel bar = NewsPanel.panel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 5, 0));
        JLabel caption = NewsPanel.label("Stock", NewsPanel.TEXT); caption.setLabelFor(search);
        search.setBackground(NewsPanel.CARD); search.setForeground(NewsPanel.TEXT); search.setCaretColor(NewsPanel.TEXT);
        search.setToolTipText("Filter stocks or type a ticker to add");
        JButton add = NewsPanel.button("Add"), shown = NewsPanel.button("Select shown"), none = NewsPanel.button("Clear shown");
        for (var control : new JComponent[]{allAvailable, caption, search, add, shown, none}) bar.add(control);
        add(bar, BorderLayout.NORTH); add(CompactUi.scroll(table)); add(count, BorderLayout.SOUTH);
        search.getDocument().addDocumentListener(new DocumentListener() {
            private void filter() { sorter.setRowFilter(RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(search.getText().strip()), 1)); }
            public void insertUpdate(DocumentEvent e) { filter(); }
            public void removeUpdate(DocumentEvent e) { filter(); }
            public void changedUpdate(DocumentEvent e) { filter(); }
        });
        Runnable addStock = () -> {
            try { addStock(search.getText()); search.setText(""); }
            catch (IllegalArgumentException error) { count.setText(error.getMessage()); }
        };
        add.addActionListener(event -> addStock.run()); search.addActionListener(event -> addStock.run());
        shown.addActionListener(event -> selectShown(true)); none.addActionListener(event -> selectShown(false));
        rows.addTableModelListener(event -> { if (!bulkSelection) updateCount(); }); updateCount();
        allAvailable.setOpaque(false); allAvailable.setForeground(NewsPanel.TEXT); allAvailable.setSelected(all);
        allAvailable.setToolTipText("Resolve the complete active, tradable broker catalogue on every run; not just filtered rows");
        allAvailable.addActionListener(event -> { table.setEnabled(!allAvailable.isSelected()); updateCount(); });
        table.setEnabled(!all); updateCount(); setPreferredSize(new Dimension(690, 430));
    }
    void addStock(String value) {
        String symbol = value.strip().toUpperCase(Locale.ROOT);
        validateCandidates(List.of(symbol));
        allAvailable.setSelected(false); table.setEnabled(true);
        for (int i=0; i<rows.getRowCount(); i++) if (symbol.equals(rows.getValueAt(i,1))) { rows.setValueAt(true,i,0); return; }
        rows.addRow(new Object[]{true, symbol, coverage.contains(symbol) ? "Covered" : "Not covered"});
    }
    void selectShown(boolean selected) {
        allAvailable.setSelected(false); table.setEnabled(true);
        bulkSelection = true;
        try { for (int i=0; i<table.getRowCount(); i++) rows.setValueAt(selected, table.convertRowIndexToModel(i), 0); }
        finally { bulkSelection = false; updateCount(); }
    }
    List<String> selected() {
        var selected = new java.util.ArrayList<String>();
        for (int i=0; i<rows.getRowCount(); i++) if (Boolean.TRUE.equals(rows.getValueAt(i,0))) selected.add((String) rows.getValueAt(i,1));
        return List.copyOf(selected);
    }
    boolean usesAllAvailable() { return allAvailable.isSelected(); }
    static void validateCandidates(List<String> symbols) {
        if (symbols.isEmpty() || new java.util.HashSet<>(symbols).size() != symbols.size()
                || symbols.stream().anyMatch(symbol -> symbol == null || !symbol.matches("[A-Z][A-Z0-9.\\-]{0,14}")))
            throw new IllegalArgumentException("Choose unique stock tickers or All available.");
    }
    static void validate(List<String> symbols) {
        if (symbols.isEmpty() || symbols.size() > 120 || new java.util.HashSet<>(symbols).size() != symbols.size()
                || symbols.stream().anyMatch(symbol -> symbol == null || !symbol.matches("[A-Z]{1,5}")))
            throw new IllegalArgumentException("Choose 1–120 unique stocks (1–5 letters per ticker).");
    }
    private void updateCount() {
        if (allAvailable.isSelected()) { count.setText("All active, tradable US equities · resolved from the broker catalogue at Run"); return; }
        List<String> selected = selected(); long uncovered = selected.stream().filter(symbol -> !coverage.contains(symbol)).count();
        count.setText(selected.size() + " candidates" + (uncovered > 0 ? " · " + uncovered + " need predictor coverage before arming" : " · evaluated by selection code"));
    }
}

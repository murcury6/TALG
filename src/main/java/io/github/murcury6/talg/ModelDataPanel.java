package io.github.murcury6.talg;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;

/** Renders declared result columns and arbitrary retained JSON; contains no scoring rules. */
final class ModelDataPanel extends JPanel {
    private final DefaultTableModel rows = new DefaultTableModel() {
        @Override public boolean isCellEditable(int row, int column) { return false; }
        @Override public Class<?> getColumnClass(int column) { return column < types.size() ? types.get(column) : String.class; }
    };
    private final JTable table = NewsPanel.table(rows);
    private final JTree detail = new JTree(new DefaultMutableTreeNode("Select an item"));
    private final List<JsonNode> records = new ArrayList<>();
    private final JScrollPane retained;
    private final JSplitPane split;
    ModelDataPanel() {
        super(new BorderLayout()); setBackground(NewsPanel.BG);
        CompactUi.table(table); table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        detail.setBackground(NewsPanel.CARD); detail.setForeground(NewsPanel.TEXT);
        var renderer = new javax.swing.tree.DefaultTreeCellRenderer();
        renderer.putClientProperty("html.disable", Boolean.TRUE);
        renderer.setBackgroundNonSelectionColor(NewsPanel.CARD); renderer.setTextNonSelectionColor(NewsPanel.TEXT);
        renderer.setBackgroundSelectionColor(new java.awt.Color(59,47,80)); renderer.setTextSelectionColor(NewsPanel.TEXT);
        renderer.setLeafIcon(null); renderer.setOpenIcon(null); renderer.setClosedIcon(null); detail.setCellRenderer(renderer);
        var list = CompactUi.scroll(table); list.setPreferredSize(new Dimension(690, 600));
        retained = CompactUi.scroll(detail); retained.setPreferredSize(new Dimension(320,600));
        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, list, retained);
        split.setDividerSize(0); split.setResizeWeight(.74); split.setBorder(null); retained.setVisible(false); add(split);
        inspect.setBackground(NewsPanel.CARD); inspect.setForeground(NewsPanel.TEXT); CompactUi.tab(inspect);
        inspect.setToolTipText("Show retained information for the selected row");
        inspect.addActionListener(event -> {
            retained.setVisible(inspect.isSelected()); split.setDividerSize(inspect.isSelected() ? 5 : 0);
            if (inspect.isSelected()) split.setDividerLocation(.74);
            revalidate();
        });
        list.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent event) {
                ModelTablePresentation.fit(table,list.getViewport().getWidth());
            }
        });
        sheets.setBackground(NewsPanel.CARD); sheets.setForeground(NewsPanel.TEXT);
        sheets.getAccessibleContext().setAccessibleName("Sheet defined by model code");
        sheets.setToolTipText("Model-defined sheets · sort applies to the loaded page");
        sheets.addActionListener(event -> { if (!switching) showSheet(); });
        table.getSelectionModel().addListSelectionListener(event -> {
            int selected = table.getSelectedRow();
            if (!event.getValueIsAdjusting() && selected >= 0 && table.convertRowIndexToModel(selected) < records.size()) {
                detail.setModel(new DefaultTreeModel(tree("Retained information", records.get(table.convertRowIndexToModel(selected)))));
                detail.expandRow(0);
            }
        });
    }
    private JsonNode payload;
    private JsonNode workbook;
    private final JComboBox<String> sheets = new JComboBox<>();
    private final JToggleButton inspect = new JToggleButton("Inspect");
    private final List<Class<?>> types = new ArrayList<>();
    private boolean switching;
    JComboBox<String> sheetSelector() { return sheets; }
    JToggleButton inspectControl() { return inspect; }
    void display(JsonNode payload) {
        this.payload = payload;
        workbook = ModelWorkbook.dataSchema(payload.path("workbook"));
        if (workbook.isMissingNode() || workbook.isNull()) {
            var fallback = PaperTestRunner.JSON.createObjectNode();
            var sheet = fallback.putArray("sheets").addObject().put("name", "Results");
            var columns = sheet.putArray("columns");
            payload.path("columns").forEach(value -> {
                String key = value.asText();
                columns.addObject().put("id", key)
                        .put("path", "/" + key.replace("~", "~0").replace("/", "~1"));
            });
            workbook = fallback;
        } else ModelWorkbook.validate(workbook);
        String selected = (String) sheets.getSelectedItem();
        switching = true; sheets.removeAllItems();
        workbook.path("sheets").forEach(sheet -> sheets.addItem(sheet.path("name").asText()));
        if (selected != null) for (int i = 0; i < sheets.getItemCount(); i++)
            if (selected.equals(sheets.getItemAt(i))) sheets.setSelectedIndex(i);
        switching = false; showSheet();
    }
    private void showSheet() {
        int selected = sheets.getSelectedIndex(); if (selected < 0 || payload == null) return;
        JsonNode sheet = workbook.path("sheets").get(selected);
        records.clear(); table.setRowSorter(null); rows.setRowCount(0); types.clear();
        List<JsonNode> columns = new ArrayList<>(); sheet.path("columns").forEach(columns::add);
        for (JsonNode column : columns) types.add(switch (column.path("type").asText("auto")) {
            case "number" -> Double.class; case "boolean" -> Boolean.class; default -> String.class;
        });
        rows.setColumnIdentifiers(columns.stream().map(column -> ModelTablePresentation.title(column.path("id").asText())).toArray());
        for (JsonNode record : payload.path("rows")) {
            JsonNode source = record.at(sheet.path("rows").asText(""));
            if (source.isMissingNode() || source.isNull()) continue;
            if (source.isArray()) for (JsonNode item : source) addRecord(record, item, columns);
            else addRecord(record, source, columns);
        }
        for (int col=0; col<columns.size(); col++) if (columns.get(col).path("type").asText("auto").equals("auto")) {
            Class<?> inferred = null;
            for (int row=0; row<rows.getRowCount(); row++) {
                Object value = rows.getValueAt(row,col); if (value == null) continue;
                if (inferred == null) inferred = value.getClass(); else if (inferred != value.getClass()) { inferred = Object.class; break; }
            }
            types.set(col,inferred == null ? String.class : inferred);
        }
        ModelTablePresentation.apply(table);
        var sorter = new javax.swing.table.TableRowSorter<DefaultTableModel>(rows); table.setRowSorter(sorter);
        ModelTablePresentation.fit(table,table.getParent() == null ? 0 : table.getParent().getWidth());
        if (rows.getRowCount() > 0) table.setRowSelectionInterval(0,0);
        else detail.setModel(new DefaultTreeModel(new DefaultMutableTreeNode("No rows for this sheet in the loaded records")));
    }
    private void addRecord(JsonNode record, JsonNode item, List<JsonNode> columns) {
        records.add(record);
        rows.addRow(columns.stream().map(column -> {
            JsonNode context = column.path("source").asText("row").equals("record") ? record : item;
            JsonNode value = context.at(column.path("path").asText());
            if (value.isMissingNode() || value.isNull()) return null;
            return switch (column.path("type").asText("auto")) {
                case "number" -> value.isNumber() ? value.doubleValue() : null;
                case "boolean" -> value.isBoolean() ? value.booleanValue() : null;
                case "json" -> value.toString();
                case "auto" -> value.isNumber() ? value.doubleValue() : value.isBoolean() ? value.booleanValue() : displayValue(value);
                default -> displayValue(value);
            };
        }).toArray());
    }
    private static String displayValue(JsonNode value) {
        if (value.isNull() || value.isMissingNode()) return "Not available";
        if (value.isFloatingPointNumber()) return String.format(java.util.Locale.ROOT, "%.6g", value.asDouble());
        return value.isTextual() ? value.asText() : value.toString();
    }
    private static DefaultMutableTreeNode tree(String name, JsonNode value) {
        var node = new DefaultMutableTreeNode(name + (value.isContainerNode() ? " (" + value.size() + ")" : ": " + displayValue(value)));
        if (value.isObject()) value.fields().forEachRemaining(entry -> node.add(tree(entry.getKey(), entry.getValue())));
        else if (value.isArray()) for (int i=0; i<value.size(); i++) node.add(tree(String.valueOf(i), value.get(i)));
        return node;
    }
}

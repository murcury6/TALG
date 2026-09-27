package io.github.murcury6.talg;

import javax.swing.*;

/** App-owned presentation shared by News, Stocks and the trading model. */
final class ModelTablePresentation {
    static final int ROW_HEIGHT = 26;
    static String title(String field) {
        String value = field.startsWith("output.") ? field.substring(7) : field;
        value = value.replace('_', ' ');
        return value.isEmpty() ? "Value" : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
    static String text(Object value) {
        if (value == null) return "—";
        if (value instanceof Boolean flag) return flag ? "Yes" : "No";
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            if (numeric != 0 && (Math.abs(numeric) < .000001 || Math.abs(numeric) >= 1_000_000_000))
                return String.format(java.util.Locale.ROOT,"%.6g",numeric);
            return new java.text.DecimalFormat("0.######",java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ROOT)).format(number);
        }
        return String.valueOf(value);
    }
    static void apply(JTable table) {
        table.setRowHeight(ROW_HEIGHT);
        var metrics = table.getFontMetrics(table.getFont());
        for (int col=0; col<table.getColumnCount(); col++) {
            int width = metrics.stringWidth(table.getColumnName(col)) + 28;
            for (int row=0; row<Math.min(100,table.getRowCount()); row++) {
                String sample = text(table.getValueAt(row,col));
                width = Math.max(width, metrics.stringWidth(sample.substring(0,Math.min(100,sample.length()))) + 24);
            }
            var column = table.getColumnModel().getColumn(col);
            column.setMinWidth(60); column.setPreferredWidth(Math.min(420,Math.max(90,width)));
            column.setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
                { putClientProperty("html.disable",Boolean.TRUE); }
                @Override public java.awt.Component getTableCellRendererComponent(JTable owner,Object value,boolean selected,boolean focus,int row,int col) {
                    super.getTableCellRendererComponent(owner,value,selected,focus,row,col);
                    setText(text(value)); setToolTipText(value == null ? "Not available" : String.valueOf(value));
                    setBorder(BorderFactory.createEmptyBorder(0,8,0,8));
                    setHorizontalAlignment(value instanceof Number ? SwingConstants.RIGHT : SwingConstants.LEFT);
                    return this;
                }
            });
        }
    }
    static void fit(JTable table, int available) {
        if (available <= 0 || table.getColumnCount() == 0) return;
        int count = table.getColumnCount();
        int[] desired = new int[count], minimum = new int[count];
        var metrics = table.getFontMetrics(table.getFont());
        int total = 0, floor = 0;
        for (int col=0; col<count; col++) {
            int heading = metrics.stringWidth(table.getColumnName(col)) + 28;
            int width = heading;
            for (int row=0; row<table.getRowCount(); row++) {
                String sample = text(table.getValueAt(row,col));
                width = Math.max(width,metrics.stringWidth(sample.substring(0,Math.min(150,sample.length()))) + 24);
            }
            minimum[col] = Math.max(70,Math.min(160,heading));
            desired[col] = Math.max(minimum[col],Math.min(Math.max(240,Math.min(520,available / 2)),width));
            total += desired[col]; floor += minimum[col];
        }
        if (total > available && floor < available) {
            double fraction = (double) (available-floor)/(total-floor);
            for (int col=0; col<count; col++) desired[col] = minimum[col] + (int)((desired[col]-minimum[col])*fraction);
        }
        int used = java.util.Arrays.stream(desired).sum();
        if (used < available) {
            // Give spare workspace to content columns; keep compact metrics readable.
            int flexible = 0;
            for (int col=0; col<count; col++) if (table.getColumnClass(col) == String.class) flexible++;
            int remaining = available-used;
            for (int col=0; col<count; col++) if (flexible == 0 || table.getColumnClass(col) == String.class) {
                int recipients = flexible == 0 ? count-col : flexible;
                int extra = remaining/recipients; desired[col] += extra; remaining -= extra;
                if (flexible > 0) flexible--;
            }
        }
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        for (int col=0; col<count; col++) {
            var column = table.getColumnModel().getColumn(col);
            column.setPreferredWidth(desired[col]); column.setWidth(desired[col]);
        }
    }
}

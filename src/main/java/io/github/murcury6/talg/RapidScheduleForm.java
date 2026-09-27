package io.github.murcury6.talg;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.*;
import javax.swing.*;

/** Common schedule/volume controls require no JSON editing. */
final class RapidScheduleForm extends JPanel {
    final JTextField start = new JTextField(6), end = new JTextField(6);
    final JCheckBox extended = new JCheckBox("Include premarket / after-hours"), repeat = new JCheckBox("Repeat each trading day");
    final JSpinner scan = spinner(10, 5, 60), flatten = spinner(5, 1, 30), entries = spinner(0, 0, 10000), perStock = spinner(0, 0, 10000);
    final JSpinner positions = spinner(5, 1, 10), loss = new JSpinner(new SpinnerNumberModel(50.0, 1.0, 500.0, 1.0));
    RapidScheduleForm(RapidPaperModel model) {
        super(new BorderLayout(8, 8)); setBackground(NewsPanel.BG);
        JPanel fields = NewsPanel.panel(new GridLayout(0, 4, 10, 12));
        start.setText(model.schedule().start()); end.setText(model.schedule().end()); extended.setSelected(model.schedule().extendedHours()); repeat.setSelected(model.schedule().repeatTradingDays());
        scan.setValue(model.schedule().scanSeconds()); flatten.setValue(model.schedule().flattenMinutesBeforeEnd()); entries.setValue(model.portfolio().maxEntries()); perStock.setValue(model.portfolio().maxEntriesPerStock());
        positions.setValue(model.portfolio().maxPositions()); loss.setValue(model.portfolio().lossDollars());
        add(fields, BorderLayout.NORTH);
        field(fields, "Start (Eastern, HH:mm)", start); field(fields, "End (Eastern, HH:mm)", end);
        field(fields, "Sessions", extended); field(fields, "Repeat", repeat);
        field(fields, "Refresh quotes every (seconds)", scan); field(fields, "Close positions before end (minutes)", flatten);
        field(fields, "Daily entry cap (0 = no cap)", entries); field(fields, "Per-stock daily cap (0 = no cap)", perStock);
        field(fields, "Maximum positions", positions); field(fields, "Daily loss trigger ($)", loss);
        JTextArea help = NewsPanel.area(); help.setText("All times use America/New_York and adjust for daylight saving. Broker calendar skips holidays/weekends.\n"
                + "Half days end extended trading at 17:00. Close-position buffer ends new entries before the window closes.\n"
                + "0 removes entry-count caps; quote checks, order-rate, position and daily-loss limits still apply.\n"
                + "Repeat works while TALG is running. STOP disables repeat. Save settings, then arm to apply them.\n"
                + "The free IEX feed can lack fresh prices during parts of extended hours; the runner waits rather than using stale quotes.");
        add(help, BorderLayout.CENTER);
    }
    private static JSpinner spinner(int value, int min, int max) { return new JSpinner(new SpinnerNumberModel(value, min, max, 1)); }
    private static void field(JPanel panel, String label, JComponent input) { panel.add(NewsPanel.label(label, NewsPanel.TEXT)); input.setBackground(NewsPanel.CARD); input.setForeground(NewsPanel.TEXT); panel.add(input); }
    void apply(ObjectNode model) throws Exception {
        for (JSpinner spinner : new JSpinner[]{scan, flatten, entries, perStock, positions, loss}) spinner.commitEdit();
        var schedule = new TradingSchedule(start.getText().trim(), end.getText().trim(), extended.isSelected(), repeat.isSelected(), (int) scan.getValue(), (int) flatten.getValue());
        model.set("schedule", PaperTestRunner.JSON.valueToTree(schedule));
        ObjectNode portfolio = (ObjectNode) model.path("portfolio"); portfolio.put("maxEntries", (int) entries.getValue()).put("maxEntriesPerStock", (int) perStock.getValue())
                .put("maxPositions", (int) positions.getValue()).put("lossDollars", ((Number) loss.getValue()).doubleValue());
    }
    void setLocked(boolean locked) {
        for (JComponent field : new JComponent[]{start, end, extended, repeat, scan, flatten, entries, perStock, positions, loss}) field.setEnabled(!locked);
    }
}

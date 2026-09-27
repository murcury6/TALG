package io.github.murcury6.talg;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.*;
import javax.swing.*;

final class BidRulesForm extends JPanel {
    final JCheckBox enabled = new JCheckBox("Require bid/ask changes for strategy trades");
    final JSpinner rise = number(.1, .01, 20, .1), imbalance = number(.1, 0, .95, .05);
    final JSpinner fall = number(1, .01, 50, .1), exitImbalance = number(-.25, -.95, 0, .05);
    final JTextField entryExpression = new JTextField(), exitExpression = new JTextField();
    BidRulesForm(RapidPaperModel.BidRules rules) {
        super(new BorderLayout(8, 12)); setBackground(NewsPanel.BG);
        enabled.setSelected(rules.enabled()); rise.setValue(rules.minRiseBps()); imbalance.setValue(rules.minImbalance());
        fall.setValue(rules.exitFallBps()); exitImbalance.setValue(rules.exitImbalance());
        entryExpression.setText(rules.entryExpression()); exitExpression.setText(rules.exitExpression());
        JPanel top = NewsPanel.panel(new BorderLayout(8, 12)); top.add(enabled, BorderLayout.NORTH);
        JPanel fields = NewsPanel.panel(new GridLayout(0, 4, 10, 12));
        field(fields, "Minimum bid rise (basis points)", rise); field(fields, "Minimum bid size imbalance", imbalance);
        field(fields, "Exit on bid fall (basis points)", fall); field(fields, "Exit at size imbalance or below", exitImbalance);
        top.add(fields, BorderLayout.CENTER);
        JPanel expressions = NewsPanel.panel(new GridLayout(0, 1, 0, 4));
        expressions.add(NewsPanel.label("Entry expression (same strategy language)", NewsPanel.TEXT)); expressions.add(entryExpression);
        expressions.add(NewsPanel.label("Exit expression (same strategy language)", NewsPanel.TEXT)); expressions.add(exitExpression);
        top.add(expressions, BorderLayout.SOUTH); add(top, BorderLayout.NORTH);
        JTextArea help = NewsPanel.area(); help.setText("The expressions above are the executable rules, saved in signal.bidRules in work/strategies/rapid-paper.json.\n"
                + "The number controls supply named parameters; expressions may combine or replace the default conditions.\n"
                + "Size imbalance = (bid size - ask size) / (bid size + ask size). Positive means more displayed bid size.\n"
                + "Fields: bid_change_bps, bid_imbalance, has_sizes; parameters: min_bid_rise_bps, min_bid_imbalance, exit_bid_fall_bps, exit_bid_imbalance.\n"
                + "Entries also need the statistical script and risk checks. Exits also include statistical sells and bid stops/targets.\n"
                + "Data/execution guards remain: fresh changed quotes, valid entry sizes, paper-only routing, limits and session close.\n"
                + "This is polled IEX top-of-book data, not full market depth; no timed holding exit in bid mode.");
        add(help, BorderLayout.CENTER);
    }
    private static JSpinner number(double value, double min, double max, double step) { return new JSpinner(new SpinnerNumberModel(value, min, max, step)); }
    private static void field(JPanel p, String label, JComponent input) { p.add(NewsPanel.label(label, NewsPanel.TEXT)); p.add(input); }
    void apply(ObjectNode model) throws Exception {
        for (JSpinner field : new JSpinner[]{rise, imbalance, fall, exitImbalance}) field.commitEdit();
        var rules = new RapidPaperModel.BidRules(enabled.isSelected(), value(rise), value(imbalance), value(fall), value(exitImbalance), entryExpression.getText().trim(), exitExpression.getText().trim());
        ((ObjectNode) model.path("signal")).set("bidRules", PaperTestRunner.JSON.valueToTree(rules));
        if (rules.enabled()) ((ObjectNode) model.path("exits")).put("holdSeconds", 0);
    }
    private static double value(JSpinner field) { return ((Number) field.getValue()).doubleValue(); }
    void setLocked(boolean locked) { for (JComponent field : new JComponent[]{enabled, rise, imbalance, fall, exitImbalance, entryExpression, exitExpression}) field.setEnabled(!locked); }
}

package io.github.murcury6.talg;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.*;
import javax.swing.*;

/** Dollar sizing and turnover controls are independent of the statistical predictor. */
final class RapidVolumeForm extends JPanel {
    final JSpinner shares = number(1, 1, 100), entry = number(1000, 5, 5000), exposure = number(5000, 5, 50000);
    final JSpinner hold = number(120, 0, 600), cooldown = number(60, 5, 600), posts = number(12, 6, 24);
    RapidVolumeForm(RapidPaperModel model) {
        super(new BorderLayout(8, 12)); setBackground(NewsPanel.BG);
        shares.setValue(model.sizing().shares()); entry.setValue(model.sizing().maxEntryDollars()); exposure.setValue(model.sizing().maxExposureDollars());
        hold.setValue(model.exits().holdSeconds()); cooldown.setValue(model.execution().cooldownSeconds()); posts.setValue(model.execution().postsPerMinute());
        JPanel fields = NewsPanel.panel(new GridLayout(0, 4, 10, 12));
        field(fields, "Entry budget ($ / stock)", entry); field(fields, "Total exposure cap ($)", exposure);
        field(fields, "Maximum shares / stock", shares); field(fields, "Timed exit seconds (0 = off)", hold);
        field(fields, "Re-entry cooldown (seconds)", cooldown); field(fields, "Order submissions / minute", posts);
        add(fields, BorderLayout.NORTH);
        JTextArea help = NewsPanel.area(); help.setText("Whole-share size = entry budget / limit price, rounded down, subject to share, exposure and buying-power caps.\n"
                + "Total exposure includes pending buys. Rising market prices may take existing holdings above the entry cap.\n"
                + "Quote refresh and maximum positions are in Hours & Limits. Bid Rules enables price/size-driven trades.\n"
                + "Bid-driven mode forces the timed exit off on save. Otherwise choose 0 (off) or 30–600 seconds.\n"
                + "The order budget includes buys and sells, with capacity reserved for exits. It must exceed maximum positions.\n"
                + "Larger size and faster turnover magnify gains, losses and trading costs; this remains a PAPER volume experiment.\n"
                + "The share ceiling also updates the signal script's qty line when you save.");
        add(help, BorderLayout.CENTER);
    }
    private static JSpinner number(int initial, int min, int max) { return new JSpinner(new SpinnerNumberModel(initial, min, max, 1)); }
    private static void field(JPanel panel, String label, JComponent input) { panel.add(NewsPanel.label(label, NewsPanel.TEXT)); panel.add(input); }
    void apply(ObjectNode model) throws Exception {
        for (JSpinner field : new JSpinner[]{shares, entry, exposure, hold, cooldown, posts}) field.commitEdit();
        int cap = ((Number) shares.getValue()).intValue();
        model.set("sizing", PaperTestRunner.JSON.valueToTree(new RapidPaperModel.Sizing(cap, ((Number) entry.getValue()).doubleValue(), ((Number) exposure.getValue()).doubleValue())));
        ((ObjectNode) model.path("exits")).put("holdSeconds", ((Number) hold.getValue()).intValue());
        ((ObjectNode) model.path("execution")).put("cooldownSeconds", ((Number) cooldown.getValue()).intValue()).put("postsPerMinute", ((Number) posts.getValue()).intValue());
        var signal = (ObjectNode) model.path("signal");
        signal.put("script", signal.path("script").asText().replaceAll("(?m)^\\s*qty\\s+\\d+\\s*$", "qty " + cap));
    }
    void setLocked(boolean locked) { for (JSpinner field : new JSpinner[]{shares, entry, exposure, hold, cooldown, posts}) field.setEnabled(!locked); }
}

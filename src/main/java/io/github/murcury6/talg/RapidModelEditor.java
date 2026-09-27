package io.github.murcury6.talg;

import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.*;

/** Each tab edits one independent model brick. Saving never mutates an armed session. */
final class RapidModelEditor extends JPanel {
    private final Map<String, JTextArea> editors = new LinkedHashMap<>();
    private final JButton save = NewsPanel.button("VALIDATE & SAVE BRICKS");
    private final RapidScheduleForm schedule;
    private final RapidVolumeForm volume;
    private final BidRulesForm bids;
    RapidModelEditor(String source, Consumer<String> saveModel) throws Exception {
        super(new BorderLayout(0, 8)); setBackground(NewsPanel.BG);
        var parsed = PaperTestRunner.JSON.readValue(source, RapidPaperModel.class);
        var object = PaperTestRunner.JSON.valueToTree(parsed); JTabbedPane tabs = new JTabbedPane();
        schedule = new RapidScheduleForm(parsed); tabs.addTab("HOURS & LIMITS", schedule);
        volume = new RapidVolumeForm(parsed); tabs.addTab("SPEED & SIZE", volume);
        bids = new BidRulesForm(parsed.signal().bidRules()); tabs.addTab("BID RULES", bids);
        ((com.fasterxml.jackson.databind.node.ObjectNode) object.path("exits")).remove("holdSeconds");
        ((com.fasterxml.jackson.databind.node.ObjectNode) object.path("execution")).remove(java.util.List.of("cooldownSeconds", "postsPerMinute"));
        String[] names = {"universe", "prediction", "signal", "exits", "execution"};
        for (String name : names) {
            JTextArea editor = new JTextArea(); editor.setBackground(NewsPanel.CARD); editor.setForeground(NewsPanel.TEXT); editor.setCaretColor(NewsPanel.TEXT);
            editor.setFont(new Font("Consolas", Font.PLAIN, 13));
            editor.setText(name.equals("signal") ? object.path(name).path("script").asText() : PaperTestRunner.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(object.path(name)));
            editors.put(name, editor); tabs.addTab(name.toUpperCase(), NewsPanel.scroll(editor));
        }
        add(NewsPanel.label("Universe → statistical predictor → equations/rules → sizing → exits → portfolio limits → execution", NewsPanel.PURPLE), BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        JPanel controls = NewsPanel.panel(new FlowLayout(FlowLayout.LEFT)); JLabel message = NewsPanel.label("Saved to work/strategies/rapid-paper.json; armed sessions keep their original model snapshot.", NewsPanel.MUTED);
        save.addActionListener(event -> {
            try {
                var model = PaperTestRunner.JSON.createObjectNode().put("version", 1);
                model.set("portfolio", object.path("portfolio").deepCopy());
                for (var entry : editors.entrySet()) {
                    if (entry.getKey().equals("signal")) model.set("signal", PaperTestRunner.JSON.createObjectNode().put("script", entry.getValue().getText()).put("warmupBars", 8));
                    else model.set(entry.getKey(), PaperTestRunner.JSON.readTree(entry.getValue().getText()));
                }
                schedule.apply(model);
                volume.apply(model);
                bids.apply(model);
                String json = PaperTestRunner.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(model);
                PaperTestRunner.JSON.readValue(json, RapidPaperModel.class); saveModel.accept(json); message.setText("Bricks validated and saved. Re-arm to use them.");
            } catch (Exception error) { message.setText("Not saved: " + error.getMessage()); }
        });
        controls.add(save); controls.add(message); add(controls, BorderLayout.SOUTH);
    }
    void setLocked(boolean locked) { save.setEnabled(!locked); editors.values().forEach(e -> e.setEditable(!locked)); schedule.setLocked(locked); volume.setLocked(locked); bids.setLocked(locked); }
}

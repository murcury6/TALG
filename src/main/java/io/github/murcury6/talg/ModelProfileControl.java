package io.github.murcury6.talg;

import java.nio.file.Path;
import java.util.*;
import java.util.function.*;
import javax.swing.*;

/** Compact profile picker. Dependencies point to saved revisions, never to an editor tab. */
final class ModelProfileControl extends JComboBox<ModelProfileControl.Choice> {
    record Choice(String id, String name) { public String toString() { return name; } }
    private final ModelProfiles profiles;
    private final String kind;
    private final BooleanSupplier dirty;
    private final Consumer<String> changed;
    private final Consumer<String> message;
    private String selected = "default";
    private boolean updating;
    ModelProfileControl(Path root, String kind, BooleanSupplier dirty, Consumer<String> changed, Consumer<String> message) {
        this.profiles = new ModelProfiles(root); this.kind = kind; this.dirty = dirty; this.changed = changed; this.message = message;
        setBackground(NewsPanel.CARD); setForeground(NewsPanel.TEXT); getAccessibleContext().setAccessibleName("Model profile");
        setToolTipText("Model profile · saved links stay pinned when switching tabs");
        setRenderer(new DefaultListCellRenderer() {
            @Override public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                return super.getListCellRendererComponent(list, value == null ? "Profile" : "Profile: " + value, index, selected, focus);
            }
        });
        try { profiles.initialize(); selected = profiles.selected(kind); refresh(); } catch (Exception e) { message.accept(e.getMessage()); }
        addActionListener(event -> {
            if (updating || getSelectedItem() == null) return;
            String next = ((Choice)getSelectedItem()).id(); if (next.equals(selected)) return;
            if (dirty.getAsBoolean()) { message.accept("Save or reload this draft before switching profiles"); restore(); return; }
            try { profiles.select(kind, next); selected = next; changed.accept(next); }
            catch (Exception e) { message.accept(e.getMessage()); restore(); }
        });
    }
    String id() { return selected; }
    @Override public java.awt.Dimension getPreferredSize() {
        var size = super.getPreferredSize(); size.width = Math.min(160, size.width); return size;
    }
    private void restore() { updating = true; for (int i = 0; i < getItemCount(); i++) if (getItemAt(i).id().equals(selected)) setSelectedIndex(i); updating = false; }
    private void refresh() throws Exception {
        updating = true; removeAllItems(); for (var item : profiles.list(kind).entrySet()) addItem(new Choice(item.getKey(), item.getValue())); updating = false; restore();
    }
    void menu(JPopupMenu menu) {
        menu.addSeparator(); JMenuItem clone = new JMenuItem("New profile from saved model…");
        clone.addActionListener(event -> {
            if (dirty.getAsBoolean()) { message.accept("Save or reload this draft before creating a profile"); return; }
            String name = JOptionPane.showInputDialog(this, "Profile name", "New model profile", JOptionPane.PLAIN_MESSAGE);
            if (name == null) return;
            try { String id = profiles.cloneProfile(kind, selected, name); profiles.select(kind, id); selected = id; refresh(); changed.accept(id); }
            catch (Exception e) { message.accept(e.getMessage()); }
        }); menu.add(clone);
        if (!kind.equals("news")) { JMenuItem links = new JMenuItem("Profile links…"); links.addActionListener(event -> links()); menu.add(links); }
    }
    private void links() {
        if (dirty.getAsBoolean()) { message.accept("Save or reload this draft before changing profile links"); return; }
        try {
            // Snapshot fills legacy defaults once, so the dialog always shows the actual locked revision.
            profiles.snapshot(kind, selected);
            var deps = profiles.dependencies(kind, selected); var panel = new JPanel(new java.awt.GridLayout(0, 2, 8, 8));
            var pickers = new LinkedHashMap<String, JComboBox<Choice>>();
            for (String dependency : kind.equals("trade") ? List.of("news", "stocks") : List.of("news")) {
                var picker = new JComboBox<Choice>(); String ref = deps.path(dependency).asText();
                if (!ref.isBlank()) picker.addItem(new Choice("keep:" + ref, "Keep " + profiles.label(ref)));
                for (var item : profiles.list(dependency).entrySet()) picker.addItem(new Choice(item.getKey(), item.getValue() + " · latest saved"));
                panel.add(new JLabel(dependency.equals("news") ? "News profile" : "Stock-selection profile")); panel.add(picker); pickers.put(dependency, picker);
            }
            if (JOptionPane.showConfirmDialog(this, panel, "Pin saved profiles · changes apply on next arm", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            var refs = new LinkedHashMap<String, String>();
            for (var item : pickers.entrySet()) { Choice choice = (Choice)item.getValue().getSelectedItem();
                refs.put(item.getKey(), choice.id().startsWith("keep:") ? choice.id().substring(5) : profiles.snapshot(item.getKey(), choice.id()));
            }
            profiles.bind(kind, selected, refs); message.accept("Profile links pinned · active sessions keep their original revisions");
        } catch (Exception e) { message.accept(e.getMessage()); }
    }
}

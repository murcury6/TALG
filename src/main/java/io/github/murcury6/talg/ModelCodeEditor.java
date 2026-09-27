package io.github.murcury6.talg;

import java.awt.event.ActionEvent;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.undo.UndoManager;

/** Editing tools live in keyboard actions, leaving the full surface for source. */
final class ModelCodeEditor {
    static JScrollPane wrap(JTextArea editor) {
        JScrollPane scroll = CompactUi.scroll(editor);
        JTextArea numbers = new JTextArea("1"); numbers.setEditable(false); numbers.setFocusable(false);
        numbers.setFont(editor.getFont()); numbers.setBackground(NewsPanel.BG); numbers.setForeground(NewsPanel.MUTED);
        numbers.setBorder(BorderFactory.createEmptyBorder(10, 6, 10, 8));
        numbers.getAccessibleContext().setAccessibleName("Line numbers"); scroll.setRowHeaderView(numbers);
        editor.getDocument().addDocumentListener(new DocumentListener() {
            private void update() {
                StringBuilder lines = new StringBuilder(); for (int i=1; i<=editor.getLineCount(); i++) lines.append(i).append('\n');
                numbers.setText(lines.toString());
            }
            public void insertUpdate(DocumentEvent e) { update(); }
            public void removeUpdate(DocumentEvent e) { update(); }
            public void changedUpdate(DocumentEvent e) { update(); }
        });
        UndoManager undo = new UndoManager(); editor.getDocument().addUndoableEditListener(undo);
        action(editor, "control Z", "Undo", () -> { if (editor.isEditable() && undo.canUndo()) undo.undo(); });
        action(editor, "control Y", "Redo", () -> { if (editor.isEditable() && undo.canRedo()) undo.redo(); });
        action(editor, "control F", "Find", () -> {
            String term = JOptionPane.showInputDialog(editor, "Find in model code", editor.getSelectedText());
            if (term == null || term.isEmpty()) return;
            int index = editor.getText().indexOf(term, editor.getSelectionEnd());
            if (index < 0) index = editor.getText().indexOf(term);
            if (index >= 0) { editor.requestFocusInWindow(); editor.select(index, index + term.length()); }
        });
        action(editor, "TAB", "Indent", () -> indent(editor, false));
        action(editor, "shift TAB", "Unindent", () -> indent(editor, true));
        return scroll;
    }
    private static void action(JTextArea editor, String key, String name, Runnable run) {
        editor.getInputMap().put(KeyStroke.getKeyStroke(key), name);
        editor.getActionMap().put(name, new AbstractAction() { public void actionPerformed(ActionEvent event) { run.run(); } });
    }
    private static void indent(JTextArea editor, boolean remove) {
        if (!editor.isEditable()) return;
        if (!remove && editor.getSelectionStart() == editor.getSelectionEnd()) { editor.replaceSelection("  "); return; }
        try {
            int start = editor.getLineStartOffset(editor.getLineOfOffset(editor.getSelectionStart()));
            int end = editor.getSelectionEnd();
            String selected = editor.getText(start, end-start);
            String changed = remove ? selected.replaceAll("(?m)^ {1,2}", "") : selected.replaceAll("(?m)^", "  ");
            editor.replaceRange(changed, start, end); editor.select(start, start + changed.length());
        } catch (javax.swing.text.BadLocationException error) { throw new IllegalStateException(error); }
    }
}

package io.github.murcury6.talg;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.Timer;

/** User-visible schedule, explicit arm action and stop control for a bounded paper smoke test. */
final class PaperTestPanel extends JPanel {
    private final Supplier<AlpacaSettings> settings;
    private final Supplier<String> mode;
    private RapidPaperRunner runner;
    private final JTextField date = new JTextField(LocalDate.now(PaperTestRunner.EASTERN).toString(), 10);
    private final JTextArea status = NewsPanel.area();
    private final JButton arm = NewsPanel.button("Arm paper model"), stop = NewsPanel.button("Stop & exit");
    private final Timer timer;
    private boolean busy, closed;
    private AlpacaSettings brokerSettings;
    private RapidPaperRunner.Alpaca broker;

    PaperTestPanel(Supplier<AlpacaSettings> settings, Supplier<String> mode) {
        this(settings, mode, Path.of(""));
    }

    PaperTestPanel(Supplier<AlpacaSettings> settings, Supplier<String> mode, Path root) {
        super(new BorderLayout(0, 10)); this.settings = settings; this.mode = mode;
        setBackground(NewsPanel.BG); setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 4, 4, 4));
        JPanel controls = NewsPanel.panel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        controls.setPreferredSize(new java.awt.Dimension(0, 30));
        date.setBackground(NewsPanel.CARD); date.setForeground(NewsPanel.TEXT); date.setCaretColor(NewsPanel.TEXT);
        JLabel caption = NewsPanel.label("Session date (Eastern)", NewsPanel.TEXT); caption.setLabelFor(date);
        controls.add(caption); controls.add(date); controls.add(arm); controls.add(stop);
        JButton configuration = NewsPanel.button("Armed configuration");
        configuration.addActionListener(event -> {
            try { JOptionPane.showMessageDialog(this, runner.configurationSummary(), "Paper configuration", JOptionPane.PLAIN_MESSAGE); }
            catch (Exception error) { status.setText(error.getMessage()); }
        });
        controls.add(configuration); add(controls, BorderLayout.NORTH);
        status.setFont(new Font("Consolas", Font.PLAIN, 13)); add(CompactUi.scroll(status), BorderLayout.CENTER);
        try { runner = new RapidPaperRunner(root); }
        catch (Exception error) { status.setText("Cannot read the saved paper session: " + PaperTestRunner.safeError(error)); }
        arm.addActionListener(event -> arm());
        stop.addActionListener(event -> work(() -> { runner.requestStop(); return runner.description(); }));
        timer = new Timer(1_000, event -> tick()); timer.start(); update();
    }

    private void arm() {
        if (runner == null || busy) return;
        if (!"paper".equals(mode.get()) || settings.get() == null) { status.setText("Connect a PAPER account in Settings first."); return; }
        String selectedDate = date.getText().trim();
        try { LocalDate.parse(selectedDate); }
        catch (RuntimeException error) { status.setText("Use a valid date: YYYY-MM-DD."); return; }
        String summary, profileRevision;
        try { profileRevision = runner.prepareProfile(); summary = runner.configurationSummary(); } catch (Exception error) { status.setText(error.getMessage()); return; }
        if (JOptionPane.showConfirmDialog(this,
                "Arm the PAPER rapid basket starting " + selectedDate + "?\n\n" + summary + "\n\nOrders run without per-order prompts.",
                "Arm paper strategy", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.YES_OPTION) return;
        AlpacaSettings connection = settings.get(); String selectedMode = mode.get();
        work(() -> { runner.arm(selectedDate, broker(connection), selectedMode, Instant.now(), profileRevision); return runner.description(); });
    }
    private Instant nextScheduledScan = Instant.EPOCH;
    private void tick() {
        if (closed || busy || runner == null || !runner.active()) return;
        AlpacaSettings connection = settings.get(); String selectedMode = mode.get();
        work(() -> {
            if (connection == null) throw new java.io.IOException("Paper account disconnected. No new orders can be sent.");
            Instant now = Instant.now();
            if (now.isBefore(nextScheduledScan) && !runner.newsWakePending()) return runner.description();
            nextScheduledScan = now.plusSeconds(runner.state().model.schedule().scanSeconds());
            runner.tick(broker(connection), selectedMode, now);
            return runner.description();
        });
    }
    private RapidPaperRunner.Alpaca broker(AlpacaSettings connection) {
        if (broker == null || !connection.equals(brokerSettings)) { broker = new RapidPaperRunner.Alpaca(connection); brokerSettings = connection; }
        return broker;
    }
    private void work(Callable<String> operation) {
        if (closed || busy) return;
        busy = true; updateButtons();
        new SwingWorker<String, Void>() {
            protected String doInBackground() throws Exception { return operation.call(); }
            protected void done() {
                busy = false;
                try { status.setText(get()); status.setCaretPosition(0); if (runner.active()) { date.setText(runner.state().date); } }
                catch (Exception error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    status.setText((runner == null ? "" : runner.description() + "\n\n") + "Operation stopped: " + cause.getMessage());
                }
                updateButtons();
            }
        }.execute();
    }
    private void update() { if (runner != null) { status.setText(runner.description()); if (!runner.state().date.isBlank()) date.setText(runner.state().date); } updateButtons(); }
    private void updateButtons() {
        boolean active = runner != null && runner.active();
        arm.setEnabled(!busy && runner != null && !active); stop.setEnabled(!busy && active); date.setEnabled(!busy && !active);
    }
    void closeRunner() {
        closed = true; timer.stop();
        if (runner != null) {
            Thread release = new Thread(() -> { try { runner.close(); } catch (Exception ignored) {} }, "paper-test-close");
            release.start();
        }
    }
}

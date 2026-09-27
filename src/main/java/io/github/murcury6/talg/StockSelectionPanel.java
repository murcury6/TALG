package io.github.murcury6.talg;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Independent, inspectable universe model. Applying results only edits the trading draft. */
final class StockSelectionPanel extends JPanel {
    @FunctionalInterface interface Apply { void accept(List<String> symbols) throws Exception; }
    private final Path root;
    private Path file;
    private final ModelProfileControl profiles;
    private final ModelBridge bridge;
    private final Apply apply;
    private final java.util.function.Supplier<AlpacaSettings> settings;
    private final java.util.function.Supplier<String> mode;
    private final JTextArea code = new JTextArea();
    private final ModelDataPanel data = new ModelDataPanel();
    private final java.awt.CardLayout cards = new java.awt.CardLayout();
    private final JPanel content = NewsPanel.panel(cards);
    private final JButton save = NewsPanel.button("Save"), validate = NewsPanel.button("Validate"), run = NewsPanel.button("Run"),
            use = NewsPanel.button("Use selection"), candidates = NewsPanel.button("Candidates"), more = NewsPanel.button("More ▾");
    private final JToggleButton sourceTab = new JToggleButton("Code", true), resultsTab = new JToggleButton("Results");
    private final JLabel status = NewsPanel.label("", NewsPanel.MUTED);
    private String loaded = "";
    private JsonNode result;
    private boolean busy;
    private JDialog watchlistWindow;

    StockSelectionPanel(Path root, Apply apply, JPanel watchlist) {
        this(root,apply,watchlist,() -> null,() -> "paper");
    }
    StockSelectionPanel(Path root, Apply apply, JPanel watchlist, java.util.function.Supplier<AlpacaSettings> settings, java.util.function.Supplier<String> mode) {
        super(new BorderLayout(0,5)); this.settings = settings; this.mode = mode; this.root = root; this.apply = apply;
        file = root.resolve("work/models/stock-selection.talg"); bridge = new ModelBridge(root);
        profiles = new ModelProfileControl(root, "stocks", () -> busy || dirty(), this::selectProfile, status::setText);
        try { file = new ModelProfiles(root).file("stocks", profiles.id(), "source"); } catch (Exception e) { status.setText(e.getMessage()); }
        bridge.profile("stocks", profiles.id());
        setBackground(NewsPanel.BG); setBorder(BorderFactory.createEmptyBorder(4,4,4,4));
        var toolbar = NewsPanel.panel(new BorderLayout(8,0)); toolbar.setPreferredSize(new Dimension(0,30));
        var actions = NewsPanel.panel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT,3,0));
        var tabs = new ButtonGroup(); tabs.add(sourceTab); tabs.add(resultsTab);
        for (var tab : new JToggleButton[]{sourceTab,resultsTab}) { CompactUi.tab(tab); tab.setBackground(NewsPanel.CARD); tab.setForeground(NewsPanel.TEXT); }
        for (var item : new JComponent[]{profiles,sourceTab,resultsTab,candidates,more,save,validate,run,use,data.sheetSelector(),data.inspectControl()}) actions.add(item);
        toolbar.add(actions,BorderLayout.WEST); toolbar.add(status); add(toolbar,BorderLayout.NORTH);
        code.setFont(new Font("Consolas",Font.PLAIN,14)); code.setBackground(NewsPanel.CARD); code.setForeground(NewsPanel.TEXT); code.setCaretColor(NewsPanel.TEXT);
        code.setTabSize(2); code.setBorder(BorderFactory.createEmptyBorder(10,12,10,12));
        code.getAccessibleContext().setAccessibleName("Stock selection model source");
        content.add(ModelCodeEditor.wrap(code),"code"); content.add(data,"results"); add(content);
        data.sheetSelector().setVisible(false); data.inspectControl().setVisible(false);
        sourceTab.addActionListener(event -> showResults(false)); resultsTab.addActionListener(event -> showResults(true));
        save.addActionListener(event -> save()); validate.addActionListener(event -> work(() -> bridge.call("stock-validate",code.getText(),0,""), value -> status.setText("Selection model is valid")));
        run.addActionListener(event -> run()); candidates.addActionListener(event -> candidates());
        use.addActionListener(event -> {
            try {
                if (dirty()) throw new IllegalArgumentException("Save and run this stock profile before applying its selection");
                var store = new ModelProfiles(root);
                if (!store.snapshot("stocks", profiles.id()).equals(result.path("profile_revision").asText())) throw new IllegalArgumentException("Profile links or inputs changed; Run again");
                String trade = store.selected("trade");
                String locked = store.dependencies("trade", trade).path("stocks").asText();
                if (!locked.isBlank() && !locked.equals(result.path("profile_revision").asText())) throw new IllegalArgumentException("Trading profile is pinned to another stock revision; update Model → More → Profile links first");
                apply.accept(selected()); status.setText("Selection copied to trading draft · Save there for next arm"); }
            catch (Exception error) { status.setText("Not applied: " + error.getMessage()); }
        });
        use.setToolTipText("Copy the evaluated stocks into the trading model draft; does not arm or trade");
        more.addActionListener(event -> {
            JPopupMenu menu = new JPopupMenu();
            JMenuItem refreshAssets = new JMenuItem("Refresh available stocks");
            refreshAssets.addActionListener(action -> work(() -> refreshCatalogue(true), value -> { result = null; update(); status.setText(value.path("assets").size() + " available stocks · Run to evaluate"); }));
            JMenuItem reload = new JMenuItem("Reload"), desk = new JMenuItem("Desk watchlist"), inputs = new JMenuItem("Inputs");
            reload.addActionListener(action -> {
                if (dirty() && JOptionPane.showConfirmDialog(this,"Discard unsaved stock-selection code?","Reload",JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
                load();
            });
            desk.addActionListener(action -> {
                if (watchlistWindow == null || !watchlistWindow.isDisplayable()) {
                    watchlistWindow = new JDialog(SwingUtilities.getWindowAncestor(this),"Desk watchlist",java.awt.Dialog.ModalityType.MODELESS);
                    watchlistWindow.add(watchlist); watchlistWindow.setSize(750,600); watchlistWindow.setLocationRelativeTo(this);
                }
                watchlistWindow.setVisible(true); watchlistWindow.toFront();
            });
            inputs.addActionListener(action -> JOptionPane.showMessageDialog(this,
                    "param candidates = \"all_available\" (or a ticker list)\nparam max_stocks = 20\n\n"
                    + "Inputs: symbol, asset, execution_supported, predictor_covered, news, news_available, news_age_seconds, retained, custom\n"
                    + "output include = boolean expression\noutput priority = number (higher first; optional)\n"
                    + "Output data is model-defined; the app handles all visuals.\n"
                    + "Custom inputs: work/models/stock-selection-inputs.json, defaults and symbols objects.\n"
                    + "Run uses retained observations. Use selection copies a draft; it does not trade.","Stock model inputs",JOptionPane.PLAIN_MESSAGE));
            JMenuItem refreshNews = new JMenuItem("Run linked news");
            refreshNews.addActionListener(action -> work(() -> bridge.call("refresh-news",null,0,""), value -> { result = null; update(); status.setText("Pinned news refreshed · Run to update selection"); }));
            menu.add(reload); menu.add(inputs); menu.add(refreshNews); menu.add(refreshAssets); menu.add(desk); profiles.menu(menu); menu.show(more,0,more.getHeight());
        });
        code.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { update(); }
            public void removeUpdate(DocumentEvent e) { update(); }
            public void changedUpdate(DocumentEvent e) { update(); }
        });
        status.addPropertyChangeListener("text", event -> status.setToolTipText(status.getText())); load();
    }
    private void selectProfile(String id) {
        try { file = new ModelProfiles(root).file("stocks", id, "source"); bridge.profile("stocks", id); load(); sourceTab.setSelected(true); showResults(false); }
        catch (Exception e) { status.setText(e.getMessage()); }
    }
    private void load() {
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                String source;
                try (var input = getClass().getResourceAsStream("/stock-selection.talg")) { source = new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8); }
                Path trading = root.resolve("work/strategies/rapid-paper.json");
                if (Files.exists(trading)) source = withCandidates(source, readSymbols(PaperTestRunner.JSON.readTree(trading.toFile()).path("universe")));
                Files.writeString(file,source);
            }
            loaded = Files.readString(file); code.setText(loaded); code.setCaretPosition(0); result = null;
            status.setText("Stock selection model · candidates and selection rules in code"); update();
        } catch (Exception error) { status.setText("Cannot load stock model: " + error.getMessage()); }
    }
    private void showResults(boolean results) {
        cards.show(content,results ? "results" : "code"); data.sheetSelector().setVisible(results); data.inspectControl().setVisible(results);
        if (results) status.setText(result == null ? "Run the selection model first" : !result.path("source").asText().equals(code.getText()) ? "Previous result · code changed; Run again" : summary(result));
    }
    private void candidates() {
        String source = code.getText();
        work(() -> {
            var value = (com.fasterxml.jackson.databind.node.ObjectNode) bridge.call("stock-validate",source,0,"");
            value.set("catalogue", refreshCatalogue(false)); return value;
        }, value -> {
            try {
                JsonNode candidateValue = value.path("parameters").path("candidates");
                boolean all = candidateValue.isTextual();
                var selected = all ? List.<String>of() : readSymbols(candidateValue);
                var available = new java.util.ArrayList<String>();
                value.path("catalogue").path("assets").forEach(asset -> available.add(asset.path("symbol").asText()));
                var covered = new java.util.ArrayList<String>();
                Path config = new ModelProfiles(root).file("stocks", profiles.id(), "trading");
                if (Files.exists(config)) {
                    String relative = PaperTestRunner.JSON.readTree(config.toFile()).path("prediction").path("modelFile").asText();
                    Path predictor = root.toAbsolutePath().resolve(relative).normalize();
                    if (!predictor.startsWith(root.toAbsolutePath().normalize())) throw new IllegalArgumentException("Predictor must be inside the project");
                    if (Files.isRegularFile(predictor)) covered.addAll(readSymbols(PaperTestRunner.JSON.readTree(predictor.toFile()).path("symbols")));
                }
                var picker = new ModelStocksPanel(selected,available,covered,all);
                while (JOptionPane.showConfirmDialog(this,picker,"Stock model candidates",JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
                    try { code.setText(picker.usesAllAvailable() ? withAllAvailable(source) : withCandidates(source,picker.selected())); status.setText("Candidates updated in code · Run to select stocks"); break; }
                    catch (IllegalArgumentException error) { JOptionPane.showMessageDialog(this,error.getMessage()); }
                }
            } catch (Exception error) { status.setText(error.getMessage()); }
        });
    }
    static String withCandidates(String source, List<String> symbols) throws java.io.IOException {
        ModelStocksPanel.validateCandidates(symbols);
        return withCandidateValue(source,PaperTestRunner.JSON.writeValueAsString(symbols));
    }
    static String withAllAvailable(String source) { return withCandidateValue(source,"\"all_available\""); }
    private static String withCandidateValue(String source, String literal) {
        var matcher = java.util.regex.Pattern.compile("(?m)^\\h*param\\h+candidates\\h*=\\h*.+$").matcher(source);
        if (!matcher.find()) throw new IllegalArgumentException("Declare param candidates = [\"AAPL\", \"MSFT\"] in code");
        return matcher.replaceFirst(java.util.regex.Matcher.quoteReplacement("param candidates = " + literal));
    }
    private JsonNode refreshCatalogue(boolean required) throws Exception {
        Path catalogue = root.resolve("work/stocks/available-assets.json");
        AlpacaSettings connection = settings.get();
        if (connection == null) {
            if (required) throw new IllegalArgumentException("Connect Alpaca in Settings to refresh available stocks");
            return Files.exists(catalogue) ? PaperTestRunner.JSON.readTree(catalogue.toFile()) : PaperTestRunner.JSON.createObjectNode();
        }
        JsonNode value = new AlpacaOrderClient().availableAssets(connection,mode.get());
        Files.createDirectories(catalogue.getParent());
        NewsService.replaceFile(catalogue,PaperTestRunner.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
        return value;
    }
    private void save() {
        String source = code.getText(), expected = loaded;
        work(() -> {
            bridge.call("stock-validate",source,0,"");
            if (!Files.readString(file).equals(expected)) throw new java.io.IOException("Stock model changed on disk; Reload first");
            Path history = root.resolve("work/models/history"); Files.createDirectories(history);
            Files.writeString(history.resolve("stock-selection-" + java.util.UUID.randomUUID() + ".talg"),expected);
            NewsService.replaceFile(file,source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return PaperTestRunner.JSON.createObjectNode();
        }, value -> { loaded = source; update(); status.setText("Stock selection model saved"); });
    }
    private void run() {
        String source = code.getText();
        work(() -> {
            JsonNode declaration = bridge.call("stock-validate",source,0,"");
            if (declaration.path("parameters").path("candidates").isTextual()) refreshCatalogue(false);
            JsonNode value = bridge.call("stock-run",source,0,"");
            Path history = root.resolve("work/stock-selection"); Files.createDirectories(history);
            PaperTestRunner.JSON.writeValue(history.resolve("run-" + java.util.UUID.randomUUID() + ".json").toFile(),value);
            return value;
        }, value -> { result = value; data.display(value); resultsTab.setSelected(true); showResults(true); update(); });
    }
    private String summary(JsonNode value) { return value.path("selected").size() + " / " + value.path("total").asInt() + " selected · " + value.path("errors").asInt() + " errors · retained inputs" + (value.path("candidate_mode").asText().equals("all_available") ? " · catalogue " + value.path("available_catalogue").path("fetched_at").asText("unknown date") : ""); }
    List<String> selected() {
        if (result == null || !result.path("source").asText().equals(code.getText())) throw new IllegalArgumentException("Run the current code before using its selection");
        if (result.path("errors").asInt() != 0) throw new IllegalArgumentException("Fix selection errors before using the result");
        var symbols = readSymbols(result.path("selected")); ModelStocksPanel.validate(symbols); return symbols;
    }
    private static List<String> readSymbols(JsonNode values) { var result = new java.util.ArrayList<String>(); values.forEach(value -> result.add(value.asText())); return result; }
    private boolean dirty() { return !loaded.equals(code.getText()); }
    private void update() {
        save.setEnabled(!busy && dirty());
        use.setEnabled(!busy && result != null && result.path("source").asText().equals(code.getText()) && result.path("errors").asInt() == 0 && !result.path("selected").isEmpty());
    }
    private void work(java.util.concurrent.Callable<JsonNode> task, java.util.function.Consumer<JsonNode> done) {
        if (busy) return; setBusy(true); status.setText("Working…");
        new SwingWorker<JsonNode,Void>() {
            protected JsonNode doInBackground() throws Exception { return task.call(); }
            protected void done() {
                setBusy(false);
                try { done.accept(get()); }
                catch (Exception error) { status.setText("Failed: " + (error.getCause() == null ? error.getMessage() : error.getCause().getMessage())); }
            }
        }.execute();
    }
    private void setBusy(boolean value) {
        busy = value; profiles.setEnabled(!busy); code.setEditable(!busy);
        for (var control : new AbstractButton[]{sourceTab,resultsTab,candidates,more,validate,run}) control.setEnabled(!busy);
        data.sheetSelector().setEnabled(!busy); update();
    }
    boolean confirmClose() { return !dirty() || JOptionPane.showConfirmDialog(this,"Close with unsaved stock-selection code?","Stocks",JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION; }
    void close() { if (watchlistWindow != null) watchlistWindow.dispose(); }
}

package io.github.murcury6.talg;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;

/** One domain workspace: code, controls and a model-owned workbook in one utility row. */
final class ModelsPanel extends JPanel {
    private final Path root;
    private final ModelFiles files;
    private final ModelProfileControl profiles;
    private final TradingPanel trading;
    private final JToggleButton codeView = new JToggleButton("Model", true), dataView = new JToggleButton("Data");
    private final ButtonGroup views = new ButtonGroup();
    private final JButton options = NewsPanel.button("More ▾");
    private final JComboBox<String> view = new JComboBox<>(new String[]{"Model", "Data", "Settings", "Inputs", "Status"});
    private final JButton save = NewsPanel.button("Save"), reload = NewsPanel.button("Reload"),
            session = NewsPanel.button("Paper session"), rules = NewsPanel.button("Rule editor");
    private final JLabel message = NewsPanel.label("", NewsPanel.MUTED);
    private final JTextArea newsEditor = editor(), tradeEditor = editor(), predictor = editor();
    private final DefaultTableModel overview = NewsPanel.model("Setting", "Current value");
    private final JTable overviewTable = NewsPanel.table(overview);
    private final java.awt.CardLayout cards = new java.awt.CardLayout();
    private final JPanel content = NewsPanel.panel(cards);
    private final Map<Boolean, String> loaded = new HashMap<>();
    private final Map<String, JDialog> windows = new HashMap<>();
    private final JTextArea newsCode = editor(), tradeCode = editor();
    private final ModelDataPanel dataPanel = new ModelDataPanel();
    private final JButton validate = NewsPanel.button("Validate"), run = NewsPanel.button("Run"), controls = NewsPanel.button("Controls"),
            previous = NewsPanel.button("←"), next = NewsPanel.button("→");
    private final JTextField search = new JTextField(12);
    private final JLabel searchLabel = NewsPanel.label("Search", NewsPanel.MUTED);
    private final ModelBridge bridge;
    private String loadedNewsCode = "";
    private int offset;
    private boolean busy;
    private static final java.util.Set<String> TRADE_INPUTS = java.util.Set.of("previous_close", "predicted_return", "trading_cost", "prediction_rank");
    private boolean news, switching;
    private NewsPanel headlines;

    ModelsPanel(Path root, TradingPanel trading) { this(root, trading, false); }
    ModelsPanel(Path root, TradingPanel trading, boolean news) {
        super(new BorderLayout(0, 5)); this.root = root; this.trading = trading; this.news = news; files = new ModelFiles(root); bridge = new ModelBridge(root);
        profiles = new ModelProfileControl(root, ModelProfiles.kind(news), () -> busy || dirty(this.news) || codeDirty(this.news), this::selectProfile, message::setText);
        files.profile(news, profiles.id()); bridge.profile(ModelProfiles.kind(news), profiles.id());
        setBackground(NewsPanel.BG); setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        JPanel toolbar = NewsPanel.panel(new BorderLayout(8, 0)); toolbar.setPreferredSize(new Dimension(0, 30));
        JPanel actions = NewsPanel.panel(new java.awt.GridBagLayout());
        var cell = new java.awt.GridBagConstraints(); cell.insets = new java.awt.Insets(0, 3, 0, 3);
        actions.add(profiles, cell); views.add(codeView); views.add(dataView);
        for (var tab : new JToggleButton[]{codeView, dataView}) {
            CompactUi.tab(tab); tab.setBackground(NewsPanel.CARD); tab.setForeground(NewsPanel.TEXT); tab.setFocusPainted(false);
            actions.add(tab, cell);
        }
        codeView.addActionListener(event -> view.setSelectedItem("Model"));
        dataView.addActionListener(event -> view.setSelectedItem(news ? "Data" : "Tickers"));
        options.addActionListener(event -> {
            JPopupMenu menu = new JPopupMenu();
            for (String item : news ? new String[]{"Headlines", "Symbol lookup", "Settings", "Inputs", "Status", "Data schema example"} : new String[]{"Settings", "Inputs", "Status", "Fitted predictor", "Rule editor", "Run linked news", "News input", "React to news", "Data schema example"}) {
                JMenuItem choice = new JMenuItem(item);
                choice.addActionListener(action -> {
                    if (item.equals("Rule editor")) openRules();
                    else if (item.equals("Run linked news")) work(() -> bridge.call("refresh-news", null, 0, ""), value -> message.setText("Pinned news refreshed · " + value.path("rated").asInt() + " rated"));
                    else if (item.equals("Symbol lookup")) lookupSymbol();
                    else if (item.equals("React to news")) {
                        if (!tradeCode.getText().lines().anyMatch(line -> line.strip().equals("react news")))
                            tradeCode.insert("react news\n", 0);
                        view.setSelectedItem("Model"); message.setText("Draft reacts to symbols selected by News; trading rules decide orders");
                    }
                    else if (item.equals("News input")) {
                        String source = tradeCode.getText();
                        if (source.contains("input news_rating ")) { message.setText("news_rating is already declared"); return; }
                        var firstRule = java.util.regex.Pattern.compile("(?m)^(?:param|let|output|buy|sell)\\s").matcher(source);
                        int at = firstRule.find() ? firstRule.start() : 0;
                        tradeCode.insert("input news_rating News(rating, 120)\n", at);
                        view.setSelectedItem("Model"); tradeCode.requestFocusInWindow(); tradeCode.setCaretPosition(at);
                        message.setText("News input added to draft · use news_rating in your expressions");
                    }
                    else if (item.equals("Headlines")) {
                        if (headlines == null) headlines = new NewsPanel(root);
                        showTool("Headlines", headlines); headlines.activate();
                    } else if (item.equals("Data schema example")) {
                        try {
                            if (ModelWorkbook.parse(codeEditor().getText()).workbook() == null) codeEditor().append(ModelWorkbook.template(news));
                            view.setSelectedItem("Model"); codeEditor().setCaretPosition(Math.max(0, codeEditor().getText().indexOf("sheet\n")));
                        } catch (IllegalArgumentException error) { message.setText(error.getMessage()); }
                    } else view.setSelectedItem(item);
                });
                menu.add(choice);
            }
            profiles.menu(menu); menu.show(options, 0, options.getHeight());
        });
        view.setBackground(NewsPanel.CARD); view.setForeground(NewsPanel.TEXT); view.getAccessibleContext().setAccessibleName("Model view");
        view.addActionListener(event -> { if (!switching) showView(); });
        for (var control : new JComponent[]{options, reload, save, validate, run, controls, session, dataPanel.sheetSelector(), dataPanel.inspectControl(), searchLabel, search, previous, next}) actions.add(control, cell);
        toolbar.add(actions, BorderLayout.WEST); toolbar.add(message, BorderLayout.CENTER); add(toolbar, BorderLayout.NORTH);
        overviewTable.getColumnModel().getColumn(0).setPreferredWidth(190);
        overviewTable.getColumnModel().getColumn(0).setMaxWidth(235);
        overviewTable.getColumnModel().getColumn(1).setPreferredWidth(800);
        overviewTable.setAutoCreateRowSorter(false);
        CompactUi.table(overviewTable);
        content.add(CompactUi.scroll(overviewTable), "Overview");
        content.add(CompactUi.scroll(newsEditor), "News source"); content.add(CompactUi.scroll(tradeEditor), "Trade source");
        content.add(ModelCodeEditor.wrap(newsCode), "News code"); content.add(ModelCodeEditor.wrap(tradeCode), "Trade code");
        content.add(dataPanel, "Data");
        predictor.setEditable(false); content.add(CompactUi.scroll(predictor), "Predictor"); add(content, BorderLayout.CENTER);
        message.addPropertyChangeListener("text", event -> message.setToolTipText(message.getText()));
        save.addActionListener(event -> save()); reload.addActionListener(event -> reload());
        session.addActionListener(event -> showTool("Paper session", trading.paperWorkspace()));
        rules.addActionListener(event -> showTool("Rule editor", trading.ruleWorkspace()));
        validate.addActionListener(event -> validateCode()); run.addActionListener(event -> runCode());
        controls.addActionListener(event -> editParameters());
        search.setBackground(NewsPanel.CARD); search.setForeground(NewsPanel.TEXT); search.setCaretColor(NewsPanel.TEXT);
        searchLabel.setLabelFor(search); search.addActionListener(event -> { offset = 0; loadData(); });
        previous.addActionListener(event -> { offset = Math.max(0, offset - 200); loadData(); });
        next.addActionListener(event -> { offset += 200; loadData(); });
        for (var editor : new JTextArea[]{newsEditor, tradeEditor, newsCode, tradeCode}) editor.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { updateSave(); }
            public void removeUpdate(DocumentEvent event) { updateSave(); }
            public void changedUpdate(DocumentEvent event) { updateSave(); }
        });
        load(true); load(false);
        try {
            Path code = newsCodePath();
            if (!Files.exists(code)) { Files.createDirectories(code.getParent());
                try (var input = ModelsPanel.class.getResourceAsStream("/news-rating.talg")) { Files.copy(input, code); }
            }
            loadedNewsCode = Files.readString(code); newsCode.setText(loadedNewsCode); newsCode.setCaretPosition(0);
        } catch (Exception error) { message.setText("Cannot read news code: " + error.getMessage()); }
        switchModel(news);
        try {
            if (ModelWorkbook.parse(codeEditor().getText()).workbook() == null) {
                codeEditor().append(ModelWorkbook.template(news)); codeEditor().setCaretPosition(0);
                message.setText("Data schema added to draft · Save to keep it");
            }
        } catch (IllegalArgumentException error) { message.setText(error.getMessage()); }
    }

    private Path newsCodePath() throws java.io.IOException {
        return new ModelProfiles(root).file("news", news ? profiles.id() : "default", "source");
    }
    private void selectProfile(String id) {
        files.profile(news, id); bridge.profile(ModelProfiles.kind(news), id); load(news); offset = 0;
        if (news) try { loadedNewsCode = Files.readString(newsCodePath()); newsCode.setText(loadedNewsCode); newsCode.setCaretPosition(0); }
        catch (Exception e) { message.setText(e.getMessage()); }
        view.setSelectedItem("Model"); showView(); message.setText("Profile loaded · saved dependencies remain pinned");
    }
    private String pinnedNewsIfNeeded(String source) throws java.io.IOException {
        var parsed = StrategyScript.parse(source, TRADE_INPUTS);
        return parsed.reactToNews() || parsed.inputs().stream().anyMatch(i -> i.kind().equals("news")) ? pinnedNews() : null;
    }
    private String pinnedNews() throws java.io.IOException {
        var store = new ModelProfiles(root); store.snapshot("trade", profiles.id()); return store.newsReference("trade", profiles.id());
    }
    private static JTextArea editor() {
        JTextArea area = new JTextArea(); area.setFont(new Font("Consolas", Font.PLAIN, 14));
        area.setBackground(NewsPanel.CARD); area.setForeground(NewsPanel.TEXT); area.setCaretColor(NewsPanel.TEXT);
        area.setTabSize(2); area.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        area.getAccessibleContext().setAccessibleName("Model source editor"); return area;
    }
    private JTextArea currentEditor() { return news ? newsEditor : tradeEditor; }
    private boolean dirty(boolean isNews) { return loaded.containsKey(isNews) && !loaded.get(isNews).equals((isNews ? newsEditor : tradeEditor).getText()); }
    private JTextArea codeEditor() { return news ? newsCode : tradeCode; }
    private boolean codeDirty(boolean isNews) {
        if (isNews) return !loadedNewsCode.equals(newsCode.getText());
        try { return !PaperTestRunner.JSON.readTree(loaded.get(false)).path("signal").path("script").asText().equals(tradeCode.getText()); }
        catch (Exception error) { return false; }
    }
    private void updateSave() { save.setEnabled(!busy && ("Settings".equals(view.getSelectedItem()) && dirty(news)
            || "Model".equals(view.getSelectedItem()) && (codeDirty(news) || !news && dirty(false)))); }

    private void switchModel(boolean next) {
        news = next; switching = true;
        view.removeAllItems(); view.addItem("Model"); view.addItem(news ? "Data" : "Tickers");
        view.addItem("Settings"); view.addItem("Inputs"); view.addItem("Status");
        if (!news) view.addItem("Fitted predictor");
        view.setSelectedIndex(0); offset = 0;
        dataView.setText(news ? "Data" : "Tickers");
        switching = false; rules.setVisible(false);
        showView();
    }

    private void showView() {
        String selected = String.valueOf(view.getSelectedItem());
        boolean modelView = selected.equals("Model"), dataView = selected.equals("Data") || selected.equals("Tickers");
        if (modelView) codeView.setSelected(true); else if (dataView) this.dataView.setSelected(true); else views.clearSelection();
        for (var button : new JButton[]{validate, run, controls}) button.setVisible(modelView);
        session.setVisible(!news && modelView); save.setVisible(modelView || selected.equals("Settings"));
        dataPanel.sheetSelector().setVisible(dataView); dataPanel.inspectControl().setVisible(dataView);
        searchLabel.setText(news ? "Headlines" : "Ticker");
        search.setVisible(dataView); searchLabel.setVisible(dataView); previous.setVisible(dataView && news); next.setVisible(dataView && news);
        if (modelView) {
            cards.show(content, news ? "News code" : "Trade code");
            message.setText(news ? "Code defines inputs and calculated data" : "Trading code and data · next armed snapshot");
        } else if (dataView) { cards.show(content, "Data"); loadData();
        } else if (selected.equals("Inputs")) { showInputs(); cards.show(content, "Overview");
        } else if (selected.equals("Settings")) {
            cards.show(content, news ? "News source" : "Trade source");
            message.setText(news ? "Encoder / aggregation configuration" : "Model components and execution configuration");
        } else if (selected.equals("Fitted predictor")) {
            try {
                var model = PaperTestRunner.JSON.readValue(files.read(false), RapidPaperModel.class);
                Path path = root.resolve(model.prediction().modelFile());
                predictor.setText(Files.readString(path)); predictor.setCaretPosition(0); message.setText(model.prediction().modelFile() + " · read only");
            } catch (Exception error) { predictor.setText("Cannot read fitted predictor: " + error.getMessage()); }
            cards.show(content, "Predictor");
        } else { readOverview(); cards.show(content, "Overview"); }
        updateSave();
    }

    private void load(boolean isNews) {
        try {
            String source = files.read(isNews); loaded.put(isNews, source);
            JTextArea editor = isNews ? newsEditor : tradeEditor; editor.setText(source); editor.setCaretPosition(0);
            if (!isNews) { tradeCode.setText(PaperTestRunner.JSON.readTree(source).path("signal").path("script").asText()); tradeCode.setCaretPosition(0); }
        } catch (Exception error) { message.setText("Cannot load model: " + error.getMessage()); }
    }
    private void reload() {
        if ((dirty(news) || codeDirty(news)) && JOptionPane.showConfirmDialog(this, "Discard unsaved changes to this model?", "Reload model",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        load(news);
        if (news) try { loadedNewsCode = Files.readString(newsCodePath()); newsCode.setText(loadedNewsCode); }
        catch (Exception error) { message.setText(error.getMessage()); }
        showView();
    }
    private void save() {
        if ("Model".equals(view.getSelectedItem())) { saveCode(); return; }
        try {
            files.save(news, loaded.get(news), currentEditor().getText());
            loaded.put(news, currentEditor().getText());
            if (!news) tradeCode.setText(PaperTestRunner.JSON.readTree(currentEditor().getText()).path("signal").path("script").asText());
            updateSave();
            message.setText(news ? "Saved · Run updates this news profile; pinned revisions stay unchanged" : "Saved · next armed session uses this file");
        } catch (Exception error) {
            message.setText("Not saved: " + error.getMessage());
            JOptionPane.showMessageDialog(this, error.getMessage(), "Model was not saved", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void validateCode() {
        String source = codeEditor().getText(); boolean selectedNews = news;
        work(() -> {
            if (selectedNews) return bridge.call("validate", source, 0, "");
            StrategyScript.parse(source, TRADE_INPUTS);
            return PaperTestRunner.JSON.createObjectNode().put("valid", true);
        }, result -> message.setText("Code is valid · no orders submitted"));
    }

    private void saveCode() {
        String source = codeEditor().getText(), original = loadedNewsCode, config = tradeEditor.getText();
        boolean selectedNews = news;
        work(() -> {
            if (selectedNews) {
                bridge.call("validate", source, 0, "");
                Path file = newsCodePath();
                if (!Files.readString(file).equals(original)) throw new java.io.IOException("News code changed on disk. Reload first.");
                Path history = root.resolve("work/models/history"); Files.createDirectories(history);
                Files.writeString(history.resolve("news-code-" + java.util.UUID.randomUUID() + ".talg"), original);
                NewsService.replaceFile(file, source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return PaperTestRunner.JSON.createObjectNode().put("source", source);
            }
            var model = (com.fasterxml.jackson.databind.node.ObjectNode) PaperTestRunner.JSON.readTree(config);
            ((com.fasterxml.jackson.databind.node.ObjectNode) model.path("signal")).put("script", source);
            String changed = PaperTestRunner.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(model);
            files.save(false, loaded.get(false), changed);
            return PaperTestRunner.JSON.createObjectNode().put("source", changed);
        }, result -> {
            if (selectedNews) loadedNewsCode = source;
            else { loaded.put(false, result.path("source").asText()); tradeEditor.setText(result.path("source").asText()); }
            updateSave(); message.setText(selectedNews ? "Saved · Run encodes, rates and tensorizes retained news" : "Saved · used on next arm; active snapshots stay unchanged");
        });
    }

    private void runCode() {
        if (news) {
            if (codeDirty(true) || dirty(true)) { message.setText("Save news code/settings before running"); return; }
            work(() -> bridge.call("run", null, 0, ""), result -> {
                message.setText(result.has("symbol_count") ? result.path("symbol_count").asInt() + " symbol tensors · " + result.path("rated").asInt() + " related articles · " + result.path("errors").asInt() + " errors" : result.path("rated").asInt() + " rated · " + result.path("errors").asInt() + " errors");
                view.setSelectedItem("Data");
            });
        } else {
            String source = tradeCode.getText(), configSource = tradeEditor.getText();
            work(() -> {
                var parsed = StrategyScript.parse(source, TRADE_INPUTS);
                var workbook = ModelWorkbook.parse(source).workbook();
                var payload = (com.fasterxml.jackson.databind.node.ObjectNode) bridge.call("ticker-inputs", null, 0, "");
                var universe = new java.util.LinkedHashSet<String>();
                PaperTestRunner.JSON.readTree(configSource).path("universe").forEach(symbol -> universe.add(symbol.asText()));
                var selectedRows = PaperTestRunner.JSON.createArrayNode();
                for (JsonNode item : payload.path("rows")) if (universe.contains(item.path("ticker").asText())) selectedRows.add(item);
                for (String symbol : universe) {
                    boolean found = false; for (JsonNode item : selectedRows) if (item.path("ticker").asText().equals(symbol)) { found = true; break; }
                    if (!found) selectedRows.addObject().put("ticker", symbol).put("output_state", "No retained observations").putObject("evaluation").putObject("values");
                }
                payload.set("rows", selectedRows); payload.put("total", selectedRows.size());
                if (workbook != null) payload.set("workbook", workbook);
                var newsInputs = new NewsModelInputs(root, source, Instant.now(), pinnedNewsIfNeeded(source));
                java.util.Set<String> names = new java.util.TreeSet<>();
                for (JsonNode item : payload.path("rows")) {
                    var row = (com.fasterxml.jackson.databind.node.ObjectNode) item;
                    Map<String, Double> inputs = new java.util.LinkedHashMap<>();
                    row.path("evaluation").path("values").fields().forEachRemaining(entry -> {
                        if (entry.getValue().isNumber()) inputs.put(entry.getKey(), entry.getValue().asDouble());
                    });
                    try {
                        var newsResult = newsInputs.forSymbol(source, row.path("ticker").asText());
                        inputs.putAll(newsResult.values()); row.set("news_model_inputs", newsResult.provenance());
                        var evaluated = parsed.evaluate(inputs);
                        evaluated.values().forEach((key, val) -> { row.put("output." + key, val); names.add("output." + key); });
                        row.put("draft_decision", evaluated.side());
                        row.set("draft_outputs", PaperTestRunner.JSON.valueToTree(evaluated.values()));
                        ((com.fasterxml.jackson.databind.node.ObjectNode) row.path("evaluation")).set("values", row.path("draft_outputs"));
                        row.put("decision", evaluated.side());
                    } catch (Exception error) { row.put("draft_decision", "Cannot evaluate: " + error.getMessage()); row.put("decision", row.path("draft_decision").asText()); ((com.fasterxml.jackson.databind.node.ObjectNode) row.path("evaluation")).putObject("values"); }
                }
                var columns = payload.putArray("columns");
                for (String name : new String[]{"ticker", "draft_decision", "evaluated_at"}) columns.add(name);
                names.forEach(columns::add); payload.put("message", "Draft evaluated on retained inputs · no live orders or new market observations"); return payload;
            }, result -> { cards.show(content, "Data"); dataPanel.display(result); dataPanel.sheetSelector().setVisible(true); dataPanel.inspectControl().setVisible(true); message.setText(result.path("message").asText()); });
        }
    }

    private void loadData() {
        if (busy) return;
        boolean selectedNews = news; int page = offset; String query = search.getText();
        String source = codeEditor().getText(); boolean draft = codeDirty(news);
        work(() -> {
            var layout = ModelWorkbook.parse(source).workbook();
            var result = (com.fasterxml.jackson.databind.node.ObjectNode) bridge.call(selectedNews ? "news-data" : "tickers", null, page, query);
            if (layout != null) result.set("workbook", layout); else result.remove("workbook");
            return result;
        }, result -> {
            dataPanel.display(result);
            int total = result.path("total").asInt(), count = result.path("rows").size();
            previous.setEnabled(selectedNews && offset > 0); next.setEnabled(selectedNews && offset + count < total);
            message.setText((count == 0 ? result.path("message").asText("0") : (offset + 1) + "–" + (offset + count)) + " / " + total + (draft ? " · draft schema" : "") + " · page sort");
            message.setToolTipText(result.path("message").asText());
        });
    }

    void applyStocks(java.util.List<String> symbols) throws java.io.IOException {
        ModelStocksPanel.validate(symbols);
        var config = (com.fasterxml.jackson.databind.node.ObjectNode) PaperTestRunner.JSON.readTree(tradeEditor.getText());
        var universe = config.putArray("universe"); symbols.forEach(universe::add);
        // Preserve both editor surfaces; never overwrite a separately edited Settings script.
        String settingsCode = config.path("signal").path("script").asText();
        String originalCode = PaperTestRunner.JSON.readTree(loaded.get(false)).path("signal").path("script").asText();
        if (!settingsCode.equals(originalCode) && codeDirty(false) && !settingsCode.equals(tradeCode.getText()))
            throw new IllegalArgumentException("Trading code and Settings contain different script drafts. Resolve that difference before applying stocks.");
        if (!settingsCode.equals(originalCode)) tradeCode.setText(settingsCode);
        else ((com.fasterxml.jackson.databind.node.ObjectNode) config.path("signal")).put("script", tradeCode.getText());
        tradeEditor.setText(PaperTestRunner.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(config));
        view.setSelectedItem("Model"); updateSave(); message.setText(symbols.size() + " stocks selected · Save for next arm");
    }

    private void lookupSymbol() {
        String symbol = JOptionPane.showInputDialog(this, "Ticker to pass to the saved news model", "AAPL");
        if (symbol == null || symbol.isBlank()) return;
        String requested = symbol.strip().toUpperCase(java.util.Locale.ROOT);
        if (!requested.matches("[A-Z][A-Z0-9.\\-]{0,14}")) { message.setText("Enter one valid ticker"); return; }
        work(() -> bridge.call("news-symbols", null, 0, requested), result -> {
            var payload = PaperTestRunner.JSON.createObjectNode(); var columns = payload.putArray("columns");
            columns.add("symbol"); columns.add("article_count"); columns.add("error");
            var record = payload.putArray("rows").addObject().put("symbol", requested);
            JsonNode rating = result.path("symbols").path(requested);
            record.setAll((com.fasterxml.jackson.databind.node.ObjectNode) rating);
            rating.path("outputs").fields().forEachRemaining(entry -> { columns.add(entry.getKey()); record.set(entry.getKey(), entry.getValue()); });
            record.set("provenance", result.deepCopy());
            ModelDataPanel panel = new ModelDataPanel(); panel.display(payload); showTool("News: " + requested, panel);
            message.setText("Saved news model called with " + requested);
        });
    }

    private void showInputs() {
        overview.setRowCount(0);
        row("Data schema", "sheet / JSON object with sheets array / end sheet; model defines columns and nested row sources");
        row("Column binding", "JSON pointers into records or model output arrays; number, boolean, text, json; the app sizes and renders the data");
        row("Editor", "Ctrl+F find; Ctrl+Z / Ctrl+Y undo / redo; Tab / Shift+Tab indent; line numbers");
        row("Declarations", "input aliases bind observed fields; param creates an editable control; output names a displayed result");
        for (String line : codeEditor().getText().split("\\R"))
            if (line.strip().matches("^(input|param|output)\\s+.*")) row(line.strip().split("\\s+", 2)[0], line.strip());
        row("News input syntax", "input alias = field; custom fields use custom.name");
        if (news) try { row("Custom input file", new ModelProfiles(root).file("news", profiles.id(), "inputs")); }
        catch (Exception e) { row("Custom inputs", e.getMessage()); }
        row("Trade input syntax", "input alias Observed(field); input news_score News(rating, 120); param name = number");
        row("News events", "News: output notify = condition; output notify_symbols = ticker_list; Trade: react news. News decides relevance; trading expressions decide entries.");
        row("News(symbol)", "Calls the saved news model's symbol outputs for the ticker; optional max age in seconds, default 120. Run News to refresh materialized results.");
        row("Symbol code", "symbol / input items = articles / output rating = mean(column(items, \"outputs.score\")) / end symbol; aggregation belongs to News");
        row("Trade observed fields", "open, high, low, close, volume, vwap, previous_close, predicted_return, trading_cost, prediction_rank");
        row("Functions (news)", "contains, length, component, dot, norm, sum, mean, min, max, abs, sqrt, log, exp, clamp, decay");
        row("Outputs", "News supports numbers, text, booleans, vectors and objects. Trading expressions produce numbers/booleans.");
        row("Execution", "File, network and order access are supplied by adapters, never expression code");
        message.setText("Controls and displayed output names come from code");
    }

    private void editParameters() {
        JTextArea editor = codeEditor(); String source = editor.getText();
        var pattern = java.util.regex.Pattern.compile("(?m)^\\h*param\\s+([a-z][a-z0-9_]{0,39})\\h*=\\h*(.+)$");
        var matcher = pattern.matcher(source);
        record Control(int start, int end, String name, JTextField field) {}
        java.util.List<Control> fields = new java.util.ArrayList<>();
        JPanel form = NewsPanel.panel(new java.awt.GridLayout(0,2,8,8));
        int symbolStart = source.indexOf("\nsymbol\n"), symbolEnd = source.indexOf("\nend symbol");
        while (matcher.find()) {
            JTextField field = new JTextField(matcher.group(2), 12);
            boolean symbol = symbolStart >= 0 && matcher.start() > symbolStart && matcher.start() < symbolEnd;
            String name = (symbol ? "Symbol · " : "") + matcher.group(1);
            JLabel caption = NewsPanel.label(name, NewsPanel.TEXT); caption.setLabelFor(field);
            fields.add(new Control(matcher.start(2), matcher.end(2), name, field)); form.add(caption); form.add(field);
        }
        if (fields.isEmpty()) { message.setText("Declare controls in code: param name = number"); view.setSelectedItem("Inputs"); return; }
        if (JOptionPane.showConfirmDialog(this, form, "Parameters declared by this code", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        try {
            StringBuilder changed = new StringBuilder(source);
            for (int i=fields.size()-1; i>=0; i--) {
                Control control = fields.get(i); String value = control.field().getText().strip();
                if (value.isBlank() || value.contains("\n") || !news && (!value.matches("-?[0-9]+(?:\\.[0-9]+)?") || !Double.isFinite(Double.parseDouble(value))))
                    throw new IllegalArgumentException("Invalid parameter value for " + control.name());
                changed.replace(control.start(), control.end(), value);
            }
            editor.setText(changed.toString()); message.setText("Parameters updated in code · Save to apply");
        } catch (Exception error) { message.setText(error.getMessage()); }
    }

    private void work(java.util.concurrent.Callable<JsonNode> operation, java.util.function.Consumer<JsonNode> success) {
        if (busy) return; busy = true; setBusy(); message.setText("Working…");
        new SwingWorker<JsonNode, Void>() {
            protected JsonNode doInBackground() throws Exception { return operation.call(); }
            protected void done() {
                busy = false; setBusy();
                try { success.accept(get()); }
                catch (Exception error) { message.setText("Failed: " + (error.getCause() == null ? error.getMessage() : error.getCause().getMessage())); }
            }
        }.execute();
    }
    private void setBusy() {
        for (var button : new AbstractButton[]{ codeView, dataView, options, reload, validate, run, controls, previous, next, session}) button.setEnabled(!busy);
        profiles.setEnabled(!busy); dataPanel.sheetSelector().setEnabled(!busy);
        view.setEnabled(!busy); search.setEnabled(!busy);
        for (var editor : new JTextArea[]{newsCode, tradeCode, newsEditor, tradeEditor}) editor.setEditable(!busy);
        updateSave();
    }

    void activate() {
        if (news) { if (headlines == null) headlines = new NewsPanel(root); headlines.activate(); }
        if ("Status".equals(view.getSelectedItem())) readOverview();
    }
    void openRules() { if (news) return; showTool("Rule editor", trading.ruleWorkspace()); }
    private void row(String name, Object value) { overview.addRow(new Object[]{name, String.valueOf(value)}); }
    private void readOverview() {
        overview.setRowCount(0); message.setText(dirty(news) ? "Unsaved configuration draft" : "Saved model");
        try {
            row("Profile", profiles.getSelectedItem()); row("Model file", files.path(news).toString());
            var links = new ModelProfiles(root).dependencies(ModelProfiles.kind(news), profiles.id());
            var entries = links.fields(); while (entries.hasNext()) { var link = entries.next(); row("Pinned " + link.getKey(), new ModelProfiles(root).label(link.getValue().asText())); }
            JsonNode model = PaperTestRunner.JSON.readTree(files.read(news));
            if (news) {
                row("Input", "Collected headlines and publisher excerpts"); row("Output", "Meaning vectors with separate ticker relevance and time decay");
                boolean weighted = model.path("engine").asText().equals("symbol_weighted_tensor_v1");
                JsonNode pipeline = weighted ? model.path("pipeline") : model;
                row("Encoder", pipeline.path("encoder").path("model").asText());
                row("Dimensions", pipeline.path("representation").path("dimensions").asInt(pipeline.path("encoder").path("dimension").asInt()));
                if (weighted) {
                    row("Symbols", model.path("symbols")); row("Formula", pipeline.path("formula").asText());
                    row("Updates", "Run refreshes this saved profile on the retained news collection");
                    if (pipeline.has("effect_model")) {
                        var effect = pipeline.path("effect_model");
                        row("Effect model", effect.path("artifact").isNull() ? "Awaiting outcome labels and fitting" : "Pinned fitted research candidate");
                        row("Planned effect dimensions", effect.path("training").path("hidden_dimensions").asInt());
                        row("Forecast horizons (sessions)", effect.path("training").path("horizons_sessions"));
                    }
                    row("Connection model", new ModelProfiles(root).file("news", profiles.id(), "connection_weight"));
                    row("Relevance model", new ModelProfiles(root).file("news", profiles.id(), "relatedness"));
                }
                if (!weighted) { row("Half-lives (seconds)", model.path("half_lives_seconds")); row("Relevance rules", model.path("ticker_rules").size()); }
                row("Effect models", model.path("effect_heads").isEmpty() ? "None fitted — no price-effect prediction" : model.path("effect_heads").size());
                Path statusFile = new ModelProfiles(root).revisionFolder(new ModelProfiles(root).snapshot("news", profiles.id())).resolve("runtime/status.json");
                if (Files.exists(statusFile)) {
                    JsonNode state = PaperTestRunner.JSON.readTree(statusFile.toFile());
                    boolean recent;
                    try { recent = Instant.parse(state.path("as_of_utc").asText()).isAfter(Instant.now().minusSeconds(30)); }
                    catch (RuntimeException error) { recent = false; }
                    row("Worker", recent ? state.path("state").asText() : "No recent update (last reported: " + state.path("state").asText() + ")");
                    row("Last update", NewsPanel.local(state.path("as_of_utc").asText()));
                    row("Encoded articles", state.path("encoded_items").asLong()); row("Pending articles", state.path("pending_items").asLong());
                } else row("Worker", "No status file yet");
                row("Changes apply", "Run publishes this saved revision; linked profiles retain their pinned revision until relinked");
            } else {
                var parsed = PaperTestRunner.JSON.treeToValue(model, RapidPaperModel.class);
                row("Universe", parsed.universe().size() + " stocks / ETFs"); row("Predictor", parsed.prediction().modelFile());
                row("Signal", parsed.signal().bidRules().enabled() ? "Bid rules and visible strategy expressions" : "Strategy expressions");
                if (parsed.signal().bidRules().enabled()) {
                    row("Entry expression", parsed.signal().bidRules().entryExpression()); row("Exit expression", parsed.signal().bidRules().exitExpression());
                } else {
                    row("Buy rule", parsed.signal().script().lines().filter(line -> line.strip().startsWith("buy ")).findFirst().orElse("Not supplied"));
                    row("Sell rule", parsed.signal().script().lines().filter(line -> line.strip().startsWith("sell ")).findFirst().orElse("Not supplied"));
                }
                row("Entry budget", "$" + parsed.sizing().maxEntryDollars()); row("Exposure limit", "$" + parsed.sizing().maxExposureDollars());
                row("Hours (Eastern)", parsed.schedule().start() + "–" + parsed.schedule().end());
                Path stateFile = root.resolve("work/rapid-paper/session.json");
                if (Files.exists(stateFile)) {
                    JsonNode state = PaperTestRunner.JSON.readTree(stateFile.toFile());
                    row("Paper session", state.path("phase").asText() + " · " + state.path("date").asText());
                    row("Session snapshot", state.path("id").asText());
                    row("Armed profile revision", state.path("profileRevision").asText("Legacy session"));
                    row("Session predictor", state.path("model").path("prediction").path("modelFile").asText());
                    row("Last scan", NewsPanel.local(state.path("lastScan").asText()));
                } else row("Paper session", "Not armed");
                row("News input", "News(output, maxAgeSeconds) reads symbol results from the News model; declared inputs only"); row("Changes apply", "Saved configuration is frozen into a new snapshot when armed; PAPER only");
            }
        } catch (Exception error) { row("Cannot load model", error.getMessage()); }
    }
    private void showTool(String title, JPanel panel) {
        JDialog existing = windows.get(title);
        if (existing != null && existing.isDisplayable()) { existing.toFront(); return; }
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this), title, java.awt.Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE); dialog.add(panel);
        dialog.setSize(1080, 670); dialog.setLocationRelativeTo(this); windows.put(title, dialog); dialog.setVisible(true);
    }
    boolean confirmClose() {
        return !dirty(news) && !codeDirty(news) || JOptionPane.showConfirmDialog(this, "Close with unsaved model changes?",
                "Models", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }
    void close() { if (headlines != null) headlines.stop(); windows.values().forEach(JDialog::dispose); }
}

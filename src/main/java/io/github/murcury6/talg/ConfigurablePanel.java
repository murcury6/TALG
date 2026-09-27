package io.github.murcury6.talg;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;

/** A blank workspace panel whose data and display are chosen in the panel itself. */
final class ConfigurablePanel extends JPanel {
    private static final Color BG = new Color(20, 18, 27);
    private static final Color CARD = new Color(34, 30, 45);
    private static final Color BORDER = new Color(70, 62, 87);
    private static final Color TEXT = new Color(232, 229, 239);
    private static final Color MUTED = new Color(153, 149, 164);
    private static final Color PURPLE = new Color(177, 146, 245);
    private static final PanelType[] TYPES = {
            new PanelType("Portfolio", "Your Alpaca account and positions."),
            new PanelType("Stock chart", "Alpaca bars, comparisons and indicators from one chart script."),
            new PanelType("Market quote", "Latest Alpaca snapshot for one ticker."),
            new PanelType("Watchlist", "The tickers you added this session."),
            new PanelType("Modeling board", "Inspect your model's validated forecast CSV."),
            new PanelType("Models", "Write and run your own R model on observed Alpaca bars.")
    };

    private final Function<Spec, JComponent> createView;
    private final Consumer<ConfigurablePanel> onDispose;
    private final Supplier<String> selectedTicker;
    private final Supplier<List<String>> watchlistTickers;
    private final CardLayout cards = new CardLayout();
    private final JLayeredPane content = new JLayeredPane() {
        @Override public void doLayout() {
            if (currentView != null) currentView.setBounds(0, 0, getWidth(), getHeight());
            if (quickDrawer != null && quickOpen)
                quickDrawer.setBounds(0, 0, Math.min(242, getWidth()),
                        Math.min(getHeight(), (Integer) quickDrawer.getClientProperty("drawerHeight")));
        }
    };
    private final JComboBox<PanelType> type = new JComboBox<>(TYPES);
    private final JComboBox<String> portfolioView = new JComboBox<>(new String[]{
            "Overview", "Performance", "Allocation", "Risk lens", "Holdings"
    });
    private final JComboBox<Choice> period = new JComboBox<>(new Choice[]{
            new Choice("1 month", "1M"), new Choice("3 months", "3M"),
            new Choice("6 months", "6M"), new Choice("Year to date", "YTD"),
            new Choice("1 year", "1Y")
    });
    private final JTextField symbol = new JTextField();
    private final JTextArea chartScript = new JTextArea();
    private final JTextField modelFile = new JTextField();
    private final JLabel description = label("", 11, MUTED, Font.PLAIN);
    private final JLabel validation = label("", 11, PURPLE, Font.PLAIN);
    private JPanel portfolioRow;
    private JPanel periodRow;
    private JPanel scriptRow;
    private JPanel symbolRow;
    private JPanel modelFileRow;
    private JComponent currentView;
    private String lastLoadedScript;
    private FormState activeForm;
    private Runnable onDescriptionChanged = () -> {};
    private boolean quickOpen;
    private JComponent quickDrawer;
    private String quickNotice = "";

    ConfigurablePanel(Function<Spec, JComponent> createView, Supplier<String> selectedTicker,
                      Consumer<ConfigurablePanel> onDispose) {
        this(createView, selectedTicker, List::of, onDispose);
    }

    ConfigurablePanel(Function<Spec, JComponent> createView, Supplier<String> selectedTicker,
                      Supplier<List<String>> watchlistTickers,
                      Consumer<ConfigurablePanel> onDispose) {
        super();
        this.createView = createView;
        this.onDispose = onDispose;
        this.selectedTicker = selectedTicker;
        this.watchlistTickers = watchlistTickers;
        setLayout(cards);
        setBackground(BG);
        content.setOpaque(true);
        content.setBackground(BG);
        add(modeChoice(), "mode");
        add(editor(), "editor");
        add(aiSetup(), "ai");
        add(content, "content");
        period.setSelectedIndex(1);
        String initialTicker = selectedTicker.get();
        if (initialTicker != null) symbol.setText(initialTicker);
        chartScript.setText(ChartScript.template(initialTicker));
        lastLoadedScript = chartScript.getText();
        type.addActionListener(event -> updateFields());
        portfolioView.addActionListener(event -> updateFields());
        updateFields();
        cards.show(this, "mode");
    }

    boolean isConfigured() { return currentView != null; }

    void onDescriptionChanged(Runnable listener) { onDescriptionChanged = listener; }

    String shortDescription() {
        if (activeForm == null) return "Blank panel";
        if ("Stock chart".equals(activeForm.type())) {
            try {
                ChartScript.Config chart = ChartScript.parse(activeForm.chartScript());
                String width = switch (chart.bars()) {
                    case "1Day" -> "daily";
                    case "1Week" -> "weekly";
                    case "1Hour" -> "hourly";
                    default -> chart.bars();
                };
                String stocks = chart.ticker() + (chart.comparisons().isBlank()
                        ? "" : " / " + chart.comparisons().replace(",", " / "));
                String value = "close".equals(chart.plot()) ? "" : chart.plot() + " · ";
                String display = "line".equals(chart.display()) ? "" : chart.display().replace('_', ' ') + " · ";
                return stocks + " · " + value + display + width + " bars · " + chart.range() + " window";
            } catch (RuntimeException error) { return "Stock chart"; }
        }
        if ("Portfolio".equals(activeForm.type()))
            return activeForm.portfolioView() + " · " + activeForm.period() + " account history";
        if ("Market quote".equals(activeForm.type())) return activeForm.symbol() + " · quote";
        return activeForm.type();
    }

    void refreshVisible() {
        if (currentView instanceof RPortfolioPanel chart) chart.refreshNow();
        else if (currentView instanceof MarketQuotePanel quote) quote.refresh();
        else if (currentView instanceof ModelingBoardPanel board) board.refresh();
    }

    void showConfigurationEditor() {
        if (currentView instanceof AnalysisStudioPanel studio) {
            studio.focusCode();
            return;
        }
        cards.show(this, "editor");
    }

    void toggleQuickVariables() {
        if (activeForm == null) { cards.show(this, "mode"); return; }
        quickOpen = !quickOpen;
        if (quickDrawer != null) content.remove(quickDrawer);
        quickDrawer = quickOpen ? buildQuickDrawer() : null;
        if (quickDrawer != null) content.add(quickDrawer, JLayeredPane.PALETTE_LAYER);
        content.revalidate();
        content.repaint();
        cards.show(this, "content");
    }

    private JComponent buildQuickDrawer() {
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBackground(CARD);
        form.setBorder(new EmptyBorder(6, 7, 6, 7));
        if ("Stock chart".equals(activeForm.type())) {
            ChartScript.Config chart = ChartScript.parse(activeForm.chartScript());
            JTextField ticker = new JTextField(chart.ticker());
            JComboBox<String> window = new JComboBox<>(new String[]{"1D", "5D", "1M", "3M", "6M", "YTD", "1Y"});
            window.setSelectedItem(chart.range());
            JComboBox<String> bars = new JComboBox<>(new String[]{"1Min", "5Min", "15Min", "1Hour", "1Day", "1Week"});
            bars.setSelectedItem(chart.bars());
            JTextField plot = new JTextField(lineValue(activeForm.chartScript(), "plot"));
            JComboBox<String> display = new JComboBox<>(new String[]{
                    "line", "step", "area", "points", "columns", "lollipop",
                    "candles", "hollow_candles", "ohlc", "heikin_ashi"});
            display.setSelectedItem(chart.display());
            JCheckBox watermark = new JCheckBox("Show", chart.watermark());
            watermark.setOpaque(false);
            watermark.setForeground(TEXT);
            watermark.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            JTextField study = new JTextField(lineValue(activeForm.chartScript(), "study"));
            JComboBox<String> update = new JComboBox<>(new String[]{"30s", "60s", "5m", "15m", "off"});
            update.setSelectedItem(lineValue(activeForm.chartScript(), "refresh"));
            JComboBox<String> layout = new JComboBox<>(new String[]{"overlay", "separate"});
            layout.setSelectedItem(chart.layout());
            JTextField comparisons = new JTextField(String.join(", ", chart.comparisons().split(",")));
            JTextArea overlays = new JTextArea(linesFor(activeForm.chartScript(), "overlay"));
            overlays.setRows(3);
            String[] timePriority = {""};
            String[] valuePriority = {""};
            window.addActionListener(event -> timePriority[0] = "range");
            bars.addActionListener(event -> timePriority[0] = "bars");
            display.addActionListener(event -> valuePriority[0] = "display");
            plot.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent event) { valuePriority[0] = "plot"; }
                @Override public void removeUpdate(DocumentEvent event) { valuePriority[0] = "plot"; }
                @Override public void changedUpdate(DocumentEvent event) { valuePriority[0] = "plot"; }
            });
            for (JComponent field : List.of(ticker, window, bars, plot, display, study, update, layout, comparisons, overlays))
                styleQuickField(field);
            addQuickRow(form, "TICKER", ticker);
            addQuickRow(form, "WINDOW", window);
            addQuickRow(form, "BAR WIDTH", bars);
            addQuickRow(form, "PLOT VALUE", plot);
            addQuickRow(form, "DISPLAY", display);
            addQuickRow(form, "WATERMARK", watermark);
            addQuickRow(form, "LOWER STUDY", study);
            addQuickRow(form, "UPDATE", update);
            addQuickRow(form, "LAYOUT", layout);
            addQuickRow(form, "COMPARE TICKERS", comparisons);
            addQuickRow(form, "OVERLAYS · ONE PER LINE", new JScrollPane(overlays));
            JButton apply = new JButton("APPLY");
            styleAction(apply);
            apply.setToolTipText("Update this chart and save in the desk profile");
            apply.addActionListener(event -> {
                ChartQuickVariables.Resolution resolved = ChartQuickVariables.resolve(activeForm.chartScript(),
                        new ChartQuickVariables.Request(ticker.getText(), String.valueOf(window.getSelectedItem()),
                                String.valueOf(bars.getSelectedItem()), plot.getText(),
                                String.valueOf(display.getSelectedItem()), study.getText(),
                                String.valueOf(update.getSelectedItem()), String.valueOf(layout.getSelectedItem()),
                                comparisons.getText(), overlays.getText(), watermark.isSelected(),
                                timePriority[0], valuePriority[0]));
                quickNotice = String.join("  ", resolved.adjustments());
                if (resolved.script().equals(activeForm.chartScript())) {
                    rebuildQuickDrawer();
                    return;
                }
                applyQuickChanges(() -> {
                    chartScript.setText(resolved.script());
                    symbol.setText(ChartScript.parse(resolved.script()).ticker());
                });
            });
            form.add(apply);
        } else if ("Portfolio".equals(activeForm.type())) {
            JComboBox<String> choice = new JComboBox<>(new String[]{
                    "Overview", "Performance", "Allocation", "Risk lens", "Holdings"});
            choice.setSelectedItem(activeForm.portfolioView());
            JComboBox<String> window = new JComboBox<>(new String[]{"1M", "3M", "6M", "YTD", "1Y"});
            window.setSelectedItem(activeForm.period());
            addQuickRow(form, "VIEW", choice);
            addQuickRow(form, "WINDOW", window);
            JButton apply = new JButton("APPLY");
            styleAction(apply);
            apply.setToolTipText("Update this portfolio view");
            apply.addActionListener(event -> applyQuickChanges(() -> {
                portfolioView.setSelectedItem(choice.getSelectedItem());
                for (int index = 0; index < period.getItemCount(); index++)
                    if (period.getItemAt(index).code().equals(window.getSelectedItem()))
                        period.setSelectedIndex(index);
            }));
            form.add(apply);
        } else if ("Market quote".equals(activeForm.type())) {
            JTextField ticker = new JTextField(activeForm.symbol());
            styleQuickField(ticker);
            addQuickRow(form, "TICKER", ticker);
            JButton apply = new JButton("APPLY");
            styleAction(apply);
            apply.addActionListener(event -> {
                String candidate = ticker.getText().trim().toUpperCase(java.util.Locale.ROOT);
                if (!candidate.matches("[A-Z][A-Z0-9.-]{0,9}")) {
                    candidate = activeForm.symbol();
                    quickNotice = "Kept " + candidate + "; ticker text was incomplete.";
                } else {
                    quickNotice = "";
                }
                if (candidate.equals(activeForm.symbol())) {
                    rebuildQuickDrawer();
                    return;
                }
                String selected = candidate;
                applyQuickChanges(() -> symbol.setText(selected));
            });
            form.add(apply);
        } else if ("Modeling board".equals(activeForm.type())) {
            JTextField path = new JTextField(activeForm.modelFile());
            styleQuickField(path);
            addQuickRow(form, "SIGNAL CSV", path);
            JButton browse = new JButton("BROWSE…");
            browse.setToolTipText("Choose a local CSV produced by your model");
            browse.addActionListener(event -> {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("Select model signal CSV");
                chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
                if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
                    path.setText(chooser.getSelectedFile().getAbsolutePath());
            });
            form.add(browse);
            JButton apply = new JButton("APPLY");
            styleAction(apply);
            apply.addActionListener(event -> applyQuickChanges(() -> modelFile.setText(path.getText().trim())));
            form.add(apply);
        } else {
            form.add(label("Use the code button to configure this panel.", 11, TEXT, Font.PLAIN));
        }
        if (!quickNotice.isBlank()) form.add(quickNote(quickNotice));
        JScrollPane scroll = new JScrollPane(form);
        scroll.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, BORDER));
        scroll.getViewport().setBackground(CARD);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getVerticalScrollBar().setPreferredSize(new Dimension(6, 0));
        scroll.getVerticalScrollBar().setUI(new BasicScrollBarUI() {
            @Override protected void configureScrollBarColors() {
                thumbColor = new Color(102, 96, 113);
                trackColor = CARD;
            }
            @Override protected JButton createDecreaseButton(int orientation) { return zeroButton(); }
            @Override protected JButton createIncreaseButton(int orientation) { return zeroButton(); }
            private JButton zeroButton() {
                JButton button = new JButton();
                button.setPreferredSize(new Dimension(0, 0));
                return button;
            }
        });
        scroll.putClientProperty("drawerHeight", form.getPreferredSize().height + 2);
        return scroll;
    }

    private static void addQuickRow(JPanel parent, String title, JComponent input) {
        int height = input instanceof JScrollPane ? 55 : 24;
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        row.setPreferredSize(new Dimension(220, height));
        JLabel heading = label(title, 9, TEXT, Font.BOLD);
        heading.setPreferredSize(new Dimension(91, height));
        row.add(heading, BorderLayout.WEST);
        row.add(input, BorderLayout.CENTER);
        parent.add(row);
        parent.add(Box.createVerticalStrut(3));
    }

    private static void styleQuickField(JComponent field) {
        field.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        field.setForeground(TEXT);
        field.setBackground(BG);
    }

    private static JTextArea quickNote(String text) {
        JTextArea message = new JTextArea(text, 3, 14);
        message.setEditable(false);
        message.setLineWrap(true);
        message.setWrapStyleWord(true);
        message.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        message.setForeground(TEXT);
        message.setBackground(CARD);
        message.setMaximumSize(new Dimension(Integer.MAX_VALUE, 58));
        return message;
    }

    private void applyQuickChanges(Runnable changes) {
        FormState draft = formState();
        boolean unfinishedDraft = activeForm != null && !draft.equals(activeForm);
        changes.run();
        applyConfiguration();
        if (unfinishedDraft) setFormState(draft);
    }

    private void rebuildQuickDrawer() {
        if (!quickOpen) return;
        if (quickDrawer != null) content.remove(quickDrawer);
        quickDrawer = buildQuickDrawer();
        content.add(quickDrawer, JLayeredPane.PALETTE_LAYER);
        content.revalidate();
        content.repaint();
    }

    private static String lineValue(String source, String command) {
        java.util.regex.Matcher match = java.util.regex.Pattern.compile(
                "(?m)^" + command + "\\s+([^\\r\\n]+)").matcher(source);
        return match.find() ? match.group(1).trim() : "";
    }

    private static String linesFor(String source, String command) {
        return source.lines().filter(line -> line.trim().startsWith(command + " "))
                .map(line -> line.trim().substring(command.length()).trim())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    State snapshot() {
        FormState before = formState();
        FormState updated = activeForm;
        if (updated != null && currentView instanceof MarketQuotePanel quote) {
            String ticker = quote.currentTicker();
            if (ticker.matches("[A-Z][A-Z0-9.-]{0,9}"))
                updated = new FormState(updated.type(), updated.portfolioView(), updated.period(),
                        ticker, updated.chartScript(), updated.modelFile());
        } else if (updated != null && currentView instanceof ModelingBoardPanel board) {
            updated = new FormState(updated.type(), updated.portfolioView(), updated.period(),
                    updated.symbol(), updated.chartScript(), board.currentFile());
        }
        if (updated != null && !updated.equals(activeForm)) {
            if (before.equals(activeForm)) {
                if (currentView instanceof MarketQuotePanel) symbol.setText(updated.symbol());
                if (currentView instanceof ModelingBoardPanel) modelFile.setText(updated.modelFile());
            }
            activeForm = updated;
        }
        return new State(formState(), activeForm);
    }

    void restore(State state) {
        if (state.active() != null) {
            setFormState(state.active());
            applyConfiguration();
            if (!isConfigured()) throw new IllegalArgumentException("Saved panel could not be restored.");
        }
        setFormState(state.draft());
        if (state.active() == null) cards.show(this, "mode");
    }

    private FormState formState() {
        Choice selectedPeriod = (Choice) period.getSelectedItem();
        PanelType selectedType = (PanelType) type.getSelectedItem();
        return new FormState(selectedType.name(), String.valueOf(portfolioView.getSelectedItem()),
                selectedPeriod.code(), symbol.getText(), chartScript.getText(), modelFile.getText());
    }

    private void setFormState(FormState state) {
        String requestedType = "Analysis studio".equals(state.type()) ? "Models" : state.type();
        for (int i = 0; i < type.getItemCount(); i++) {
            if (type.getItemAt(i).name().equals(requestedType)) type.setSelectedIndex(i);
        }
        portfolioView.setSelectedItem(state.portfolioView());
        for (int i = 0; i < period.getItemCount(); i++) {
            if (period.getItemAt(i).code().equals(state.period())) period.setSelectedIndex(i);
        }
        symbol.setText(state.symbol());
        chartScript.setText(state.chartScript());
        lastLoadedScript = state.chartScript();
        modelFile.setText(state.modelFile());
        updateFields();
    }

    void showEditor() {
        cards.show(this, "mode");
    }

    void openStockChart(String ticker) {
        type.setSelectedIndex(1);
        symbol.setText(ticker);
        chartScript.setText(ChartScript.template(ticker));
        lastLoadedScript = chartScript.getText();
        applyConfiguration();
    }

    void openIndicatorChart(String ticker, String indicator) {
        type.setSelectedIndex(1);
        symbol.setText(ticker);
        chartScript.setText(ChartScript.template(ticker).replace(
                "study RSI(14)", "study Indicator(" + IndicatorRepository.name(indicator) + ")"));
        lastLoadedScript = chartScript.getText();
        applyConfiguration();
    }

    void openMarketQuote(String ticker) {
        type.setSelectedIndex(2);
        symbol.setText(ticker);
        applyConfiguration();
    }

    void accountChanged(AlpacaSettings settings, String mode) {
        if (currentView instanceof RPortfolioPanel chart) chart.connect(settings, mode);
        if (currentView instanceof MarketQuotePanel quote) quote.refresh();
    }

    void accountCleared() {
        if (currentView instanceof RPortfolioPanel chart) chart.disconnect();
        if (currentView instanceof MarketQuotePanel quote) quote.disconnect();
    }

    void refreshQuote() {
        if (currentView instanceof MarketQuotePanel quote) quote.refresh();
    }

    void disposePanel() {
        if (currentView instanceof RPortfolioPanel chart) chart.close();
        onDispose.accept(this);
    }

    private JPanel modeChoice() {
        JPanel shell = new JPanel(new BorderLayout());
        shell.setBackground(BG);
        JPanel box = new JPanel();
        box.setBackground(BG);
        box.setBorder(new EmptyBorder(18, 20, 18, 20));
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        JLabel title = label("How do you want to configure this panel?", 18, TEXT, Font.BOLD);
        title.setAlignmentX(LEFT_ALIGNMENT);
        box.add(title);
        box.add(Box.createVerticalStrut(5));
        JLabel help = label("Choose a method. No data is loaded until you apply a view.",
                11, MUTED, Font.PLAIN);
        help.setAlignmentX(LEFT_ALIGNMENT);
        box.add(help);
        box.add(Box.createVerticalStrut(14));
        JPanel options = new JPanel(new GridLayout(2, 1, 0, 6));
        options.setOpaque(false);
        options.setAlignmentX(LEFT_ALIGNMENT);
        options.setMaximumSize(new Dimension(Integer.MAX_VALUE, 88));
        JButton manual = modeButton("MANUAL", "Choose data and studies yourself");
        manual.addActionListener(event -> cards.show(this, "editor"));
        options.add(manual);
        JButton ai = modeButton("AI-ASSISTED", "Describe the view you want");
        ai.addActionListener(event -> cards.show(this, "ai"));
        options.add(ai);
        box.add(options);
        shell.add(box, BorderLayout.NORTH);
        return shell;
    }

    private JPanel aiSetup() {
        JPanel shell = new JPanel(new BorderLayout());
        shell.setBackground(BG);
        JPanel box = new JPanel();
        box.setBackground(BG);
        box.setBorder(new EmptyBorder(18, 20, 18, 20));
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        JLabel title = label("AI-assisted configuration", 18, TEXT, Font.BOLD);
        title.setAlignmentX(LEFT_ALIGNMENT);
        box.add(title);
        box.add(Box.createVerticalStrut(10));
        JLabel honest = label("A local or Llama configuration model is not connected yet.",
                12, MUTED, Font.PLAIN);
        honest.setAlignmentX(LEFT_ALIGNMENT);
        box.add(honest);
        box.add(Box.createVerticalStrut(5));
        JLabel detail = label("TALG will not invent a panel from your request.",
                12, MUTED, Font.PLAIN);
        detail.setAlignmentX(LEFT_ALIGNMENT);
        box.add(detail);
        box.add(Box.createVerticalStrut(18));
        JButton manual = new JButton("CONFIGURE MANUALLY");
        styleAction(manual);
        manual.setAlignmentX(LEFT_ALIGNMENT);
        manual.addActionListener(event -> cards.show(this, "editor"));
        box.add(manual);
        shell.add(box, BorderLayout.NORTH);
        return shell;
    }

    private static JButton modeButton(String title, String detail) {
        JButton button = new JButton(title);
        button.setHorizontalAlignment(JButton.LEFT);
        button.setToolTipText(detail);
        button.setFont(new Font("Segoe UI", Font.BOLD, 12));
        button.setForeground(TEXT);
        button.setBackground(BG);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER), new EmptyBorder(7, 12, 7, 12)));
        button.setPreferredSize(new Dimension(205, 40));
        return button;
    }

    private static void styleAction(JButton button) {
        button.setFont(new Font("Segoe UI", Font.BOLD, 11));
        button.setForeground(BG);
        button.setBackground(PURPLE);
        button.setFocusPainted(false);
        button.setBorder(new EmptyBorder(8, 12, 8, 12));
    }

    private JComponent editor() {
        JPanel form = new JPanel();
        form.setBackground(BG);
        form.setBorder(new EmptyBorder(14, 18, 14, 18));
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        JLabel heading = label("Configure this panel", 18, TEXT, Font.BOLD);
        heading.setAlignmentX(LEFT_ALIGNMENT);
        form.add(heading);
        form.add(Box.createVerticalStrut(10));
        form.add(field("PANEL TYPE", type));
        description.setAlignmentX(LEFT_ALIGNMENT);
        form.add(description);
        form.add(Box.createVerticalStrut(10));
        portfolioRow = field("PORTFOLIO VIEW", portfolioView);
        form.add(portfolioRow);
        symbolRow = field("TICKER", symbol);
        form.add(symbolRow);
        periodRow = field("HISTORY", period);
        form.add(periodRow);
        scriptRow = scriptEditor();
        form.add(scriptRow);
        modelFileRow = field("MODEL SIGNAL CSV (OPTIONAL)", modelFile);
        form.add(modelFileRow);
        form.add(Box.createVerticalStrut(4));
        JPanel shell = new JPanel(new BorderLayout());
        shell.setBackground(BG);
        shell.add(form, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(shell);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(BG);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getVerticalScrollBar().setPreferredSize(new Dimension(7, 0));
        scroll.getVerticalScrollBar().setUI(new BasicScrollBarUI() {
            @Override protected void configureScrollBarColors() {
                thumbColor = BORDER;
                trackColor = BG;
            }
            @Override protected JButton createDecreaseButton(int orientation) { return zeroButton(); }
            @Override protected JButton createIncreaseButton(int orientation) { return zeroButton(); }
            private JButton zeroButton() {
                JButton button = new JButton();
                button.setPreferredSize(new Dimension(0, 0));
                return button;
            }
        });

        JPanel footer = new JPanel(new BorderLayout(0, 4));
        footer.setBackground(BG);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER),
                new EmptyBorder(7, 18, 8, 18)));
        JButton apply = new JButton("APPLY TO PANEL");
        styleAction(apply);
        apply.addActionListener(event -> applyConfiguration());
        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 10, 0));
        actions.setOpaque(false);
        actions.add(apply);
        JButton changeMethod = new JButton("CHANGE METHOD");
        changeMethod.setFont(new Font("Segoe UI", Font.BOLD, 10));
        changeMethod.setForeground(MUTED);
        changeMethod.setBackground(BG);
        changeMethod.setBorder(new EmptyBorder(3, 0, 3, 0));
        changeMethod.addActionListener(event -> cards.show(this, "mode"));
        actions.add(changeMethod);
        footer.add(actions, BorderLayout.CENTER);
        validation.setVisible(false);
        footer.add(validation, BorderLayout.NORTH);
        JPanel editor = new JPanel(new BorderLayout());
        editor.setBackground(BG);
        editor.add(scroll, BorderLayout.CENTER);
        editor.add(footer, BorderLayout.SOUTH);
        return editor;
    }

    private JPanel field(String title, JComponent input) {
        JPanel row = new JPanel(new BorderLayout(0, 3));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));
        row.setBorder(new EmptyBorder(0, 0, 8, 0));
        row.add(label(title, 10, MUTED, Font.BOLD), BorderLayout.NORTH);
        input.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        input.setForeground(TEXT);
        input.setBackground(CARD);
        input.setPreferredSize(new Dimension(370, 28));
        row.add(input, BorderLayout.CENTER);
        return row;
    }

    private JPanel scriptEditor() {
        JPanel section = new JPanel(new BorderLayout(0, 5));
        section.setOpaque(false);
        section.setAlignmentX(LEFT_ALIGNMENT);
        JPanel top = new JPanel(new BorderLayout(7, 0));
        top.setOpaque(false);
        top.add(label("CHART SCRIPT", 10, MUTED, Font.BOLD), BorderLayout.WEST);
        JComboBox<ChartPresets.Preset> preset = new JComboBox<>(ChartPresets.Preset.values());
        preset.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        preset.setBackground(CARD);
        preset.setForeground(TEXT);
        top.add(preset, BorderLayout.CENTER);
        JButton usePreset = new JButton("USE GRAPH");
        usePreset.setFont(new Font("Segoe UI", Font.BOLD, 10));
        usePreset.setBackground(CARD);
        usePreset.setForeground(PURPLE);
        usePreset.setBorder(new EmptyBorder(4, 7, 4, 7));
        usePreset.addActionListener(event -> {
            if (lastLoadedScript != null && !chartScript.getText().equals(lastLoadedScript)
                    && JOptionPane.showConfirmDialog(this,
                    "Replace your current chart script with this graph starter?", "Use graph starter",
                    JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            String source = ChartPresets.script((ChartPresets.Preset) preset.getSelectedItem(),
                    selectedTicker.get(), watchlistTickers.get());
            chartScript.setText(source);
            chartScript.setCaretPosition(0);
            lastLoadedScript = source;
        });
        top.add(usePreset, BorderLayout.EAST);
        section.add(top, BorderLayout.NORTH);
        chartScript.setFont(new Font("Consolas", Font.PLAIN, 12));
        chartScript.setForeground(TEXT);
        chartScript.setBackground(CARD);
        chartScript.setCaretColor(PURPLE);
        chartScript.setTabSize(2);
        chartScript.setBorder(new EmptyBorder(8, 9, 8, 9));
        JScrollPane editor = new JScrollPane(chartScript);
        editor.setBorder(BorderFactory.createLineBorder(BORDER));
        editor.setPreferredSize(new Dimension(370, 235));
        section.add(editor, BorderLayout.CENTER);
        JLabel help = label("plot volume • study Field(volume) • study Indicator(name)",
                11, MUTED, Font.PLAIN);
        section.add(help, BorderLayout.SOUTH);
        section.setMaximumSize(new Dimension(Integer.MAX_VALUE, 285));
        return section;
    }

    private void updateFields() {
        PanelType selected = (PanelType) type.getSelectedItem();
        if (selected == null || portfolioRow == null) return;
        String name = selected.name();
        description.setText(selected.description());
        boolean portfolio = "Portfolio".equals(name);
        boolean stockChart = "Stock chart".equals(name);
        portfolioRow.setVisible(portfolio);
        String selectedView = String.valueOf(portfolioView.getSelectedItem());
        periodRow.setVisible(portfolio && ("Overview".equals(selectedView)
                || "Performance".equals(selectedView) || "Risk lens".equals(selectedView)));
        scriptRow.setVisible(stockChart);
        symbolRow.setVisible("Market quote".equals(name));
        modelFileRow.setVisible("Modeling board".equals(name));
        validation.setText("");
        validation.setVisible(false);
        revalidate();
        repaint();
    }

    void applyConfiguration() {
        PanelType selected = (PanelType) type.getSelectedItem();
        Choice selectedPeriod = (Choice) period.getSelectedItem();
        if (selected == null || selectedPeriod == null) return;
        if (activeForm != null && !selected.name().equals(activeForm.type())) quickNotice = "";
        String ticker = symbol.getText().trim().toUpperCase(java.util.Locale.ROOT);
        if ("Market quote".equals(selected.name())
                && !ticker.matches("[A-Z][A-Z0-9.-]{0,9}")) {
            validation.setText("Enter a stock ticker such as AAPL.");
            validation.setVisible(true);
            return;
        }
        String viewName = "Portfolio".equals(selected.name()) ? String.valueOf(portfolioView.getSelectedItem())
                : "Stock chart".equals(selected.name()) ? "Stock lab" : selected.name();
        try {
            ChartScript.Config chart = "Stock chart".equals(selected.name())
                    ? ChartScript.parse(chartScript.getText()) : null;
            Spec spec = new Spec(viewName, chart == null ? selectedPeriod.code() : chart.range(),
                    chart == null ? "none" : chart.study(),
                    chart == null ? ("Market quote".equals(selected.name()) ? ticker : "") : chart.ticker(),
                    modelFile.getText().trim(), chart == null ? "" : chart.overlays(), chart);
            JComponent next = createView.apply(spec);
            if (currentView instanceof RPortfolioPanel previous) previous.close();
            content.removeAll();
            content.add(next, JLayeredPane.DEFAULT_LAYER);
            currentView = next;
            activeForm = formState();
            if (quickOpen) {
                quickDrawer = buildQuickDrawer();
                content.add(quickDrawer, JLayeredPane.PALETTE_LAYER);
            }
            onDescriptionChanged.run();
            content.revalidate();
            content.repaint();
            validation.setText("");
            validation.setVisible(false);
            cards.show(this, "content");
        } catch (RuntimeException error) {
            validation.setText(error.getMessage() == null ? "This display could not be created." : error.getMessage());
            validation.setVisible(true);
        }
    }

    private static JLabel label(String text, int size, Color color, int weight) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("Segoe UI", weight, size));
        label.setForeground(color);
        return label;
    }

    record Spec(String kind, String period, String indicator, String symbol, String modelFile,
                String overlays, ChartScript.Config chart) {}
    record FormState(String type, String portfolioView, String period, String symbol,
                     String chartScript, String modelFile) {}
    record State(FormState draft, FormState active) {}
    private record Choice(String label, String code) {
        @Override public String toString() { return label; }
    }
    private record PanelType(String name, String description) {
        @Override public String toString() { return name; }
    }
}

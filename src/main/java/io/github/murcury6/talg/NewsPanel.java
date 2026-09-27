package io.github.murcury6.talg;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

/** World events are collected independently of positions, tickers, sentiment, and trade rules. */
final class NewsPanel extends JPanel {
    static final Color BG = new Color(20, 18, 27), CARD = new Color(34, 30, 45),
            TEXT = new Color(232, 229, 239), MUTED = new Color(153, 149, 164), PURPLE = new Color(177, 146, 245);
    private final NewsService service;
    private List<NewsService.Source> sources = List.of();
    private NewsService.Snapshot snapshot = new NewsService.Snapshot(List.of(), List.of());
    private List<NewsService.Article> visible = List.of();
    private final JTextField search = new JTextField(25);
    private final JComboBox<String> publishers = new JComboBox<>(new String[]{"All sources"});
    private final JComboBox<String> stocks = new JComboBox<>(new String[]{"All stocks / world"});
    private final JComboBox<String> categories = new JComboBox<>(new String[]{"All categories", "World", "Business", "Stocks & ETFs", "Press releases", "Politics", "Technology", "Healthcare", "Science", "Official releases"});
    private final JComboBox<String> topics = new JComboBox<>(new String[]{"All topics", "Conflict & security", "Trade & policy", "Energy", "Technology", "Rates & economy", "Company results", "Healthcare"});
    private final JComboBox<String> age = new JComboBox<>(new String[]{"Past 24 hours", "Past 7 days", "All indexed"});
    private final JLabel status = label("Saved news is ready to read. Refresh checks for new articles.", MUTED);
    private final JLabel counts = label("No collected headlines yet", MUTED);
    private final javax.swing.JTextPane details = new javax.swing.JTextPane();
    private final JButton refresh = button("Refresh news"), open = button("Read full article ↗"), edit = button("Edit feed configuration");
    private final JButton filterToggle = button("Filters"), clear = button("Reset filters"), articleInfo = button("Source details");
    private final JCheckBox auto = new JCheckBox("Update every minute", true);
    private final DefaultTableModel headlines = model("Latest articles");
    private final DefaultTableModel sourceRows = model("SOURCE / FEED", "CATEGORY", "ENABLED", "LAST SUCCESS / LOCAL", "NEWEST ITEM / LOCAL", "ITEMS", "STATUS");
    private final JTable table = table(headlines), sourceTable = table(sourceRows);
    private final Timer timer;
    private final Timer liveIndexTimer;
    private final Timer debounce;
    private JScrollPane headlineScroll, readerScroll;
    private int headlineWidth;
    private NewsService.Article reading;
    private final Map<String, javax.swing.JDialog> toolWindows = new HashMap<>();
    private boolean rebuildingRows;
    private long lastStreamIndex;
    private SwingWorker<?, ?> worker;
    private boolean started, updatingFilters, stopped;

    NewsPanel(Path root) {
        super(new BorderLayout(0, 6));
        service = new NewsService(root);
        setBackground(BG);
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        add(headlinesView(), BorderLayout.CENTER);
        JPanel utility = panel(new BorderLayout(8, 0));
        utility.setPreferredSize(new Dimension(0, 30));
        JButton menu = button("News ▾");
        javax.swing.JPopupMenu options = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem sourcesItem = new javax.swing.JMenuItem("Sources & coverage…");
        sourcesItem.addActionListener(event -> showTool("Sources & coverage", this::sourcesView));
        options.add(sourcesItem);
        javax.swing.JMenuItem financialsItem = new javax.swing.JMenuItem("Company financials…");
        financialsItem.addActionListener(event -> showTool("Company financials", () -> new CompanyFinancialsPanel(root)));
        options.add(financialsItem);
        javax.swing.JMenuItem collection = new javax.swing.JMenuItem("Open saved collection");
        collection.addActionListener(event -> openCollection()); options.add(collection);
        options.addSeparator();
        options.add(articleInfo);
        auto.setOpaque(false); auto.setForeground(TEXT); options.add(auto);
        options.addSeparator(); options.add(status);
        menu.addActionListener(event -> options.show(menu, 0, menu.getHeight()));
        utility.add(menu, BorderLayout.WEST);
        JPanel searchRow = panel(new BorderLayout(6, 0));
        JLabel searchLabel = label("Search news", TEXT); searchLabel.setLabelFor(search);
        search.getAccessibleContext().setAccessibleName("Search news");
        searchRow.add(searchLabel, BorderLayout.WEST); searchRow.add(search, BorderLayout.CENTER);
        utility.add(searchRow, BorderLayout.CENTER);
        JPanel actions = panel(new java.awt.GridBagLayout());
        java.awt.GridBagConstraints cell = new java.awt.GridBagConstraints();
        cell.insets = new java.awt.Insets(0, 3, 0, 3); cell.fill = java.awt.GridBagConstraints.VERTICAL;
        actions.add(filterToggle, cell); actions.add(refresh, cell); actions.add(open, cell); actions.add(counts, cell);
        counts.setPreferredSize(new Dimension(85, 24));
        utility.add(actions, BorderLayout.EAST);
        add(utility, BorderLayout.NORTH);
        status.addPropertyChangeListener("text", event -> {
            status.setToolTipText(status.getText()); refresh.setToolTipText(status.getText());
            refresh.setForeground(status.getText().contains("failed") || status.getText().startsWith("Cannot")
                    ? new Color(255, 173, 115) : PURPLE);
        });
        refresh.addActionListener(event -> refresh());
        open.addActionListener(event -> openSelected());
        open.setEnabled(false);
        articleInfo.setEnabled(false);
        articleInfo.addActionListener(event -> showArticleInfo());
        for (var box : List.of(publishers, stocks, categories, topics, age)) {
            box.setBackground(CARD); box.setForeground(TEXT);
            box.addActionListener(event -> { if (!updatingFilters) filter(); });
        }
        age.setSelectedIndex(1);
        search.setBackground(CARD); search.setForeground(TEXT); search.setCaretColor(TEXT);
        search.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(70, 62, 87)),
                BorderFactory.createEmptyBorder(0, 7, 0, 7)));
        search.setToolTipText("Search headlines and summaries. Use the stock selector for dedicated ticker-feed results.");
        debounce = new Timer(180, event -> filter()); debounce.setRepeats(false);
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { debounce.restart(); }
            public void removeUpdate(DocumentEvent event) { debounce.restart(); }
            public void changedUpdate(DocumentEvent event) { debounce.restart(); }
        });
        table.getSelectionModel().addListSelectionListener(event -> { if (!rebuildingRows && !event.getValueIsAdjusting()) select(); });
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) { if (event.getClickCount() == 2) openSelected(); }
        });
        timer = new Timer(60_000, event -> { if (auto.isSelected()) refresh(); });
        liveIndexTimer = new Timer(2000, event -> {
            if (stopped || worker != null) return;
            try {
                Path stream = root.resolve("work/news/stream-articles.json");
                long updated = Files.exists(stream) ? Files.getLastModifiedTime(stream).toMillis() : 0;
                if (updated == lastStreamIndex) return;
                lastStreamIndex = updated;
                worker = new SwingWorker<NewsService.Snapshot, Void>() {
                    protected NewsService.Snapshot doInBackground() throws Exception { return service.load(); }
                    protected void done() {
                        try { snapshot = get(); rebuild(); } catch (Exception error) { lastStreamIndex = 0; }
                        finally { worker = null; }
                    }
                };
                worker.execute();
            } catch (Exception ignored) { }
        });
        try { sources = service.sources(); updateSources(); }
        catch (Exception error) { status.setText("Source configuration: " + NewsService.errorMessage(error)); }
    }

    private JPanel headlinesView() {
        JPanel view = panel(new BorderLayout());
        JPanel filters = panel(new java.awt.GridLayout(0, 1, 0, 9));
        filters.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        filters.add(filterField("Published", age));
        filters.add(filterField("Source", publishers));
        filters.add(filterField("Category", categories));
        filters.add(filterField("Stock / ETF", stocks));
        filters.add(filterField("Topic (keyword match)", topics));
        filters.add(clear);
        javax.swing.JPopupMenu popup = new javax.swing.JPopupMenu();
        filters.setPreferredSize(new Dimension(290, 365)); popup.add(filters);
        filterToggle.addActionListener(event -> popup.show(filterToggle, 0, filterToggle.getHeight()));
        clear.addActionListener(event -> {
            updatingFilters = true;
            search.setText(""); publishers.setSelectedIndex(0); stocks.setSelectedIndex(0);
            categories.setSelectedIndex(0); topics.setSelectedIndex(0); age.setSelectedIndex(1);
            updatingFilters = false; debounce.stop(); filter();
        });
        // Each row gives its space to the headline; source and date sit underneath it.
        table.setAutoCreateRowSorter(false); table.setRowSorter(null);
        table.setTableHeader(null); table.setRowHeight(66); table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setDefaultRenderer(Object.class, new HeadlineRenderer());
        table.getAccessibleContext().setAccessibleName("News headlines, newest first");
        headlineScroll = newsScroll(table);
        table.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent event) {
                if (headlineWidth != table.getWidth()) updateRowHeights();
            }
        });
        headlineScroll.setMinimumSize(new Dimension(330, 180));
        JPanel list = panel(new BorderLayout(0, 4));
        list.add(headlineScroll, BorderLayout.CENTER);

        details.setEditable(false); details.setBackground(CARD); details.setForeground(TEXT);
        details.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        details.getAccessibleContext().setAccessibleName("Selected article preview");
        JPanel reader = panel(new BorderLayout(0, 4));
        reader.setMinimumSize(new Dimension(280, 180));
        readerScroll = newsScroll(details);
        reader.add(readerScroll, BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, list, reader);
        split.setResizeWeight(0.60); split.setDividerSize(7); split.setBorder(null); split.setBackground(BG);
        split.setContinuousLayout(true);
        // Proportional sizing also works before the component has been shown.
        list.setPreferredSize(new Dimension(650, 500)); reader.setPreferredSize(new Dimension(420, 500));
        view.add(split, BorderLayout.CENTER);
        setPreview(null);
        return view;
    }

    private JPanel filterField(String name, javax.swing.JComponent control) {
        JPanel field = panel(new BorderLayout(0, 3));
        JLabel caption = label(name, MUTED); caption.setLabelFor(control);
        control.getAccessibleContext().setAccessibleName(name);
        field.add(caption, BorderLayout.NORTH); field.add(control, BorderLayout.CENTER);
        return field;
    }

    private void updateRowHeights() {
        headlineWidth = table.getWidth();
        var metrics = table.getFontMetrics(new Font("Segoe UI", Font.PLAIN, 14));
        for (int row = 0; row < visible.size(); row++) {
            int lines = metrics.stringWidth(visible.get(row).title()) <= Math.max(100, headlineWidth - 24) ? 1 : 2;
            int height = metrics.getHeight() * lines + 34;
            if (table.getRowHeight(row) != height) table.setRowHeight(row, height);
        }
    }

    private static JScrollPane newsScroll(java.awt.Component content) {
        JScrollPane pane = scroll(content);
        for (var bar : List.of(pane.getVerticalScrollBar(), pane.getHorizontalScrollBar())) {
            bar.setUI(new javax.swing.plaf.basic.BasicScrollBarUI() {
                @Override protected void configureScrollBarColors() { thumbColor = new Color(86, 76, 104); trackColor = CARD; }
                @Override protected JButton createDecreaseButton(int orientation) { return hiddenButton(); }
                @Override protected JButton createIncreaseButton(int orientation) { return hiddenButton(); }
                private JButton hiddenButton() { JButton b = new JButton(); b.setPreferredSize(new Dimension(0, 0)); return b; }
            });
            bar.setUnitIncrement(24);
        }
        pane.getVerticalScrollBar().setPreferredSize(new Dimension(9, 0));
        pane.getHorizontalScrollBar().setPreferredSize(new Dimension(0, 9));
        return pane;
    }

    private void updateFilterControls() {
        int count = (publishers.getSelectedIndex() > 0 ? 1 : 0) + (stocks.getSelectedIndex() > 0 ? 1 : 0)
                + (categories.getSelectedIndex() > 0 ? 1 : 0) + (topics.getSelectedIndex() > 0 ? 1 : 0)
                + (age.getSelectedIndex() != 1 ? 1 : 0);
        filterToggle.setText("Filters" + (count == 0 ? "" : " (" + count + ")"));
        clear.setEnabled(count > 0 || !search.getText().isBlank() || age.getSelectedIndex() != 1);
    }

    private final class HeadlineRenderer extends JPanel implements javax.swing.table.TableCellRenderer {
        private final JTextArea title = new JTextArea();
        private final JLabel meta = label("", MUTED);
        HeadlineRenderer() {
            super(new BorderLayout(0, 3));
            setBorder(BorderFactory.createEmptyBorder(7, 10, 7, 10));
            title.setLineWrap(true); title.setWrapStyleWord(true); title.setOpaque(false);
            title.setFont(new Font("Segoe UI", Font.PLAIN, 14)); title.setForeground(TEXT);
            title.setEditable(false); meta.putClientProperty("html.disable", Boolean.TRUE);
            add(title, BorderLayout.CENTER); add(meta, BorderLayout.SOUTH);
        }
        @Override public java.awt.Component getTableCellRendererComponent(JTable t, Object value,
                boolean selected, boolean focus, int row, int column) {
            var article = (NewsService.Article) value;
            title.setText(article.title());
            meta.setText(article.publisher() + " · " + publishedLabel(article) + " · " + article.category());
            setBackground(selected ? new Color(59, 47, 80) : CARD);
            setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, selected ? 3 : 0, 1, 0,
                    selected ? PURPLE : BG), BorderFactory.createEmptyBorder(6, selected ? 7 : 10, 6, 10)));
            // JTable paints renderers without a normal validate pass.
            setSize(t.getColumnModel().getColumn(column).getWidth(), t.getRowHeight(row)); doLayout();
            return this;
        }
    }

    private static String publishedLabel(NewsService.Article article) {
        if (article.publishedAt().isBlank()) return "Date unavailable";
        return local(article.publishedAt()) + (article.uncertainDate() ? " (date uncertain)" : "");
    }

    private void showTool(String title, java.util.function.Supplier<JPanel> content) {
        var existing = toolWindows.get(title);
        if (existing != null && existing.isDisplayable()) { existing.toFront(); return; }
        var dialog = new javax.swing.JDialog(javax.swing.SwingUtilities.getWindowAncestor(this), title,
                java.awt.Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        dialog.add(content.get()); dialog.setSize(1000, 620); dialog.setLocationRelativeTo(this);
        toolWindows.put(title, dialog); dialog.setVisible(true);
    }

    private JPanel sourcesView() {
        JPanel view = panel(new BorderLayout(0, 8));
        JTextArea explanation = area();
        explanation.setText("World feeds and dedicated stock/ETF feeds are collected together, independently of holdings.\nStock associations come from the provider's ticker feed; related-market stories can appear. Use ADD STOCK to expand coverage.\nGoogle and Yahoo discovery reflect their providers' selection. Failures retain previously collected articles.\nPublic RSS/Atom feeds are polled; this is not a streaming newswire or a complete historical archive.");
        explanation.setRows(4); view.add(explanation, BorderLayout.NORTH);
        view.add(scroll(sourceTable), BorderLayout.CENTER);
        sourceTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        int[] widths = {230, 125, 70, 155, 155, 65, 260};
        for (int i = 0; i < widths.length; i++) sourceTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        JPanel bottom = panel(new FlowLayout(FlowLayout.LEFT));
        if (edit.getActionListeners().length == 0) edit.addActionListener(event -> editSources()); bottom.add(edit);
        JButton addStock = button("ADD STOCK");
        addStock.addActionListener(event -> addStock()); bottom.add(addStock);
        JButton feed = button("OPEN FEED");
        feed.addActionListener(event -> {
            int selected = sourceTable.getSelectedRow();
            if (selected >= 0) browse(sources.get(sourceTable.convertRowIndexToModel(selected)).url());
        });
        bottom.add(feed); view.add(bottom, BorderLayout.SOUTH);
        return view;
    }

    void activate() {
        if (started) return;
        started = true; refresh.setEnabled(false);
        worker = new SwingWorker<NewsService.Snapshot, Void>() {
            protected NewsService.Snapshot doInBackground() throws Exception { return service.load(); }
            protected void done() {
                if (stopped) return;
                try { snapshot = get(); rebuild(); }
                catch (Exception error) { status.setText("Cache unavailable; collecting fresh feeds. " + NewsService.errorMessage(error)); }
                worker = null; refresh.setEnabled(true); timer.start(); liveIndexTimer.start(); refresh();
            }
        };
        worker.execute();
    }

    void stop() {
        stopped = true; timer.stop(); liveIndexTimer.stop(); debounce.stop();
        if (worker != null) worker.cancel(true);
        toolWindows.values().forEach(javax.swing.JDialog::dispose);
    }

    private void refresh() {
        if (worker != null || stopped) return;
        try { sources = service.sources(); }
        catch (Exception error) { status.setText("Cannot load sources: " + NewsService.errorMessage(error)); return; }
        refresh.setEnabled(false); edit.setEnabled(false);
        status.setText("Collecting free news feeds…");
        worker = new SwingWorker<NewsService.Snapshot, String>() {
            protected NewsService.Snapshot doInBackground() throws Exception {
                return service.collect(message -> publish(message));
            }
            protected void process(List<String> messages) { status.setText(messages.getLast()); }
            protected void done() {
                try {
                    snapshot = get(); rebuild();
                    Set<String> enabled = sources.stream().filter(NewsService.Source::enabled).map(NewsService.Source::id).collect(java.util.stream.Collectors.toSet());
                    long success = snapshot.statuses().stream().filter(s -> enabled.contains(s.sourceId()) && (s.state().equals("OK") || s.state().equals("Empty feed"))).count();
                    status.setText(success + "/" + enabled.size() + " feeds responded • Checked " + local(Instant.now().toString())
                            + " • " + (enabled.size() - success) + " unavailable (see Sources & coverage)");
                } catch (Exception error) { status.setText("Refresh failed; previous collection retained. " + NewsService.errorMessage(error)); }
                finally { worker = null; refresh.setEnabled(true); edit.setEnabled(true); }
            }
        };
        worker.execute();
    }

    private void rebuild() {
        updatingFilters = true;
        String stock = String.valueOf(stocks.getSelectedItem());
        stocks.removeAllItems(); stocks.addItem("All stocks / world");
        java.util.stream.Stream.concat(sources.stream().filter(NewsService.Source::enabled).map(NewsService.Source::symbol),
                        snapshot.articles().stream().flatMap(article -> article.symbols().stream()))
                .filter(s -> !s.isEmpty()).distinct().sorted().forEach(stocks::addItem);
        stocks.setSelectedItem(stock);
        if (stocks.getSelectedIndex() < 0) stocks.setSelectedIndex(0);
        String selected = String.valueOf(publishers.getSelectedItem());
        publishers.removeAllItems(); publishers.addItem("All sources");
        snapshot.articles().stream().map(NewsService.Article::publisher).distinct().sorted().forEach(publishers::addItem);
        publishers.setSelectedItem(selected);
        if (publishers.getSelectedIndex() < 0) publishers.setSelectedIndex(0);
        updatingFilters = false;
        filter(); updateSources();
    }

    private void filter() {
        if (updatingFilters || stopped) return;
        var previous = reading;
        var previousScroll = headlineScroll.getViewport().getViewPosition();
        int previewScroll = readerScroll.getVerticalScrollBar().getValue();
        String source = String.valueOf(publishers.getSelectedItem()), category = String.valueOf(categories.getSelectedItem());
        Instant cutoff = age.getSelectedIndex() == 0 ? Instant.now().minus(Duration.ofHours(24))
                : age.getSelectedIndex() == 1 ? Instant.now().minus(Duration.ofDays(7)) : Instant.MIN;
        Set<String> enabled = sources.stream().filter(NewsService.Source::enabled).map(NewsService.Source::id).collect(java.util.stream.Collectors.toSet());
        String stock = String.valueOf(stocks.getSelectedItem());
        Set<String> stockFeeds = sources.stream().filter(s -> s.symbol().equals(stock)).map(NewsService.Source::id).collect(java.util.stream.Collectors.toSet());
        Map<String, NewsService.Article> unique = new LinkedHashMap<>();
        for (var article : snapshot.articles()) {
            if ((!enabled.contains(article.sourceId()) && !article.sourceId().equals("alpaca-news"))
                    || (stocks.getSelectedIndex() > 0 && !stockFeeds.contains(article.sourceId()) && !article.symbols().contains(stock))
                    || (!source.equals("All sources") && !source.equals(article.publisher()))
                    || (!category.equals("All categories") && !category.equals(article.category()))
                    || !NewsService.matches(article, search.getText(), String.valueOf(topics.getSelectedItem()))) continue;
            try { if (Instant.parse(article.sortTime()).isBefore(cutoff)) continue; } catch (RuntimeException error) { continue; }
            unique.putIfAbsent(article.publisher() + "\n" + NewsService.canonicalUrl(article.url()), article);
        }
        visible = new ArrayList<>(unique.values());
        visible.sort(java.util.Comparator.comparing(NewsService.Article::uncertainDate)
                .thenComparing(java.util.Comparator.comparing((NewsService.Article a) -> Instant.parse(a.sortTime())).reversed()));
        rebuildingRows = true;
        headlines.setRowCount(0);
        for (var article : visible) headlines.addRow(new Object[]{article});
        updateRowHeights();
        counts.setText(visible.size() + " articles");
        counts.setToolTipText("Newest first · " + visible.stream().map(NewsService.Article::publisher).distinct().count()
                + " publishers · " + String.valueOf(age.getSelectedItem()));
        updateFilterControls();
        int selected = -1;
        if (previous != null) for (int i = 0; i < visible.size(); i++) {
            if (sameStory(previous, visible.get(i))) { selected = i; break; }
        }
        boolean retained = selected >= 0;
        if (selected < 0 && !visible.isEmpty()) selected = 0;
        if (selected >= 0) table.setRowSelectionInterval(selected, selected);
        rebuildingRows = false;
        select();
        if (retained) {
            headlineScroll.getViewport().setViewPosition(previousScroll);
            if (previous.equals(reading)) readerScroll.getVerticalScrollBar().setValue(previewScroll);
        } else {
            headlineScroll.getViewport().setViewPosition(new java.awt.Point());
        }
    }

    private static boolean sameStory(NewsService.Article a, NewsService.Article b) {
        return a.publisher().equals(b.publisher()) && NewsService.canonicalUrl(a.url()).equals(NewsService.canonicalUrl(b.url()));
    }

    private void updateSources() {
        Map<String, NewsService.FeedStatus> statuses = new HashMap<>();
        snapshot.statuses().forEach(value -> statuses.put(value.sourceId(), value));
        sourceRows.setRowCount(0);
        for (var source : sources) {
            var result = statuses.get(source.id());
            String newest = snapshot.articles().stream().filter(a -> a.sourceId().equals(source.id()))
                    .map(NewsService.Article::publishedAt).filter(s -> !s.isBlank()).max(String::compareTo).orElse("");
            sourceRows.addRow(new Object[]{source.toString(), source.category(), source.enabled() ? "Yes" : "No",
                    result == null || result.lastSuccess().isBlank() ? "Never" : local(result.lastSuccess()),
                    newest.isBlank() ? "Unknown" : local(newest),
                    result == null ? 0 : result.count(), !source.enabled() ? "Disabled" : result == null ? "Not checked" : result.state()
                    + (result.state().equals("OK") && !newest.isBlank() && Instant.parse(newest).isBefore(Instant.now().minus(Duration.ofDays(7)))
                    ? " • latest item over 7 days old" : "")});
        }
    }

    private NewsService.Article selectedArticle() {
        int row = table.getSelectedRow();
        return row < 0 || table.convertRowIndexToModel(row) >= visible.size() ? null : visible.get(table.convertRowIndexToModel(row));
    }
    private void select() {
        var article = selectedArticle();
        open.setEnabled(article != null); articleInfo.setEnabled(article != null);
        if (article != null && article.equals(reading)) return;
        reading = article; setPreview(article);
    }

    private void setPreview(NewsService.Article article) {
        details.setText("");
        if (article == null) {
            appendPreview(snapshot.articles().isEmpty() ? "No saved articles yet\n" : "No matching articles\n", 19, TEXT, true);
            appendPreview(snapshot.articles().isEmpty() ? "Use Refresh news to collect headlines. Feed status is in News → Sources & coverage."
                    : "Try a different search or use Filters → Reset filters.", 14, MUTED, false);
            return;
        }
        appendPreview(article.title() + "\n", 21, TEXT, true);
        appendPreview(article.publisher() + " · " + article.category() + "\n" + publishedLabel(article) + "\n\n", 12, MUTED, false);
        String summary = article.summary().isBlank() || article.summary().strip().equals(article.title().strip())
                ? "This source provides only a headline. Use Read full article to open the story."
                : article.summary();
        appendPreview(summary + "\n\n", 15, TEXT, false);
        if (!article.summary().isBlank() && !article.summary().strip().equals(article.title().strip()))
            appendPreview("Publisher excerpt · Read full article opens the original source.", 12, MUTED, false);
        details.setCaretPosition(0);
    }

    private void appendPreview(String text, int size, Color color, boolean bold) {
        var style = new javax.swing.text.SimpleAttributeSet();
        javax.swing.text.StyleConstants.setFontFamily(style, "Segoe UI");
        javax.swing.text.StyleConstants.setFontSize(style, size);
        javax.swing.text.StyleConstants.setForeground(style, color);
        javax.swing.text.StyleConstants.setBold(style, bold);
        javax.swing.text.StyleConstants.setSpaceAbove(style, 3);
        javax.swing.text.StyleConstants.setSpaceBelow(style, 6);
        var document = details.getStyledDocument();
        try {
            int start = document.getLength(); document.insertString(start, text, style);
            document.setParagraphAttributes(start, text.length(), style, false);
        } catch (javax.swing.text.BadLocationException impossible) { throw new IllegalStateException(impossible); }
    }

    private void showArticleInfo() {
        var article = selectedArticle();
        if (article == null) return;
        String feed = sources.stream().filter(s -> s.id().equals(article.sourceId())).map(NewsService.Source::toString).findFirst().orElse(article.sourceId());
        JTextArea info = area();
        info.setText("Publisher: " + article.publisher() + "\nFeed: " + feed
                + "\nPublished: " + publishedLabel(article)
                + "\nFirst collected: " + local(article.firstSeen()) + "\nLast seen: " + local(article.lastSeen())
                + (article.uncertainDate() ? "\nPublication time is missing or later than collection; sorted after dated articles." : "")
                + "\n\n" + article.url());
        var pane = scroll(info); pane.setPreferredSize(new Dimension(600, 260));
        JOptionPane.showMessageDialog(this, pane, "Source details", JOptionPane.PLAIN_MESSAGE);
    }
    private void openSelected() { var article = selectedArticle(); if (article != null) browse(article.url()); }
    private void browse(String url) {
        try { if (NewsService.webUrl(url)) Desktop.getDesktop().browse(URI.create(url)); }
        catch (Exception error) { JOptionPane.showMessageDialog(this, "Open this address in your browser:\n" + url); }
    }
    private void openCollection() {
        try { Files.createDirectories(service.archivePath()); Desktop.getDesktop().open(service.archivePath().toAbsolutePath().toFile()); }
        catch (Exception error) { JOptionPane.showMessageDialog(this, service.archivePath().toAbsolutePath().toString()); }
    }
    private void addStock() {
        String ticker = JOptionPane.showInputDialog(this, "Stock or ETF ticker (for example AAPL, NVDA, SPY):", "Add stock news", JOptionPane.PLAIN_MESSAGE);
        if (ticker == null) return;
        try {
            var added = NewsService.stockSource(ticker);
            var edited = new ArrayList<>(service.sources());
            edited.removeIf(source -> source.id().equals(added.id()));
            edited.add(added); service.saveSources(edited); sources = edited; rebuild(); refresh();
        } catch (Exception error) { JOptionPane.showMessageDialog(this, NewsService.errorMessage(error), "Stock was not added", JOptionPane.ERROR_MESSAGE); }
    }
    private void editSources() {
        try {
            JTextArea editor = area(); editor.setFont(new Font("Consolas", Font.PLAIN, 12));
            editor.setLineWrap(false); editor.setEditable(true);
            editor.setText(NewsService.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(sources));
            JScrollPane pane = scroll(editor); pane.setPreferredSize(new Dimension(860, 530));
            if (JOptionPane.showConfirmDialog(this, pane, "Free RSS / Atom feeds — edit enabled or add a feed (maximum 500)",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            List<NewsService.Source> edited = NewsService.JSON.readValue(editor.getText(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
            service.saveSources(edited); sources = edited; rebuild(); refresh();
        } catch (Exception error) { JOptionPane.showMessageDialog(this, NewsService.errorMessage(error), "Feeds were not saved", JOptionPane.ERROR_MESSAGE); }
    }

    static String local(String instant) {
        try { return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(instant)); }
        catch (RuntimeException error) { return "Unknown"; }
    }
    static JPanel panel(java.awt.LayoutManager layout) { JPanel panel = new JPanel(layout); panel.setBackground(BG); return panel; }
    static JLabel label(String value, Color color) { JLabel label = new JLabel(value); label.setForeground(color); label.setFont(new Font("Segoe UI", Font.PLAIN, 12)); return label; }
    static JButton button(String text) { JButton button = new JButton(text); button.setBackground(CARD); button.setForeground(PURPLE); button.setFocusPainted(false); return button; }
    static JTextArea area() { JTextArea area = new JTextArea(); area.setEditable(false); area.setLineWrap(true); area.setWrapStyleWord(true); area.setBackground(CARD); area.setForeground(TEXT); area.setFont(new Font("Segoe UI", Font.PLAIN, 13)); area.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12)); return area; }
    static DefaultTableModel model(String... columns) { return new DefaultTableModel(columns, 0) { @Override public boolean isCellEditable(int row, int col) { return false; } }; }
    static JTable table(DefaultTableModel model) {
        JTable table = new JTable(model); table.setBackground(CARD); table.setForeground(TEXT);
        table.setGridColor(BG); table.setRowHeight(29); table.setFillsViewportHeight(true);
        table.setSelectionBackground(new Color(65, 50, 88)); table.setSelectionForeground(TEXT);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); table.setAutoCreateRowSorter(true);
        table.getTableHeader().setBackground(CARD); table.getTableHeader().setForeground(PURPLE);
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override public java.awt.Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int column) {
                putClientProperty("html.disable", Boolean.TRUE);
                var component = super.getTableCellRendererComponent(t, value, selected, focus, row, column);
                setToolTipText(String.valueOf(value)); return component;
            }
        });
        return table;
    }
    static JScrollPane scroll(java.awt.Component content) {
        JScrollPane pane = new JScrollPane(content);
        pane.setBorder(BorderFactory.createLineBorder(new Color(70, 62, 87))); pane.getViewport().setBackground(CARD);
        if (content instanceof JTable table) pane.setColumnHeaderView(table.getTableHeader());
        return pane;
    }
}

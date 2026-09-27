package io.github.murcury6.talg;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;

/** Market desk with persistent research layouts and explicit, confirmed order submission. */
public final class TalgDesktop extends JFrame {
    private static final Color BG = new Color(20, 18, 27);
    private static final Color SIDEBAR = new Color(27, 24, 37);
    private static final Color CARD = new Color(34, 30, 45);
    private static final Color CARD_LIGHT = new Color(43, 37, 59);
    private static final Color BORDER = new Color(70, 62, 87);
    private static final Color WHITE = new Color(232, 229, 239);
    private static final Color MUTED = new Color(153, 149, 164);
    private static final Color ACCENT = new Color(177, 146, 245);
    private final DefaultListModel<String> symbols = new DefaultListModel<>();
    private final JList<String> watchlist = new JList<>(symbols);
    private final CardLayout cards = new CardLayout();
    private final JPanel cardHost = new JPanel(cards);
    private final NavButton displayNav = new NavButton("DESK");
    private final NavButton indicatorsNav = new NavButton("INDICATORS");
    private final NavButton tradingNav = new NavButton("TRADE");
    private final NavButton modelsNav = new NavButton("MODEL");
    private final NavButton newsNav = new NavButton("NEWS");
    private final NavButton stocksNav = new NavButton("STOCKS");
    private final NavButton settingsNav = new NavButton("SETTINGS");
    private final JLabel sidebarStatus = text("●", 18, MUTED, Font.BOLD);
    private final JLabel status = text("Connect Alpaca in Settings to load account and market data", 12, MUTED, Font.PLAIN);
    private final JLabel backfillStatus = text("", 12, MUTED, Font.PLAIN);
    private final JTextField tickerField = new JTextField();
    private final JPasswordField keyField = new JPasswordField();
    private final JPasswordField secretField = new JPasswordField();
    private final JComboBox<String> feedBox = new JComboBox<>(new String[]{"iex", "sip", "delayed_sip"});
    private final JComboBox<String> accountBox = new JComboBox<>(new String[]{"Paper account", "Live account"});
    private final JTextField refreshField = new JTextField("30");
    private final JLabel credentialsNotice = text("Keys will be encrypted for this Windows user when you connect.",
            12, MUTED, Font.PLAIN);
    private final CredentialStore credentialStore = new CredentialStore();
    private final DeskProfileStore profileStore = new DeskProfileStore();
    private final JComboBox<String> profileSelector = new JComboBox<>();
    private final JLabel profileSaveStatus = text("●", 12, MUTED, Font.BOLD);
    private CredentialStore.Saved savedCredentials;
    private String activeProfile;
    private String lastSavedProfileScript;
    private String lastDiskProfileSource;
    private boolean changingProfile;
    private Timer profileSaveTimer;
    private final List<ConfigurablePanel> displayPanels = new ArrayList<>();
    private ModularWorkspace workspace;
    private IndicatorStudioPanel indicatorPanel;
    private TradingPanel tradingPanel;
    private ModelsPanel modelsPanel;
    private ModelsPanel newsPanel;
    private StockSelectionPanel stockSelectionPanel;
    private AlpacaSettings settings;
    private Timer refreshTimer;

    public TalgDesktop() {
        super("TALG  |  Market Research");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(1100, 700));
        setSize(1440, 920);
        setLocationRelativeTo(null);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { closeWithProfileSave(); }
            @Override public void windowClosed(WindowEvent e) {
                if (refreshTimer != null) refreshTimer.stop();
                if (profileSaveTimer != null) profileSaveTimer.stop();
                if (newsPanel != null) newsPanel.close();
                if (tradingPanel != null) tradingPanel.closePaperTest();
                if (modelsPanel != null) modelsPanel.close();
                if (stockSelectionPanel != null) stockSelectionPanel.close();
            }
        });

        try {
            savedCredentials = credentialStore.load().orElse(null);
            if (savedCredentials != null) {
                settings = savedCredentials.settings();
                feedBox.setSelectedItem(savedCredentials.settings().feed());
                refreshField.setText(Integer.toString(savedCredentials.settings().refreshSeconds()));
                accountBox.setSelectedIndex("live".equals(savedCredentials.mode()) ? 1 : 0);
                credentialsNotice.setText("Encrypted keys saved for this Windows user. Leave both key fields blank to reuse.");
            }
        } catch (IOException error) {
            credentialsNotice.setText("Saved keys could not be unlocked here; enter them again in Settings.");
        }
        if (savedCredentials == null && !System.getenv().getOrDefault("APCA_API_KEY_ID", "").isBlank()
                && !System.getenv().getOrDefault("APCA_API_SECRET_KEY", "").isBlank()) {
            credentialsNotice.setText("Alpaca keys are available from the Windows environment. Fields stay empty.");
        }
        String envFeed = System.getenv().getOrDefault("APCA_API_DATA_FEED", "iex");
        if (savedCredentials == null
                && ("iex".equals(envFeed) || "sip".equals(envFeed) || "delayed_sip".equals(envFeed))) {
            feedBox.setSelectedItem(envFeed);
        }

        for (String starter : new String[]{"AAPL", "MSFT", "NVDA"}) symbols.addElement(starter);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        root.add(sidebar(), BorderLayout.WEST);
        root.add(mainArea(), BorderLayout.CENTER);
        setContentPane(root);
        watchlist.setSelectedIndex(0);
        initializeProfiles();
        profileSaveTimer = new Timer(2000, event -> saveProfileQuietly());
        profileSaveTimer.start();
        if (settings != null) {
            startRefreshTimer();
            sidebarStatus.setForeground(ACCENT);
            status.setText("Saved Alpaca settings restored; desk panels request fresh data");
        }
        showTab("display");
    }

    private JPanel sidebar() {
        JPanel side = new JPanel();
        side.setBackground(SIDEBAR);
        side.setPreferredSize(new Dimension(62, 0));
        side.setBorder(new EmptyBorder(11, 3, 9, 3));
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));

        JLabel mark = text("T", 21, WHITE, Font.BOLD);
        mark.setAlignmentX(CENTER_ALIGNMENT);
        side.add(mark);
        side.add(Box.createVerticalStrut(14));

        indicatorsNav.setToolTipText("Indicators: create, test, and reuse calculation blocks");
        newsNav.setToolTipText("News / Info: world events, company news, financials, and source coverage");
        modelsNav.setToolTipText("Trading model");
        for (NavButton nav : new NavButton[]{displayNav, indicatorsNav, tradingNav, modelsNav, newsNav, stocksNav, settingsNav}) {
            nav.setAlignmentX(CENTER_ALIGNMENT);
            nav.setMaximumSize(new Dimension(56, 40));
            side.add(nav);
            side.add(Box.createVerticalStrut(4));
        }
        displayNav.addActionListener(e -> showTab("display"));
        indicatorsNav.addActionListener(e -> showTab("indicators"));
        tradingNav.addActionListener(e -> showTab("trading"));
        modelsNav.addActionListener(e -> showTab("models"));
        newsNav.addActionListener(e -> showTab("news"));
        stocksNav.addActionListener(e -> showTab("stocks"));
        settingsNav.addActionListener(e -> showTab("settings"));

        NavButton addPanel = new NavButton("+");
        addPanel.setFont(new Font("Segoe UI", Font.BOLD, 22));
        addPanel.setForeground(ACCENT);
        addPanel.setToolTipText("Add a panel to the display");
        addPanel.setAlignmentX(CENTER_ALIGNMENT);
        addPanel.setMaximumSize(new Dimension(56, 38));
        addPanel.addActionListener(e -> {
            showTab("display");
            workspace.addPanel();
        });
        side.add(addPanel);
        NavButton home = new NavButton("⌂");
        home.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 19));
        home.setForeground(MUTED);
        home.setToolTipText("Center the display canvas");
        home.setAlignmentX(CENTER_ALIGNMENT);
        home.setMaximumSize(new Dimension(56, 34));
        home.addActionListener(e -> {
            showTab("display");
            workspace.centerView();
        });
        side.add(home);

        side.add(Box.createVerticalGlue());
        sidebarStatus.setAlignmentX(CENTER_ALIGNMENT);
        sidebarStatus.setToolTipText(status.getText());
        status.addPropertyChangeListener("text", event ->
                sidebarStatus.setToolTipText(String.valueOf(event.getNewValue())));
        side.add(sidebarStatus);
        return side;
    }

    private JPanel mainArea() {
        cardHost.setBackground(BG);
        cardHost.add(displayTab(), "display");
        tradingPanel = new TradingPanel(() -> settings, this::connectedAccountMode, watchlist::getSelectedValue);
        indicatorPanel = new IndicatorStudioPanel(() -> settings, this::connectedAccountMode,
                symbols.isEmpty() ? "" : symbols.get(0),
                this::openIndicatorChart, this::useIndicatorInTrading);
        cardHost.add(indicatorPanel, "indicators");
        cardHost.add(tradingPanel, "trading");
        modelsPanel = new ModelsPanel(java.nio.file.Path.of(""), tradingPanel);
        tradingPanel.setTradeNavigator(() -> showTab("trading"));
        cardHost.add(modelsPanel, "models");
        newsPanel = new ModelsPanel(java.nio.file.Path.of(""), tradingPanel, true);
        cardHost.add(newsPanel, "news");
        stockSelectionPanel = new StockSelectionPanel(java.nio.file.Path.of(""), selected -> {
            modelsPanel.applyStocks(selected); showTab("models");
        }, stocksTab(), () -> settings, this::accountMode);
        cardHost.add(stockSelectionPanel, "stocks");
        cardHost.add(wrapView(settingsTab()), "settings");
        return cardHost;
    }

    private JScrollPane wrapView(JPanel content) {
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(BG);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        styleScroll(scroll);
        return scroll;
    }

    private JComponent displayTab() {
        workspace = new ModularWorkspace(this::createConfigurablePanel);
        JPanel desk = new JPanel(new BorderLayout());
        desk.setBackground(BG);
        JPanel bar = transparent(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 2));
        bar.setBackground(SIDEBAR);
        bar.setOpaque(true);
        bar.setPreferredSize(new Dimension(0, 31));
        profileSelector.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        profileSelector.setBackground(CARD);
        profileSelector.setForeground(WHITE);
        profileSelector.setPreferredSize(new Dimension(170, 25));
        profileSelector.setToolTipText("Desk profile • automatically saves layout and panel configuration");
        profileSelector.addActionListener(event -> selectProfile());
        bar.add(profileSelector);
        JButton addProfile = compactProfileButton("+", "Create a new desk profile");
        addProfile.addActionListener(event -> createProfile());
        bar.add(addProfile);
        JButton script = compactProfileButton("{ }", "Edit the whole desk as a declarative profile script");
        script.addActionListener(event -> editProfileScript());
        bar.add(script);
        JButton reload = compactProfileButton("↻", "Reload profile scripts from the USB");
        reload.addActionListener(event -> reloadProfiles());
        bar.add(reload);
        profileSaveStatus.setToolTipText("Desk profiles save automatically");
        bar.add(profileSaveStatus);
        desk.add(bar, BorderLayout.NORTH);
        desk.add(workspace, BorderLayout.CENTER);
        return desk;
    }

    private static JButton compactProfileButton(String label, String tooltip) {
        JButton button = new JButton(label);
        button.setFont(new Font("Segoe UI", Font.BOLD, 11));
        button.setForeground(ACCENT);
        button.setBackground(CARD);
        button.setBorder(new EmptyBorder(4, 8, 4, 8));
        button.setToolTipText(tooltip);
        button.setFocusPainted(false);
        return button;
    }

    private ConfigurablePanel createConfigurablePanel() {
        ConfigurablePanel panel = new ConfigurablePanel(this::createDisplayView,
                watchlist::getSelectedValue,
                () -> java.util.Collections.list(symbols.elements()), displayPanels::remove);
        displayPanels.add(panel);
        return panel;
    }

    private void initializeProfiles() {
        try {
            List<String> names = profileStore.names();
            if (names.isEmpty()) {
                profileStore.save(DeskProfile.blank("Default"));
                names = profileStore.names();
            }
            String wanted;
            try { wanted = profileStore.activeName(); }
            catch (IllegalArgumentException invalidPointer) { wanted = names.getFirst(); }
            if (!names.contains(wanted)) wanted = names.getFirst();
            refreshProfileSelector(names);
            List<String> candidates = new ArrayList<>();
            candidates.add(wanted);
            for (String name : names) if (!name.equals(wanted)) candidates.add(name);
            IOException firstFailure = null;
            for (String name : candidates) {
                try {
                    loadProfile(name);
                    if (firstFailure != null) {
                        String fallback = name;
                        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                                "The last desk profile could not be loaded. Opened " + fallback
                                        + " instead. The invalid script was left on the USB for repair.",
                                "Desk profiles", JOptionPane.WARNING_MESSAGE));
                    }
                    return;
                } catch (IOException | IllegalArgumentException error) {
                    if (firstFailure == null) firstFailure = new IOException(error.getMessage(), error);
                }
            }
            throw firstFailure == null ? new IOException("No usable desk profile was found.") : firstFailure;
        } catch (IOException | IllegalArgumentException error) {
            profileSaveStatus.setForeground(new Color(215, 107, 122));
            profileSaveStatus.setToolTipText("Could not load desk profiles: " + error.getMessage());
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                    "Desk profiles could not be loaded. Changes will not be saved until this is fixed.\n"
                            + error.getMessage(), "Desk profiles", JOptionPane.ERROR_MESSAGE));
        }
    }

    private void refreshProfileSelector(List<String> names) {
        changingProfile = true;
        profileSelector.removeAllItems();
        for (String name : names) profileSelector.addItem(name);
        if (activeProfile != null) profileSelector.setSelectedItem(activeProfile);
        changingProfile = false;
    }

    private DeskProfile currentProfile() {
        Point position = workspace.viewPosition();
        return new DeskProfile(DeskProfile.VERSION, activeProfile,
                java.util.Collections.list(symbols.elements()), watchlist.getSelectedValue(),
                position.x, position.y,
                workspace.snapshot().stream().map(DeskProfile.Panel::fromWorkspaceState).toList());
    }

    private void saveProfileQuietly() {
        if (activeProfile == null || changingProfile) return;
        try {
            saveProfile();
        } catch (IOException | IllegalArgumentException error) {
            profileSaveStatus.setForeground(new Color(215, 107, 122));
            profileSaveStatus.setToolTipText("Desk profile was not saved: " + error.getMessage());
        }
    }

    private void closeWithProfileSave() {
        if (modelsPanel != null && !modelsPanel.confirmClose()) return;
        if (newsPanel != null && !newsPanel.confirmClose()) return;
        if (stockSelectionPanel != null && !stockSelectionPanel.confirmClose()) return;
        try {
            if (activeProfile == null) throw new IOException("No desk profile was loaded.");
            saveProfile();
            dispose();
        } catch (IOException | RuntimeException error) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "The current desk could not be saved: " + error.getMessage()
                            + "\nClose TALG anyway and lose unsaved desk changes?",
                    "Desk not saved", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice == JOptionPane.YES_OPTION) dispose();
        }
    }

    private void saveProfile() throws IOException {
        if (activeProfile == null) return;
        DeskProfile profile = currentProfile();
        String script = profile.script();
        if (!script.equals(lastSavedProfileScript)) {
            if (lastDiskProfileSource != null
                    && !profileStore.source(activeProfile).equals(lastDiskProfileSource))
                throw new IOException("The profile script changed on disk. Reload it before saving this desk.");
            profileStore.save(profile);
            lastSavedProfileScript = script;
            lastDiskProfileSource = script;
        }
        profileSaveStatus.setForeground(ACCENT);
        profileSaveStatus.setToolTipText("Saved on USB: " + profileStore.pathFor(activeProfile));
    }

    private void loadProfile(String name) throws IOException {
        DeskProfile profile = profileStore.load(name);
        applyProfile(profile);
        profileStore.setActive(name);
        lastSavedProfileScript = profile.script();
        lastDiskProfileSource = profileStore.source(name);
        profileSaveStatus.setForeground(ACCENT);
        profileSaveStatus.setToolTipText("Saved on USB: " + profileStore.pathFor(name));
    }

    private void applyProfile(DeskProfile profile) {
        DeskProfile.validate(profile);
        changingProfile = true;
        try {
            symbols.clear();
            for (String ticker : profile.watchlist()) symbols.addElement(ticker);
            if (profile.selectedTicker() != null)
                watchlist.setSelectedValue(profile.selectedTicker(), true);
            else watchlist.clearSelection();
            workspace.restore(profile.panels().stream().map(DeskProfile.Panel::toWorkspaceState).toList(),
                    profile.viewport());
            activeProfile = profile.name();
            profileSelector.setSelectedItem(activeProfile);
            profileSelector.setToolTipText("Desk profile: " + activeProfile
                    + " • layout and panels autosave on the USB");
        } finally {
            changingProfile = false;
        }
    }

    private void selectProfile() {
        if (changingProfile || activeProfile == null) return;
        String chosen = (String) profileSelector.getSelectedItem();
        if (chosen == null || chosen.equals(activeProfile)) return;
        try {
            saveProfile();
            DeskProfile backup = currentProfile();
            try { loadProfile(chosen); }
            catch (IOException | RuntimeException error) {
                applyProfile(backup);
                throw error;
            }
        } catch (IOException | IllegalArgumentException error) {
            changingProfile = true;
            profileSelector.setSelectedItem(activeProfile);
            changingProfile = false;
            JOptionPane.showMessageDialog(this, "Could not switch desk profiles: " + error.getMessage(),
                    "Desk profiles", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void reloadProfiles() {
        if (activeProfile == null) return;
        if (JOptionPane.showConfirmDialog(this,
                "Reload desk scripts from the USB? Unsaved changes to the current desk will be discarded.",
                "Reload desk profiles", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        DeskProfile backup = currentProfile();
        try {
            refreshProfileSelector(profileStore.names());
            loadProfile(activeProfile);
        } catch (IOException | RuntimeException error) {
            applyProfile(backup);
            JOptionPane.showMessageDialog(this, "Could not reload desk profiles: " + error.getMessage(),
                    "Desk profiles", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void createProfile() {
        String name = JOptionPane.showInputDialog(this, "Name the new desk profile:",
                "New desk", JOptionPane.PLAIN_MESSAGE);
        if (name == null) return;
        name = name.trim();
        try {
            DeskProfile.validateName(name);
            if (profileStore.exists(name)) throw new IllegalArgumentException("That profile already exists.");
            saveProfile();
            profileStore.save(DeskProfile.blank(name));
            refreshProfileSelector(profileStore.names());
            loadProfile(name);
        } catch (IOException | IllegalArgumentException error) {
            JOptionPane.showMessageDialog(this, error.getMessage(), "New desk", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void editProfileScript() {
        if (activeProfile == null) return;
        try {
            saveProfile();
            if (!profileStore.source(activeProfile).equals(lastDiskProfileSource))
                throw new IOException("The profile script changed on disk. Click ↻ before editing it here.");
            JTextArea source = new JTextArea(lastSavedProfileScript, 28, 86);
            source.setFont(new Font("Consolas", Font.PLAIN, 12));
            source.setBackground(CARD);
            source.setForeground(WHITE);
            source.setCaretColor(ACCENT);
            source.setTabSize(2);
            JScrollPane editor = new JScrollPane(source);
            editor.setPreferredSize(new Dimension(850, 540));
            int choice = JOptionPane.showConfirmDialog(this, editor,
                    "Desk script • " + activeProfile + " • layout and panels only, no API keys",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) return;
            DeskProfile edited = DeskProfile.parse(source.getText());
            if (!activeProfile.equals(edited.name()))
                throw new IllegalArgumentException("Keep the profile name unchanged here; use + for a new desk.");
            DeskProfile backup = currentProfile();
            try {
                applyProfile(edited);
                profileStore.save(edited);
                lastSavedProfileScript = edited.script();
                lastDiskProfileSource = lastSavedProfileScript;
                profileSaveStatus.setForeground(ACCENT);
                profileSaveStatus.setToolTipText("Saved on USB: " + profileStore.pathFor(activeProfile));
            } catch (RuntimeException | IOException error) {
                applyProfile(backup);
                throw error;
            }
        } catch (IOException | RuntimeException error) {
            JOptionPane.showMessageDialog(this, error.getMessage(),
                    "Profile script", JOptionPane.ERROR_MESSAGE);
        }
    }

    private JComponent createDisplayView(ConfigurablePanel.Spec spec) {
        if ("Market quote".equals(spec.kind())) {
            return new MarketQuotePanel(() -> settings, spec.symbol());
        }
        if ("Watchlist".equals(spec.kind())) return watchlistModule();
        if ("Modeling board".equals(spec.kind())) return new ModelingBoardPanel(spec.modelFile());
        if ("Models".equals(spec.kind()) || "Analysis studio".equals(spec.kind()))
            return new AnalysisStudioPanel(() -> settings, this::connectedAccountMode,
                    watchlist.getSelectedValue());
        RPortfolioPanel panel = new RPortfolioPanel(spec.kind(), spec.period(),
                spec.indicator(), spec.overlays(), spec.symbol(), spec.chart());
        if (settings != null) SwingUtilities.invokeLater(() -> panel.connect(settings, accountMode()));
        return panel;
    }

    private JPanel watchlistModule() {
        JPanel rail = new JPanel(new BorderLayout());
        rail.setBackground(BG);
        rail.setBorder(new EmptyBorder(2, 3, 2, 3));
        JList<String> panelWatchlist = new JList<>(symbols);
        panelWatchlist.setBackground(BG);
        panelWatchlist.setForeground(WHITE);
        panelWatchlist.setFont(new Font("Segoe UI", Font.BOLD, 14));
        panelWatchlist.setFixedCellHeight(34);
        panelWatchlist.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        panelWatchlist.setBorder(new EmptyBorder(2, 4, 2, 4));
        panelWatchlist.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && panelWatchlist.getSelectedValue() != null) {
                watchlist.setSelectedValue(panelWatchlist.getSelectedValue(), true);
            }
        });
        panelWatchlist.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() == 2 && panelWatchlist.getSelectedValue() != null) {
                    watchlist.setSelectedValue(panelWatchlist.getSelectedValue(), true);
                    openSelectedStockChart();
                }
            }
        });
        JScrollPane watchScroll = new JScrollPane(panelWatchlist);
        watchScroll.setBorder(BorderFactory.createLineBorder(BORDER));
        styleScroll(watchScroll);
        rail.add(watchScroll, BorderLayout.CENTER);
        JLabel note = text("Double-click a ticker to open its chart", 10, MUTED, Font.PLAIN);
        rail.add(note, BorderLayout.SOUTH);

        return rail;
    }

    private JPanel stocksTab() {
        JPanel page = transparent(new BorderLayout(0, 4));
        page.setBorder(new EmptyBorder(7, 9, 7, 9));
        JPanel card = transparent(new BorderLayout(0, 6));
        card.setBorder(new EmptyBorder(2, 2, 2, 2));

        JPanel inputRow = transparent(new BorderLayout(6, 0));
        styleField(tickerField);
        tickerField.setToolTipText("Enter a stock ticker, such as AAPL or BRK.B");
        inputRow.add(tickerField, BorderLayout.CENTER);
        JButton add = new ActionButton("ADD STOCK", true);
        add.addActionListener(e -> addTicker());
        tickerField.addActionListener(e -> addTicker());
        inputRow.add(add, BorderLayout.EAST);
        card.add(inputRow, BorderLayout.NORTH);

        watchlist.setBackground(BG);
        watchlist.setForeground(WHITE);
        watchlist.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        watchlist.setFixedCellHeight(32);
        watchlist.setFont(new Font("Segoe UI", Font.BOLD, 13));
        watchlist.setCellRenderer((list, value, index, selected, focus) -> {
            JLabel cell = (JLabel) new DefaultListCellRenderer()
                    .getListCellRendererComponent(list, value, index, selected, focus);
            cell.setText("  " + value);
            cell.setOpaque(true);
            cell.setBackground(selected ? CARD_LIGHT : BG);
            cell.setForeground(selected ? ACCENT : WHITE);
            cell.setBorder(new EmptyBorder(0, 10, 0, 10));
            return cell;
        });
        watchlist.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() == 2) openSelectedStockChart();
            }
        });
        JScrollPane listScroll = new JScrollPane(watchlist);
        listScroll.setBorder(BorderFactory.createLineBorder(BORDER));
        styleScroll(listScroll);
        card.add(listScroll, BorderLayout.CENTER);

        JPanel lower = transparent(new BorderLayout());
        JPanel stockActions = transparent(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        JButton chart = new ActionButton("OPEN CHART", true);
        chart.addActionListener(e -> openSelectedStockChart());
        stockActions.add(chart);
        JButton quote = new ActionButton("OPEN QUOTE", false);
        quote.addActionListener(e -> openSelectedStockQuote());
        stockActions.add(quote);
        JButton backfill = new ActionButton("BACKFILL 1Y", false);
        backfill.addActionListener(e -> backfillWatchlist(backfill));
        stockActions.add(backfill);
        JButton remove = new ActionButton("REMOVE", false);
        remove.addActionListener(e -> removeTicker());
        stockActions.add(remove);
        lower.add(stockActions, BorderLayout.WEST);
        card.add(lower, BorderLayout.SOUTH);
        page.add(card, BorderLayout.CENTER);
        backfillStatus.setVisible(false);
        backfillStatus.addPropertyChangeListener("text", event ->
                backfillStatus.setVisible(!String.valueOf(event.getNewValue()).isBlank()));
        page.add(backfillStatus, BorderLayout.SOUTH);
        return page;
    }

    private JPanel settingsTab() {
        JPanel page = page();
        JPanel card = transparent(new GridBagLayout());
        card.setAlignmentX(LEFT_ALIGNMENT);
        card.setBorder(new EmptyBorder(2, 2, 2, 2));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 290));
        styleField(keyField);
        styleField(secretField);
        styleField(feedBox);
        styleField(accountBox);
        styleField(refreshField);

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 1;
        constraints.insets.bottom = 6;
        formRow(card, constraints, 0, "ALPACA API KEY ID", keyField);
        formRow(card, constraints, 1, "ALPACA API SECRET", secretField);
        formRow(card, constraints, 2, "MARKET DATA FEED", feedBox);
        formRow(card, constraints, 3, "ACCOUNT", accountBox);
        formRow(card, constraints, 4, "REFRESH INTERVAL (SECONDS)", refreshField);

        page.add(card);
        page.add(Box.createVerticalStrut(8));

        JButton connect = new ActionButton("APPLY & CONNECT", true);
        connect.setAlignmentX(LEFT_ALIGNMENT);
        connect.addActionListener(e -> applySettings());
        page.add(connect);
        page.add(Box.createVerticalStrut(8));
        credentialsNotice.setAlignmentX(LEFT_ALIGNMENT);
        page.add(credentialsNotice);
        page.add(Box.createVerticalStrut(6));
        JButton forget = new ActionButton("FORGET SAVED KEYS", false);
        forget.setAlignmentX(LEFT_ALIGNMENT);
        forget.addActionListener(e -> forgetSavedCredentials());
        page.add(forget);
        return page;
    }

    private void formRow(JPanel container, GridBagConstraints base, int row,
                         String title, JComponent component) {
        JPanel line = transparent(new BorderLayout(0, 2));
        line.add(text(title, 11, MUTED, Font.BOLD), BorderLayout.NORTH);
        component.setPreferredSize(new Dimension(200, 29));
        line.add(component, BorderLayout.CENTER);
        base.gridy = row;
        container.add(line, base);
    }

    private void showTab(String name) {
        cards.show(cardHost, name);
        displayNav.setActive("display".equals(name));
        indicatorsNav.setActive("indicators".equals(name));
        tradingNav.setActive("trading".equals(name));
        if ("trading".equals(name)) tradingPanel.activate();
        modelsNav.setActive("models".equals(name));
        if ("models".equals(name)) modelsPanel.activate();
        newsNav.setActive("news".equals(name));
        if ("news".equals(name)) newsPanel.activate();
        stocksNav.setActive("stocks".equals(name));
        settingsNav.setActive("settings".equals(name));
    }

    private void addTicker() {
        String symbol = tickerField.getText().trim().toUpperCase(Locale.ROOT);
        if (!symbol.matches("[A-Z][A-Z0-9.-]{0,9}")) {
            JOptionPane.showMessageDialog(this, "Enter a valid uppercase stock ticker.",
                    "Invalid symbol", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!symbols.contains(symbol)) symbols.addElement(symbol);
        watchlist.setSelectedValue(symbol, true);
        tickerField.setText("");
    }

    private void openSelectedStockChart() {
        String symbol = watchlist.getSelectedValue();
        if (symbol == null) {
            backfillStatus.setText("Select a ticker first.");
            return;
        }
        showTab("display");
        workspace.addPanel().openStockChart(symbol);
    }

    private void openIndicatorChart(IndicatorStudioPanel.Selection selection) {
        showTab("display");
        workspace.addPanel().openIndicatorChart(selection.ticker(), selection.name());
    }

    private void useIndicatorInTrading(IndicatorStudioPanel.Selection selection) {
        showTab("models");
        tradingPanel.addIndicatorInput(selection.name(), selection.ticker());
        modelsPanel.openRules();
    }

    private void openSelectedStockQuote() {
        String symbol = watchlist.getSelectedValue();
        if (symbol == null) {
            backfillStatus.setText("Select a ticker first.");
            return;
        }
        showTab("display");
        workspace.addPanel().openMarketQuote(symbol);
    }

    private void backfillWatchlist(JButton button) {
        if (settings == null) {
            backfillStatus.setText("Connect Alpaca in Settings, then press BACKFILL 1Y.");
            showTab("settings");
            return;
        }
        List<String> requested = java.util.Collections.list(symbols.elements());
        if (requested.isEmpty()) {
            backfillStatus.setText("Add a stock ticker before backfilling.");
            return;
        }
        AlpacaSettings requestSettings = settings;
        button.setEnabled(false);
        backfillStatus.setText("Backfilling " + requested.size() + " ticker(s) from Alpaca…");
        new SwingWorker<BackfillSummary, String>() {
            @Override protected BackfillSummary doInBackground() {
                List<String> outcomes = new ArrayList<>();
                HistoricalBackfillService service = new HistoricalBackfillService();
                int saved = 0;
                int failed = 0;
                for (String ticker : requested) {
                    try {
                        HistoricalBackfillService.Result result = service.fetchOneYear(ticker, requestSettings);
                        saved++;
                        outcomes.add(ticker + ": " + result.bars() + " bars (" + result.feed()
                                + " feed) saved to " + result.path());
                        publish(ticker + " saved " + result.bars() + " historical bars ("
                                + result.feed() + " feed)");
                    } catch (Exception error) {
                        failed++;
                        outcomes.add(ticker + ": " + error.getMessage());
                        publish(ticker + " backfill failed: " + error.getMessage());
                    }
                }
                return new BackfillSummary(saved, failed, outcomes);
            }

            @Override protected void process(List<String> updates) {
                backfillStatus.setText(updates.getLast());
            }

            @Override protected void done() {
                button.setEnabled(true);
                try {
                    BackfillSummary result = get();
                    backfillStatus.setText("Backfill: " + result.saved() + " saved, " + result.failed()
                            + " failed • hover for details");
                    backfillStatus.setToolTipText(String.join(" | ", result.details()));
                } catch (Exception error) {
                    backfillStatus.setText("Backfill interrupted: " + error.getMessage());
                }
            }
        }.execute();
    }

    private record BackfillSummary(int saved, int failed, List<String> details) {}

    private void removeTicker() {
        int selected = watchlist.getSelectedIndex();
        if (selected >= 0) {
            symbols.remove(selected);
            if (!symbols.isEmpty()) watchlist.setSelectedIndex(Math.min(selected, symbols.size() - 1));
        }
    }

    private void applySettings() {
        char[] keyChars = keyField.getPassword();
        char[] secretChars = secretField.getPassword();
        AlpacaSettings candidate;
        try {
            int seconds;
            try {
                seconds = Integer.parseInt(refreshField.getText().trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Refresh interval must be a whole number of seconds", ex);
            }
            String key = new String(keyChars).trim();
            String secret = new String(secretChars).trim();
            if (key.isEmpty() && secret.isEmpty() && savedCredentials != null
                    && savedCredentials.mode().equals(accountMode())) {
                key = savedCredentials.settings().apiKey();
                secret = savedCredentials.settings().apiSecret();
            } else if (key.isEmpty() && secret.isEmpty() && savedCredentials != null) {
                throw new IllegalArgumentException("Enter the " + accountMode()
                        + " Alpaca key and secret when changing account modes.");
            } else if (key.isEmpty() && secret.isEmpty()) {
                key = System.getenv().getOrDefault("APCA_API_KEY_ID", "").trim();
                secret = System.getenv().getOrDefault("APCA_API_SECRET_KEY", "").trim();
            }
            candidate = new AlpacaSettings(key, secret, (String) feedBox.getSelectedItem(), seconds);
            credentialStore.save(new CredentialStore.Saved(candidate, accountMode()));
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Settings", JOptionPane.WARNING_MESSAGE);
            return;
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "TALG could not save keys securely for this Windows user. No new connection was applied.",
                    "Settings", JOptionPane.ERROR_MESSAGE);
            return;
        } finally {
            Arrays.fill(keyChars, '\0');
            Arrays.fill(secretChars, '\0');
        }
        settings = candidate;
        savedCredentials = new CredentialStore.Saved(candidate, accountMode());
        keyField.setText("");
        secretField.setText("");
        credentialsNotice.setText("Encrypted keys saved for this Windows user. Leave both key fields blank to reuse.");
        startRefreshTimer();
        sidebarStatus.setForeground(ACCENT);
        sidebarStatus.setToolTipText("Alpaca account configured");
        status.setForeground(MUTED);
        status.setText("Alpaca settings saved securely; configured panels refresh independently");
        for (ConfigurablePanel panel : List.copyOf(displayPanels)) panel.accountChanged(settings, accountMode());
        if (tradingPanel != null) tradingPanel.settingsChanged();
        showTab("display");
    }

    private void forgetSavedCredentials() {
        if (JOptionPane.showConfirmDialog(this,
                "Remove TALG's encrypted Alpaca keys from this Windows account and disconnect?",
                "Forget saved keys", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        try {
            credentialStore.delete();
        } catch (IOException error) {
            JOptionPane.showMessageDialog(this, "TALG could not remove the saved credential file.",
                    "Settings", JOptionPane.ERROR_MESSAGE);
            return;
        }
        savedCredentials = null;
        settings = null;
        keyField.setText("");
        secretField.setText("");
        if (refreshTimer != null) refreshTimer.stop();
        for (ConfigurablePanel panel : List.copyOf(displayPanels)) panel.accountCleared();
        if (tradingPanel != null) tradingPanel.settingsChanged();
        credentialsNotice.setText("Saved keys removed. Enter new keys to reconnect.");
        sidebarStatus.setForeground(MUTED);
        status.setText("Disconnected from Alpaca; saved credentials removed");
    }

    private void startRefreshTimer() {
        if (refreshTimer != null) refreshTimer.stop();
        refreshTimer = new Timer(settings.refreshSeconds() * 1000, e ->
                List.copyOf(displayPanels).forEach(ConfigurablePanel::refreshQuote));
        refreshTimer.start();
    }

    private String accountMode() {
        return accountBox.getSelectedIndex() == 0 ? "paper" : "live";
    }

    private String connectedAccountMode() {
        return savedCredentials == null ? accountMode() : savedCredentials.mode();
    }

    private static JPanel page() {
        JPanel page = transparent();
        page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
        page.setBorder(new EmptyBorder(8, 10, 6, 10));
        return page;
    }

    private static JPanel transparent() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        return panel;
    }

    private static JPanel transparent(java.awt.LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }

    private static JLabel text(String value, int size, Color color, int weight) {
        JLabel label = new JLabel(value);
        label.setFont(new Font("Segoe UI", weight, size));
        label.setForeground(color);
        return label;
    }

    private static void styleField(JComponent field) {
        field.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        field.setForeground(WHITE);
        field.setBackground(CARD_LIGHT);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER), new EmptyBorder(6, 10, 6, 10)));
        if (field instanceof JTextField text) text.setCaretColor(ACCENT);
    }

    private static void styleScroll(JScrollPane scroll) {
        scroll.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
        scroll.getVerticalScrollBar().setUI(new BasicScrollBarUI() {
            @Override protected void configureScrollBarColors() {
                thumbColor = BORDER;
                trackColor = BG;
            }

            @Override protected JButton createDecreaseButton(int orientation) {
                return zeroButton();
            }

            @Override protected JButton createIncreaseButton(int orientation) {
                return zeroButton();
            }

            private JButton zeroButton() {
                JButton button = new JButton();
                button.setPreferredSize(new Dimension(0, 0));
                button.setMinimumSize(new Dimension(0, 0));
                button.setMaximumSize(new Dimension(0, 0));
                return button;
            }
        });
    }

    private static final class ActionButton extends JButton {
        private final boolean primary;

        private ActionButton(String title, boolean primary) {
            super(title);
            this.primary = primary;
            setFont(new Font("Segoe UI", Font.BOLD, 11));
            setForeground(primary ? BG : WHITE);
            setBorder(new EmptyBorder(7, 11, 7, 11));
            setContentAreaFilled(false);
            setFocusPainted(false);
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(!isEnabled() ? BORDER : primary ? ACCENT : CARD_LIGHT);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    private static final class NavButton extends JButton {
        private boolean active;

        private NavButton(String title) {
            super(title);
            setHorizontalAlignment(SwingConstants.CENTER);
            setFont(new Font("Segoe UI", Font.BOLD, 9));
            setBorder(new EmptyBorder(0, 3, 0, 3));
            setContentAreaFilled(false);
            setFocusPainted(false);
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        }

        private void setActive(boolean active) {
            this.active = active;
            setForeground(active ? ACCENT : MUTED);
            repaint();
        }

        @Override protected void paintComponent(Graphics graphics) {
            if (active) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(CARD_LIGHT);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
                g.setColor(ACCENT);
                g.fillRoundRect(0, 8, 4, getHeight() - 16, 4, 4);
                g.dispose();
            }
            super.paintComponent(graphics);
        }
    }

}

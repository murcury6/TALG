package io.github.murcury6.talg;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Declarative, editable desk script. It contains no credentials or cached market values. */
record DeskProfile(int version, String name, List<String> watchlist, String selectedTicker,
                   int viewX, int viewY, List<Panel> panels) {
    static final int VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final Set<String> TYPES = Set.of("Portfolio", "Stock chart", "Market quote",
            "Watchlist", "Modeling board", "Models", "Analysis studio");
    private static final Set<String> VIEWS = Set.of("Overview", "Performance", "Allocation",
            "Risk lens", "Holdings");
    private static final Set<String> PERIODS = Set.of("1M", "3M", "6M", "YTD", "1Y");

    record Panel(int x, int y, int width, int height,
                 ConfigurablePanel.FormState draft, ConfigurablePanel.FormState active) {
        ModularWorkspace.PanelState toWorkspaceState() {
            return new ModularWorkspace.PanelState(new Rectangle(x, y, width, height),
                    new ConfigurablePanel.State(draft == null ? active : draft, active));
        }

        static Panel fromWorkspaceState(ModularWorkspace.PanelState state) {
            Rectangle box = state.bounds();
            return new Panel(box.x, box.y, box.width, box.height,
                    state.panel().draft(), state.panel().active());
        }
    }

    static DeskProfile blank(String name) {
        return new DeskProfile(VERSION, name, List.of("AAPL", "MSFT", "NVDA"), "AAPL",
                1100, 700, List.of());
    }

    static DeskProfile parse(String source) throws JsonProcessingException {
        DeskProfile profile = JSON.readValue(source, DeskProfile.class);
        validate(profile);
        return profile;
    }

    String script() throws JsonProcessingException {
        validate(this);
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(this) + System.lineSeparator();
    }

    Point viewport() { return new Point(viewX, viewY); }

    static void validateName(String name) {
        if (name == null || !name.matches("[A-Za-z](?:[A-Za-z0-9 _-]{0,38}[A-Za-z0-9])?"))
            throw new IllegalArgumentException("Profile names need 1–40 letters, digits, spaces, _ or -, starting with a letter.");
    }

    static void validate(DeskProfile profile) {
        if (profile == null) throw new IllegalArgumentException("Profile script is empty.");
        if (profile.version != VERSION) throw new IllegalArgumentException("Unsupported desk profile version.");
        validateName(profile.name);
        if (profile.watchlist == null || profile.watchlist.size() > 200)
            throw new IllegalArgumentException("A profile may hold up to 200 watchlist tickers.");
        Set<String> unique = new HashSet<>();
        for (String ticker : profile.watchlist) {
            if (!validTicker(ticker) || !unique.add(ticker))
                throw new IllegalArgumentException("Watchlist tickers must be valid and unique.");
        }
        if (profile.selectedTicker != null && !profile.selectedTicker.isBlank()
                && !profile.watchlist.contains(profile.selectedTicker))
            throw new IllegalArgumentException("Selected ticker must be in the watchlist.");
        if (profile.viewX < 0 || profile.viewX > 3600 || profile.viewY < 0 || profile.viewY > 2400)
            throw new IllegalArgumentException("Viewport is outside the desk canvas.");
        if (profile.panels == null || profile.panels.size() > 64)
            throw new IllegalArgumentException("A desk profile may contain up to 64 panels.");
        for (Panel panel : profile.panels) {
            if (panel == null || panel.x < 0 || panel.y < 0 || panel.width < 280 || panel.height < 200
                    || panel.x + panel.width > 3600 || panel.y + panel.height > 2400)
                throw new IllegalArgumentException("A panel has invalid position or size.");
            if (panel.draft == null && panel.active == null)
                throw new IllegalArgumentException("A panel needs draft or active configuration.");
            if (panel.draft != null) validateForm(panel.draft, false);
            if (panel.active != null) validateForm(panel.active, true);
        }
    }

    private static void validateForm(ConfigurablePanel.FormState form, boolean active) {
        if (form == null || !TYPES.contains(form.type()) || !VIEWS.contains(form.portfolioView())
                || !PERIODS.contains(form.period()) || form.symbol() == null
                || form.chartScript() == null || form.chartScript().length() > 100_000
                || form.modelFile() == null || form.modelFile().length() > 1000)
            throw new IllegalArgumentException("A panel has invalid configuration fields.");
        if (!active) return; // Draft scripts can be unfinished without changing the active panel.
        if ("Stock chart".equals(form.type())) ChartScript.parse(form.chartScript());
        if ("Market quote".equals(form.type()) && !validTicker(form.symbol()))
            throw new IllegalArgumentException("An active quote panel needs a valid ticker.");
    }

    private static boolean validTicker(String ticker) {
        return ticker != null && ticker.matches("[A-Z][A-Z0-9.-]{0,9}");
    }
}

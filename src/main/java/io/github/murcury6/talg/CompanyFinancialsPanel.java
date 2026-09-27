package io.github.murcury6.talg;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;

/** Free SEC company facts; original disclosures are saved alongside normalized observations. */
final class CompanyFinancialsPanel extends JPanel {
    record Fact(String metric, String period, String start, String end, String value, String unit,
                String filed, String form, String accession, String tag) {}
    private final Path root;
    private final JTextField ticker = new JTextField(8), contact = new JTextField(25);
    private final JButton load = NewsPanel.button("FETCH SEC FINANCIALS"), filing = NewsPanel.button("OPEN FILING");
    private final JLabel status = NewsPanel.label("Enter a US-listed ticker and a contact email for SEC requests. No key or subscription.", NewsPanel.MUTED);
    private final DefaultTableModel rows = NewsPanel.model("METRIC", "PERIOD", "START", "END", "REPORTED VALUE", "UNIT", "FILED", "FORM");
    private final JTable table = NewsPanel.table(rows);
    private List<Fact> facts = List.of();
    private String cik = "";

    CompanyFinancialsPanel(Path root) {
        super(new BorderLayout(0, 8)); this.root = root; setBackground(NewsPanel.BG);
        JPanel top = NewsPanel.panel(new FlowLayout(FlowLayout.LEFT, 7, 10));
        for (var field : List.of(ticker, contact)) { field.setBackground(NewsPanel.CARD); field.setForeground(NewsPanel.TEXT); field.setCaretColor(NewsPanel.TEXT); }
        top.add(NewsPanel.label("Ticker", NewsPanel.TEXT)); top.add(ticker);
        top.add(NewsPanel.label("SEC contact email", NewsPanel.TEXT)); top.add(contact); top.add(load);
        contact.setToolTipText("Sent only in the request identification header to the SEC; not saved in collection files.");
        add(top, BorderLayout.NORTH); add(NewsPanel.scroll(table), BorderLayout.CENTER);
        JPanel bottom = NewsPanel.panel(new BorderLayout(0, 7));
        JTextArea note = NewsPanel.area();
        note.setText("Reported consolidated figures from SEC filings. Quarterly rows cover roughly three months; annual rows cover roughly a year.\nYear-to-date figures are excluded here. Missing quarters (often Q4 or cash flow) are not invented. Company-specific tags and segment sales may be absent.\nValues retain their original units. The table shows the latest filed version of each period; the downloaded source retains filing history.");
        note.setRows(3); bottom.add(note, BorderLayout.NORTH);
        bottom.add(status, BorderLayout.CENTER); bottom.add(filing, BorderLayout.SOUTH); add(bottom, BorderLayout.SOUTH);
        filing.setEnabled(false);
        load.addActionListener(event -> fetch());
        table.getSelectionModel().addListSelectionListener(event -> filing.setEnabled(table.getSelectedRow() >= 0 && !cik.isBlank()));
        filing.addActionListener(event -> {
            int selected = table.getSelectedRow();
            if (selected < 0) return;
            Fact fact = facts.get(table.convertRowIndexToModel(selected));
            String url = "https://www.sec.gov/Archives/edgar/data/" + Long.parseLong(cik) + "/"
                    + fact.accession().replace("-", "") + "/" + fact.accession() + "-index.html";
            try { Desktop.getDesktop().browse(URI.create(url)); }
            catch (Exception error) { status.setText(url); }
        });
    }

    private void fetch() {
        String symbol = ticker.getText().trim().toUpperCase(Locale.ROOT), email = contact.getText().trim();
        if (!symbol.matches("[A-Z][A-Z0-9.-]{0,14}")) { status.setText("Enter a ticker such as AAPL or BRK-B."); return; }
        if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) { status.setText("The SEC requests an identifying contact email for automated access."); return; }
        load.setEnabled(false); filing.setEnabled(false);
        status.setText("Looking up " + symbol + " in the SEC company directory…");
        new SwingWorker<List<Fact>, Void>() {
            private String company, loadedCik;
            protected List<Fact> doInBackground() throws Exception {
                String agent = "TALG personal research " + email;
                Path directory = root.resolve("data/financials"); Files.createDirectories(directory);
                Path tickers = directory.resolve("company-tickers.json");
                if (!Files.exists(tickers) || Files.getLastModifiedTime(tickers).toInstant().isBefore(Instant.now().minusSeconds(86400))) {
                    byte[] data = NewsService.fetch("https://www.sec.gov/files/company_tickers.json", agent, 8_000_000);
                    NewsService.JSON.readTree(data); save(tickers, data);
                }
                JsonNode directoryData = NewsService.JSON.readTree(Files.readAllBytes(tickers));
                for (JsonNode entry : directoryData) if (symbol.equalsIgnoreCase(entry.path("ticker").asText())) {
                    loadedCik = String.format("%010d", entry.path("cik_str").asLong());
                    company = entry.path("title").asText(); break;
                }
                if (loadedCik == null) throw new java.io.IOException("Ticker not found in SEC directory (coverage is limited to SEC filers)");
                byte[] data = NewsService.fetch("https://data.sec.gov/api/xbrl/companyfacts/CIK" + loadedCik + ".json", agent, 30_000_000);
                JsonNode document = NewsService.JSON.readTree(data);
                List<Fact> parsed = extract(document);
                Path folder = directory.resolve(symbol); Files.createDirectories(folder);
                String stamp = Instant.now().toString().replace(':', '-');
                save(folder.resolve(stamp + "-companyfacts.json"), data);
                save(folder.resolve(stamp + "-normalized.json"), NewsService.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(parsed));
                return parsed;
            }
            protected void done() {
                try {
                    facts = get(); cik = loadedCik; rows.setRowCount(0);
                    facts.forEach(fact -> rows.addRow(new Object[]{fact.metric(), fact.period(), fact.start(), fact.end(), fact.value(), fact.unit(), fact.filed(), fact.form()}));
                    status.setText(company + " • " + facts.size() + " reported observations • Saved under data/financials/" + symbol);
                } catch (Exception error) { status.setText("SEC fetch failed; existing files retained. " + NewsService.errorMessage(error)); }
                finally { load.setEnabled(true); }
            }
        }.execute();
    }

    private static void save(Path file, byte[] bytes) throws Exception {
        Path temp = Files.createTempFile(file.getParent(), "sec-", ".tmp");
        try { Files.write(temp, bytes); Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temp); }
    }

    static List<Fact> extract(JsonNode document) {
        Map<String, List<String>> metrics = new LinkedHashMap<>();
        metrics.put("Revenue / sales", List.of("RevenueFromContractWithCustomerExcludingAssessedTax", "Revenues", "SalesRevenueNet", "RevenueFromContractWithCustomerIncludingAssessedTax"));
        metrics.put("Net income", List.of("NetIncomeLoss", "ProfitLoss"));
        metrics.put("Operating income", List.of("OperatingIncomeLoss"));
        metrics.put("Diluted EPS", List.of("EarningsPerShareDiluted"));
        metrics.put("Operating cash flow", List.of("NetCashProvidedByUsedInOperatingActivities"));
        metrics.put("Assets", List.of("Assets"));
        metrics.put("Liabilities", List.of("Liabilities"));
        metrics.put("Cash & equivalents", List.of("CashAndCashEquivalentsAtCarryingValue"));
        Map<String, Fact> selected = new LinkedHashMap<>();
        JsonNode gaap = document.path("facts").path("us-gaap");
        for (var metric : metrics.entrySet()) for (String tag : metric.getValue()) {
            var units = gaap.path(tag).path("units").fields();
            while (units.hasNext()) {
                var unit = units.next();
                for (JsonNode observation : unit.getValue()) {
                    String start = observation.path("start").asText(), end = observation.path("end").asText();
                    String period;
                    try {
                        LocalDate.parse(end);
                        if (start.isBlank()) period = "At date";
                        else {
                            long days = ChronoUnit.DAYS.between(LocalDate.parse(start), LocalDate.parse(end)) + 1;
                            if (days >= 60 && days <= 120) period = "Quarter";
                            else if (days >= 330 && days <= 400) period = "Annual";
                            else continue;
                        }
                    } catch (RuntimeException error) { continue; }
                    if (!observation.path("val").isNumber() || observation.path("accn").asText().isBlank()) continue;
                    Fact fact = new Fact(metric.getKey(), period, start, end, observation.path("val").asText(), unit.getKey(),
                            observation.path("filed").asText(), observation.path("form").asText(), observation.path("accn").asText(), tag);
                    String key = metric.getKey() + "/" + start + "/" + end + "/" + unit.getKey();
                    Fact old = selected.get(key);
                    if (old == null || fact.filed().compareTo(old.filed()) > 0) selected.put(key, fact);
                }
            }
        }
        return selected.values().stream().sorted(Comparator.comparing(Fact::end).reversed().thenComparing(Fact::metric).thenComparing(Fact::period)).toList();
    }
}

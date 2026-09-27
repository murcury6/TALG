package io.github.murcury6.talg;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.function.Consumer;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** Free RSS/Atom collection. Publisher text is retained as text, never executable HTML. */
final class NewsService {
    static final ObjectMapper JSON = new ObjectMapper();
    static final int MAX_ITEMS = 20_000;
    static final String USER_AGENT = "TALG/0.1 (personal research RSS reader)";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final Path directory;
    private final Path archive;
    private static final Map<String, FeedCache> FEED_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Instant> RETRY_AT = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Integer> FAILURES = new java.util.concurrent.ConcurrentHashMap<>();
    private record FeedCache(byte[] body, String etag, String modified) {}

    record Source(String id, String publisher, String name, String category, String url, boolean enabled, String symbol) {
        Source { symbol = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT); }
        Source(String id, String publisher, String name, String category, String url, boolean enabled) {
            this(id, publisher, name, category, url, enabled, "");
        }
        @Override public String toString() { return publisher + " / " + name; }
    }
    record Article(String sourceId, String publisher, String category, String title, String url,
                   String summary, String publishedAt, String firstSeen, String lastSeen, List<String> symbols) {
        Article { symbols = symbols == null ? List.of() : List.copyOf(symbols); }
        Article(String sourceId, String publisher, String category, String title, String url,
                String summary, String publishedAt, String firstSeen, String lastSeen) {
            this(sourceId, publisher, category, title, url, summary, publishedAt, firstSeen, lastSeen, List.of());
        }
        String key() { return sourceId + "\n" + canonicalUrl(url); }
        String sortTime() {
            // A publisher's erroneous future clock must not outrank subsequent observed events.
            return publishedAt.isBlank() ? firstSeen
                    : Instant.parse(publishedAt).isAfter(Instant.parse(lastSeen)) ? lastSeen : publishedAt;
        }
        boolean uncertainDate() {
            return publishedAt.isBlank() || Instant.parse(publishedAt).isAfter(Instant.parse(lastSeen).plusSeconds(300));
        }
    }
    record FeedStatus(String sourceId, String attemptedAt, String lastSuccess, String state, int count) {}
    record Snapshot(List<Article> articles, List<FeedStatus> statuses) {}
    record FeedResult(Source source, List<Article> articles, String error) {}
    record Collected(String fingerprint, String collectedAt, Source source, Article article) {}

    NewsService(Path root) { directory = root.resolve("work/news"); archive = root.resolve("data/news"); }
    Path sourcesPath() { return directory.resolve("sources.json"); }
    Path archivePath() { return archive; }

    /** Permanent, readable collection: one JSONL file per UTC day and source; revisions get new rows. */
    void archive(Source source, byte[] xml, List<Article> articles, Instant now) throws Exception {
        Path day = archive.resolve(now.toString().substring(0, 10));
        Files.createDirectories(day);
        Path records = day.resolve(source.id() + ".jsonl");
        Set<String> existing = new java.util.HashSet<>();
        String original = Files.exists(records) ? Files.readString(records, StandardCharsets.UTF_8) : "";
        if (Files.exists(records)) {
            try (var lines = Files.lines(records, StandardCharsets.UTF_8)) {
                for (String line : lines.filter(value -> !value.isBlank()).toList()) {
                    existing.add(JSON.readTree(line).path("fingerprint").asText());
                }
            }
        }
        StringBuilder added = new StringBuilder();
        for (Article article : articles) {
            String fingerprint = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((article.key() + "\n" + article.title() + "\n" + article.summary() + "\n" + article.publishedAt())
                            .getBytes(StandardCharsets.UTF_8)));
            if (existing.add(fingerprint)) added.append(JSON.writeValueAsString(new Collected(fingerprint,
                    now.toString(), source, article))).append('\n');
        }
        if (!added.isEmpty()) replaceFile(records, (original + added).getBytes(StandardCharsets.UTF_8));
        // A daily raw feed snapshot complements the normalized collection; updated atomically.
        Path raw = day.resolve(source.id() + ".xml");
        replaceFile(raw, xml);
    }

    static void replaceFile(Path path, byte[] bytes) throws IOException {
        Path temp = Files.createTempFile(path.getParent(), "news-", ".tmp");
        try {
            Files.write(temp, bytes);
            try { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException error) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    List<Source> sources() throws IOException {
        List<Source> sources;
        if (Files.exists(sourcesPath())) {
            sources = JSON.readValue(Files.readAllBytes(sourcesPath()), new TypeReference<>() {});
        } else {
            try (var stream = NewsService.class.getResourceAsStream("/news-sources.json")) {
                if (stream == null) throw new IOException("Bundled news sources are missing");
                sources = JSON.readValue(stream, new TypeReference<>() {});
            }
        }
        validateSources(sources);
        return sources;
    }

    void saveSources(List<Source> sources) throws IOException {
        validateSources(sources);
        writeAtomic(sourcesPath(), sources);
    }

    static void validateSources(List<Source> sources) throws IOException {
        if (sources == null || sources.size() > 500) throw new IOException("Use at most 500 feeds");
        Set<String> ids = new java.util.HashSet<>();
        for (Source source : sources) {
            if (source == null || source.id() == null || !source.id().matches("[a-z0-9-]{1,60}")
                    || !ids.add(source.id()) || blank(source.publisher()) || blank(source.name())
                    || blank(source.category()) || !webUrl(source.url())
                    || (!source.symbol().isEmpty() && !source.symbol().matches("[A-Z0-9][A-Z0-9.^-]{0,14}"))) {
                throw new IOException("Each feed needs a unique lowercase id, publisher, name, category, and HTTP(S) URL");
            }
        }
    }

    static Source stockSource(String ticker) throws IOException {
        String symbol = ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT);
        if (!symbol.matches("[A-Z0-9][A-Z0-9.^-]{0,14}")) throw new IOException("Enter a stock or ETF ticker, such as AAPL or BRK-B");
        String encoded = java.net.URLEncoder.encode(symbol, StandardCharsets.UTF_8);
        String id = "stock-" + java.util.HexFormat.of().formatHex(symbol.getBytes(StandardCharsets.UTF_8));
        return new Source(id, "Yahoo Finance discovery", symbol + " news", "Stocks & ETFs",
                "https://feeds.finance.yahoo.com/rss/2.0/headline?s=" + encoded + "&region=US&lang=en-US", true, symbol);
    }

    /** Serialize complete collection/index updates across the app and background collector. */
    Snapshot collect(Consumer<String> progress) throws Exception {
        Files.createDirectories(directory);
        try (var channel = java.nio.channels.FileChannel.open(directory.resolve("collection.lock"),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
            java.nio.channels.FileLock lock;
            try { lock = channel.tryLock(); }
            catch (java.nio.channels.OverlappingFileLockException busy) { return load(); }
            if (lock == null) return load();
            try (lock) {
                Snapshot result = refresh(sources(), load(), progress);
                save(result);
                return result;
            }
        }
    }

    Snapshot load() throws IOException {
        Path path = directory.resolve("articles.json");
        Snapshot saved = Files.exists(path) ? JSON.readValue(Files.readAllBytes(path), Snapshot.class) : new Snapshot(List.of(), List.of());
        if (saved.articles() == null || saved.statuses() == null) throw new IOException("Invalid news cache");
        Path stream = directory.resolve("stream-articles.json");
        List<Article> pushed = Files.exists(stream) ? JSON.readValue(Files.readAllBytes(stream), new TypeReference<>() {}) : List.of();
        return new Snapshot(merge(saved.articles(), pushed, Instant.now()), saved.statuses());
    }

    void save(Snapshot snapshot) throws IOException { writeAtomic(directory.resolve("articles.json"), snapshot); }

    private void writeAtomic(Path path, Object value) throws IOException {
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, "news-", ".tmp");
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), value);
            try { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException error) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temp); }
    }

    Snapshot refresh(List<Source> sources, Snapshot previous, Consumer<String> progress) throws Exception {
        Instant now = Instant.now();
        Files.createDirectories(archive);
        replaceFile(archive.resolve("sources.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(sources));
        Map<String, FeedStatus> statuses = new HashMap<>();
        previous.statuses().forEach(status -> statuses.put(status.sourceId(), status));
        List<Article> incoming = new ArrayList<>();
        var enabled = sources.stream().filter(Source::enabled).toList();
        try (var executor = Executors.newFixedThreadPool(6, Thread.ofPlatform().daemon(true).factory())) {
            var completion = new java.util.concurrent.ExecutorCompletionService<FeedResult>(executor);
            enabled.forEach(source -> completion.submit(() -> {
                try {
                    Instant retry = RETRY_AT.get(source.url());
                    if (retry != null && now.isBefore(retry)) return new FeedResult(source, List.of(), "Retry after " + retry);
                    byte[] xml = fetchFeed(source.url());
                    List<Article> articles = parse(source, xml, now);
                    archive(source, xml, articles, now);
                    FAILURES.remove(source.url()); RETRY_AT.remove(source.url());
                    return new FeedResult(source, articles, "");
                }
                catch (Exception error) {
                    int failures = FAILURES.merge(source.url(), 1, Integer::sum);
                    RETRY_AT.merge(source.url(), now.plusSeconds(Math.min(3600, 120L << Math.min(failures - 1, 5))),
                            (old, proposed) -> old.isAfter(proposed) ? old : proposed);
                    return new FeedResult(source, List.of(), errorMessage(error));
                }
            }));
            for (int index = 0; index < enabled.size(); index++) {
                FeedResult result = completion.take().get();
                FeedStatus old = statuses.get(result.source().id());
                boolean ok = result.error().isBlank();
                String state = ok ? (result.articles().isEmpty() ? "Empty feed" : "OK") : result.error();
                statuses.put(result.source().id(), new FeedStatus(result.source().id(), now.toString(),
                        ok ? now.toString() : old == null ? "" : old.lastSuccess(), state, result.articles().size()));
                incoming.addAll(result.articles());
                progress.accept("Checked " + (index + 1) + "/" + enabled.size() + " feeds • " + result.source().publisher());
            }
        }
        Snapshot result = new Snapshot(merge(previous.articles(), incoming, now), sources.stream()
                .map(source -> statuses.getOrDefault(source.id(), new FeedStatus(source.id(), "", "", "Not checked", 0)))
                .toList());
        replaceFile(archive.resolve("collection-status.json"), JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(Map.of("checkedAt", now.toString(), "statuses", result.statuses(), "indexedItems", result.articles().size())));
        return result;
    }

    private static byte[] fetchFeed(String url) throws Exception {
        FeedCache cached = FEED_CACHE.get(url);
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(18))
                .header("User-Agent", USER_AGENT).header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, */*");
        if (cached != null) {
            if (!cached.etag().isBlank()) request.header("If-None-Match", cached.etag());
            if (!cached.modified().isBlank()) request.header("If-Modified-Since", cached.modified());
        }
        var response = HTTP.send(request.GET().build(), ignored -> new LimitedBody(4_000_000));
        if (response.statusCode() == 304 && cached != null) return cached.body();
        if (response.statusCode() != 200) {
            String retry = response.headers().firstValue("Retry-After").orElse("");
            try { RETRY_AT.put(url, Instant.now().plusSeconds(Math.max(0, Long.parseLong(retry)))); }
            catch (RuntimeException ignored) {
                try { RETRY_AT.put(url, ZonedDateTime.parse(retry, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()); }
                catch (RuntimeException notADate) { }
            }
            throw new IOException("HTTP " + response.statusCode());
        }
        byte[] body = response.body();
        if (response.headers().firstValue("Content-Encoding").orElse("").equalsIgnoreCase("gzip")) {
            try (var stream = new java.util.zip.GZIPInputStream(new ByteArrayInputStream(body))) {
                body = stream.readNBytes(4_000_001);
                if (body.length > 4_000_000) throw new IOException("Response exceeds size limit");
            }
        }
        FEED_CACHE.put(url, new FeedCache(body, response.headers().firstValue("ETag").orElse(""),
                response.headers().firstValue("Last-Modified").orElse("")));
        return body;
    }

    static byte[] fetch(String url, String userAgent, int limit) throws Exception {
        if (!webUrl(url)) throw new IOException("Invalid HTTP(S) URL");
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(18))
                .header("User-Agent", userAgent).header("Accept", "application/rss+xml, application/atom+xml, application/json, application/xml, text/xml, */*")
                .GET().build();
        var response = HTTP.send(request, ignored -> new LimitedBody(limit));
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
        if (response.headers().firstValue("Content-Encoding").orElse("").equalsIgnoreCase("gzip")) {
            try (var stream = new java.util.zip.GZIPInputStream(new ByteArrayInputStream(response.body()))) {
                byte[] expanded = stream.readNBytes(limit + 1);
                if (expanded.length > limit) throw new IOException("Response exceeds size limit");
                return expanded;
            }
        }
        return response.body();
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final int limit;
        private long size;
        private Flow.Subscription subscription;
        LimitedBody(int limit) { this.limit = limit; }
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
        public void onNext(List<ByteBuffer> buffers) {
            size += buffers.stream().mapToLong(ByteBuffer::remaining).sum();
            if (size > limit) { subscription.cancel(); delegate.onError(new IOException("Response exceeds size limit")); }
            else delegate.onNext(buffers);
        }
        public void onError(Throwable error) { delegate.onError(error); }
        public void onComplete() { delegate.onComplete(); }
    }

    static List<Article> parse(Source source, byte[] xml, Instant now) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler() {
            @Override public void fatalError(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
        });
        var document = builder.parse(new ByteArrayInputStream(xml));
        String root = document.getDocumentElement().getLocalName();
        if (!Set.of("rss", "RDF", "feed").contains(root)) throw new IOException("Response is not RSS or Atom");
        var nodes = document.getElementsByTagNameNS("*", "item");
        if (nodes.getLength() == 0) nodes = document.getElementsByTagNameNS("*", "entry");
        List<Article> articles = new ArrayList<>();
        for (int index = 0; index < Math.min(nodes.getLength(), 250); index++) {
            Element item = (Element) nodes.item(index);
            String title = plain(child(item, "title"));
            String url = "";
            for (Node node = item.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element link && "link".equals(link.getLocalName())) {
                    if (link.hasAttribute("href")) {
                        if (!link.hasAttribute("rel") || "alternate".equals(link.getAttribute("rel"))) {
                            url = link.getAttribute("href"); break;
                        }
                    } else if (!link.getTextContent().isBlank()) { url = link.getTextContent().trim(); break; }
                }
            }
            if (url.isBlank()) url = child(item, "guid");
            try { url = URI.create(source.url()).resolve(url.trim()).toString(); }
            catch (IllegalArgumentException error) { continue; }
            if (title.isBlank() || !webUrl(url) || url.equals(source.url())) continue;
            String date = first(child(item, "pubDate"), child(item, "published"), child(item, "date"), child(item, "updated"));
            String summary = plain(first(child(item, "description"), child(item, "summary")));
            if (summary.length() > 2500) summary = summary.substring(0, 2500) + "…";
            String publisher = source.id().startsWith("google-") || !source.symbol().isEmpty()
                    ? first(plain(child(item, "source")), source.publisher()) : source.publisher();
            articles.add(new Article(source.id(), publisher, source.category(), title, url, summary,
                    date(date), now.toString(), now.toString()));
        }
        if (nodes.getLength() > 0 && articles.isEmpty()) throw new IOException("Feed items have no usable headlines/links");
        return articles;
    }

    private static String child(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getLocalName())) return element.getTextContent().trim();
        }
        return "";
    }

    static String date(String value) {
        if (blank(value)) return "";
        value = value.trim().replaceAll("\\s+", " ");
        for (var zone : Map.of("EST", "-0500", "EDT", "-0400", "CST", "-0600", "CDT", "-0500",
                "MST", "-0700", "MDT", "-0600", "PST", "-0800", "PDT", "-0700").entrySet()) {
            if (value.endsWith(" " + zone.getKey())) value = value.substring(0, value.length() - 3) + zone.getValue();
        }
        try { return Instant.parse(value).toString(); } catch (RuntimeException ignored) {}
        try { return OffsetDateTime.parse(value).toInstant().toString(); } catch (RuntimeException ignored) {}
        try { return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toString(); }
        catch (RuntimeException ignored) {}
        // Some RSS producers omit the weekday or use single-digit days.
        for (String pattern : List.of("d MMM uuuu HH:mm:ss Z", "EEE, d MMM uuuu HH:mm:ss z")) {
            try { return ZonedDateTime.parse(value, DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)).toInstant().toString(); }
            catch (RuntimeException ignored) {}
        }
        return "";
    }

    static String plain(String html) {
        if (html == null || html.isBlank()) return "";
        StringBuilder text = new StringBuilder();
        try {
            new ParserDelegator().parse(new StringReader(html), new HTMLEditorKit.ParserCallback() {
                private boolean hidden;
                @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int pos) {
                    if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE) hidden = true;
                }
                @Override public void handleEndTag(HTML.Tag tag, int pos) {
                    if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE) hidden = false;
                    text.append(' ');
                }
                @Override public void handleText(char[] data, int pos) { if (!hidden) text.append(data).append(' '); }
                @Override public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attributes, int pos) { text.append(' '); }
            }, true);
        } catch (IOException ignored) { return ""; }
        return text.toString().replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }

    static List<Article> merge(List<Article> previous, List<Article> incoming, Instant now) {
        Map<String, Article> merged = new LinkedHashMap<>();
        previous.forEach(article -> merged.put(article.key(), article));
        for (Article article : incoming) {
            Article old = merged.get(article.key());
            merged.put(article.key(), new Article(article.sourceId(), article.publisher(), article.category(),
                    article.title(), article.url(), article.summary(), article.publishedAt(),
                    old == null ? article.firstSeen() : old.firstSeen(), article.lastSeen(), article.symbols()));
        }
        Instant cutoff = now.minus(Duration.ofDays(30));
        return merged.values().stream().filter(article -> {
            try { return !Instant.parse(article.firstSeen()).isBefore(cutoff); }
            catch (RuntimeException error) { return false; }
        }).sorted(Comparator.comparing(Article::uncertainDate)
                .thenComparing(Comparator.comparing((Article article) -> Instant.parse(article.sortTime())).reversed())
                .thenComparing(Article::key))
                .limit(MAX_ITEMS).toList();
    }

    static String canonicalUrl(String url) {
        try {
            URI uri = URI.create(url);
            String query = uri.getRawQuery();
            if (query != null) query = java.util.Arrays.stream(query.split("&"))
                    .filter(part -> !part.matches("(?i)(utm_[^=]*|fbclid|gclid|ocid)=.*"))
                    .collect(java.util.stream.Collectors.joining("&"));
            return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority().toLowerCase(Locale.ROOT)
                    + uri.getRawPath() + (query == null || query.isBlank() ? "" : "?" + query);
        } catch (RuntimeException error) { return url; }
    }

    static boolean matches(Article article, String query, String topic) {
        String text = (article.title() + " " + article.summary() + " " + article.publisher()).toLowerCase(Locale.ROOT);
        for (String term : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) if (!text.contains(term)) return false;
        String pattern = switch (topic) {
            case "Conflict & security" -> "war|militar|missile|bomb|attack|iran|israel|ukraine|russia|nato|defen[cs]e|terror|ceasefire";
            case "Trade & policy" -> "tariff|sanction|trade|election|trump|congress|legislation|export|import|regulat";
            case "Energy" -> "oil|gas|energy|opec|petroleum|crude|pipeline|electric|nuclear";
            case "Technology" -> "tech|chip|semiconductor|software|artificial intelligence|\\bai\\b|cyber|nvidia|apple|microsoft";
            case "Rates & economy" -> "inflation|interest rate|federal reserve|central bank|unemployment|jobs|gdp|econom|treasur|bond";
            case "Company results" -> "earning|revenue|profit|quarter|sales|guidance|dividend|buyback";
            case "Healthcare" -> "health|pharma|drug|biotech|fda|vaccine|hospital";
            default -> "";
        };
        return pattern.isEmpty() || java.util.regex.Pattern.compile(pattern).matcher(text).find();
    }

    static boolean webUrl(String value) {
        try {
            URI uri = URI.create(value);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (RuntimeException error) { return false; }
    }
    static String errorMessage(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        if (error instanceof java.net.http.HttpTimeoutException) return "Timed out";
        String message = error.getMessage();
        return blank(message) ? error.getClass().getSimpleName() : message.substring(0, Math.min(message.length(), 160));
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String first(String... values) { for (String value : values) if (!value.isBlank()) return value; return ""; }
}

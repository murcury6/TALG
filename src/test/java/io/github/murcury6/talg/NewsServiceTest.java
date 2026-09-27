package io.github.murcury6.talg;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NewsServiceTest {
    @TempDir Path root;
    private static final Instant NOW = Instant.parse("2026-09-23T03:00:00Z");
    private static final NewsService.Source SOURCE = new NewsService.Source("test", "Test publisher", "World", "World", "https://example.com/feed", true);

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String rss(String title, String description) {
        return "<rss version='2.0'><channel><item><title>" + title + "</title><link>https://example.com/news/1?utm_source=rss</link>"
                + "<pubDate>Tue, 22 Sep 2026 19:30:00 -0400</pubDate><description><![CDATA[" + description + "]]></description></item></channel></rss>";
    }

    @Test void rssRetainsUntickeredWorldNewsAndConvertsDatesWithoutExecutingMarkup() throws Exception {
        var items = NewsService.parse(SOURCE, bytes(rss("Military escalation in Iran", "<p>Oil &amp; shipping.</p><script>alert(1)</script>")), NOW);
        assertEquals(1, items.size());
        assertEquals("2026-09-22T23:30:00Z", items.getFirst().publishedAt());
        assertEquals("Oil & shipping.", items.getFirst().summary());
        assertTrue(NewsService.matches(items.getFirst(), "", "All topics"));
        assertTrue(NewsService.matches(items.getFirst(), "Iran", "Conflict & security"));
        assertFalse(NewsService.matches(items.getFirst(), "Iran semiconductor", "All topics"));
    }

    @Test void atomSupportsNamespacesAlternateRelativeLinksAndUnknownDates() throws Exception {
        var xml = "<feed xmlns='http://www.w3.org/2005/Atom'><entry><title>Tariff update</title>"
                + "<link rel='self' href='https://example.com/api'/><link rel='alternate' href='/story'/>"
                + "<updated>bad timestamp</updated><summary>Text</summary></entry></feed>";
        var item = NewsService.parse(SOURCE, bytes(xml), NOW).getFirst();
        assertEquals("https://example.com/story", item.url());
        assertEquals("", item.publishedAt());
        assertEquals(NOW.toString(), item.firstSeen());
    }

    @Test void publisherDateWhitespaceAndExplicitUsTimeZonesAreHandled() {
        assertEquals("2026-09-22T14:00:00Z", NewsService.date("Tue, 22 Sep 2026  09:00:00 EST"));
        assertEquals("2026-09-22T13:00:00Z", NewsService.date("Tue, 22 Sep 2026 09:00:00 EDT"));
    }

    @Test void futurePublisherDatesCannotCrowdOutNormallyDatedNews() throws Exception {
        var actual = NewsService.parse(SOURCE, bytes(rss("Actual", "Actual")), NOW).getFirst();
        var future = new NewsService.Article("other", "Other", "World", "Future clock", "https://example.com/future",
                "", NOW.plusSeconds(3600).toString(), NOW.toString(), NOW.toString());
        var result = NewsService.merge(List.of(), List.of(future, actual), NOW);
        assertEquals("Actual", result.getFirst().title());
        assertTrue(result.getLast().uncertainDate());
        var revised = new NewsService.Article("test", "Publisher", "World", "Updated story", "https://example.com/update",
                "", NOW.minusSeconds(60).toString(), NOW.minusSeconds(3600).toString(), NOW.toString());
        assertFalse(revised.uncertainDate());
        assertEquals(revised.publishedAt(), revised.sortTime());
    }

    @Test void rdfAndGoogleDiscoveryRetainOriginalPublisher() throws Exception {
        var google = new NewsService.Source("google-test", "Google News discovery", "World", "World", "https://news.google.com/rss", true);
        var xml = "<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#' xmlns='http://purl.org/rss/1.0/'>"
                + "<item><title>Headline</title><link>https://example.com/story</link><source>Original publisher</source></item></rdf:RDF>";
        assertEquals("Original publisher", NewsService.parse(google, bytes(xml), NOW).getFirst().publisher());
    }

    @Test void rejectsExternalEntitiesHtmlResponsesAndUnsafeArticleSchemes() {
        assertThrows(Exception.class, () -> NewsService.parse(SOURCE, bytes("<!DOCTYPE rss [<!ENTITY x SYSTEM 'file:///private'>]><rss><channel>&x;</channel></rss>"), NOW));
        assertThrows(Exception.class, () -> NewsService.parse(SOURCE, bytes("<html><body>Access denied</body></html>"), NOW));
        assertThrows(Exception.class, () -> NewsService.parse(SOURCE, bytes("<rss><channel><item><title>X</title><link>javascript:alert(1)</link></item></channel></rss>"), NOW));
        assertFalse(NewsService.webUrl("https://user:secret@example.com/rss"));
    }

    @Test void mergeKeepsOtherPublishersPreservesFirstSeenAndUpdatesSameSource() throws Exception {
        var old = NewsService.parse(SOURCE, bytes(rss("Original", "Original")), NOW.minusSeconds(60)).getFirst();
        var update = NewsService.parse(SOURCE, bytes(rss("Revised", "Revised")), NOW).getFirst();
        var otherSource = new NewsService.Source("second", "Other publisher", "World", "World", SOURCE.url(), true);
        var other = NewsService.parse(otherSource, bytes(rss("Revised", "Revised")), NOW).getFirst();
        var result = NewsService.merge(List.of(old), List.of(update, other), NOW);
        assertEquals(2, result.size());
        var updated = result.stream().filter(a -> a.sourceId().equals("test")).findFirst().orElseThrow();
        assertEquals(old.firstSeen(), updated.firstSeen());
        assertEquals("Revised", updated.title());
        assertEquals("https://example.com/news/1", NewsService.canonicalUrl(updated.url()));
    }

    @Test void collectionIsDailyPerSourceFilesWithRevisionsAndNoDuplicatePollRows() throws Exception {
        NewsService service = new NewsService(root);
        byte[] original = bytes(rss("Original", "One"));
        byte[] revision = bytes(rss("Revised", "Two"));
        service.archive(SOURCE, original, NewsService.parse(SOURCE, original, NOW), NOW);
        service.archive(SOURCE, original, NewsService.parse(SOURCE, original, NOW.plusSeconds(30)), NOW.plusSeconds(30));
        service.archive(SOURCE, revision, NewsService.parse(SOURCE, revision, NOW.plusSeconds(60)), NOW.plusSeconds(60));
        Path folder = root.resolve("data/news/2026-09-23");
        List<String> lines = Files.readAllLines(folder.resolve("test.jsonl"));
        assertEquals(2, lines.size());
        assertEquals("Original", NewsService.JSON.readTree(lines.getFirst()).path("article").path("title").asText());
        assertEquals("Revised", NewsService.JSON.readTree(lines.getLast()).path("article").path("title").asText());
        assertArrayEquals(revision, Files.readAllBytes(folder.resolve("test.xml")));
        // Trimming the browsing index never deletes permanent collection files.
        assertTrue(NewsService.merge(NewsService.parse(SOURCE, original, NOW), List.of(), NOW.plusSeconds(31L * 86400)).isEmpty());
        assertTrue(Files.exists(folder.resolve("test.jsonl")));
    }

    @Test void sourceRegistryAndBrowsingIndexRoundTripWithoutSecrets() throws Exception {
        NewsService service = new NewsService(root);
        assertTrue(service.sources().size() >= 50);
        service.saveSources(List.of(SOURCE));
        assertEquals(List.of(SOURCE), service.sources());
        var now = Instant.now();
        var snapshot = new NewsService.Snapshot(NewsService.parse(SOURCE, bytes(rss("Headline", "Text")), now), List.of());
        service.save(snapshot);
        assertEquals(snapshot, service.load());
        assertThrows(Exception.class, () -> service.saveSources(List.of(SOURCE, SOURCE)));
    }

    @Test void dedicatedStockFeedRetainsAssociationWithoutRequiringTickerInHeadline() throws Exception {
        var source = NewsService.stockSource(" aapl ");
        assertEquals("AAPL", source.symbol());
        assertTrue(source.url().contains("s=AAPL&"));
        var article = NewsService.parse(source, bytes(rss("New phone launched", "Company announcement")), NOW).getFirst();
        assertEquals(source.id(), article.sourceId());
        var service = new NewsService(root);
        service.saveSources(List.of(SOURCE, source));
        assertEquals(List.of(SOURCE, source), service.sources());
        assertThrows(Exception.class, () -> NewsService.stockSource("AAPL&other=SPY"));
        assertNotEquals(NewsService.stockSource("BRK-B").id(), NewsService.stockSource("BRK.B").id());
        assertEquals("", NewsService.JSON.readValue("{\"id\":\"old\",\"publisher\":\"P\",\"name\":\"N\",\"category\":\"World\",\"url\":\"https://example.com\",\"enabled\":true}", NewsService.Source.class).symbol());
    }

    @Test void concurrentCollectorReadsCacheWithoutOverwritingArchive() throws Exception {
        var service = new NewsService(root);
        var saved = new NewsService.Snapshot(NewsService.parse(SOURCE, bytes(rss("Retained", "Text")), Instant.now()), List.of());
        service.save(saved);
        try (var channel = java.nio.channels.FileChannel.open(root.resolve("work/news/collection.lock"), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            assertEquals(saved, service.collect(ignored -> fail("Must not fetch while another collector owns lock")));
        }
    }
}

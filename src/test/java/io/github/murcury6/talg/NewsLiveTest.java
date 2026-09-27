package io.github.murcury6.talg;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit integration check also populates the user's requested local news collection. */
@EnabledIfSystemProperty(named = "talg.liveNews", matches = "true")
class NewsLiveTest {
    @Test void collectRealFeedsAndRecordHonestSourceHealth() throws Exception {
        var service = new NewsService(Path.of(""));
        var sources = service.sources();
        var result = service.collect(ignored -> {});
        for (var status : result.statuses()) {
            var newest = result.articles().stream().filter(a -> a.sourceId().equals(status.sourceId()))
                    .map(NewsService.Article::publishedAt).filter(s -> !s.isBlank()).max(String::compareTo).orElse("unknown");
            System.out.println(status.sourceId() + " | " + status.state() + " | " + status.count() + " items | newest " + newest);
        }
        System.out.println("COLLECTION " + result.articles().size() + " items from " + result.articles().stream().map(NewsService.Article::publisher).distinct().count() + " attributed publishers");
        assertTrue(result.statuses().stream().filter(s -> s.state().equals("OK")).count() >= 20);
        assertTrue(result.articles().size() > 200);
        Path report = Path.of("work/news/live-check.json");
        NewsService.JSON.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), Map.of("checkedAt", Instant.now().toString(), "statuses", result.statuses(), "items", result.articles().size()));
    }
}

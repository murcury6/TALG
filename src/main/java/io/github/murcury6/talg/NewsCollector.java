package io.github.murcury6.talg;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;

/** News-only background entry point. No trading operations. */
public final class NewsCollector {
    public static void main(String[] args) throws Exception {
        boolean watch = args.length == 1 && args[0].equals("--watch");
        if (args.length > 0 && !watch) throw new IllegalArgumentException("Usage: NewsCollector [--watch]");
        Path directory = Path.of("work/news");
        Files.createDirectories(directory);
        try (var channel = FileChannel.open(directory.resolve("collector.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("News collector is already running");
            var service = new NewsService(Path.of(""));
            var configFile = directory.resolve("collector-config.json");
            if (!Files.exists(configFile)) NewsService.replaceFile(configFile, NewsService.JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(Map.of("streamEnabled", true, "rssIntervalSeconds", 60)));
            var config = NewsService.JSON.readTree(Files.readAllBytes(configFile));
            if (watch && config.path("streamEnabled").asBoolean(true))
                Thread.ofPlatform().daemon(true).name("live-news").start(new NewsStream(Path.of("")));
            do {
                long started = System.nanoTime();
                config = NewsService.JSON.readTree(Files.readAllBytes(configFile));
                int interval = Math.max(30, Math.min(3600, config.path("rssIntervalSeconds").asInt(60)));
                try {
                    var result = service.collect(System.out::println);
                    var sources = service.sources();
                    var enabled = sources.stream().filter(NewsService.Source::enabled).map(NewsService.Source::id).collect(java.util.stream.Collectors.toSet());
                    long responding = result.statuses().stream().filter(s -> enabled.contains(s.sourceId()) && (s.state().equals("OK") || s.state().equals("Empty feed"))).count();
                    var report = Map.of("completedAt", Instant.now().toString(), "pid", ProcessHandle.current().pid(),
                            "watch", watch, "intervalSeconds", interval, "enabledFeeds", enabled.size(), "respondingFeeds", responding,
                            "indexedItems", result.articles().size(), "stockTickers", sources.stream().filter(NewsService.Source::enabled)
                                    .map(NewsService.Source::symbol).filter(s -> !s.isEmpty()).distinct().count());
                    NewsService.JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("collector-status.json").toFile(), report);
                    System.out.println(NewsService.JSON.writeValueAsString(report));
                } catch (Exception error) {
                    System.err.println(Instant.now() + " Collection failed: " + NewsService.errorMessage(error));
                    if (!watch) throw error;
                }
                if (watch) Thread.sleep(Math.max(1000, interval * 1000L - (System.nanoTime() - started) / 1_000_000));
            } while (watch);
        }
    }
}

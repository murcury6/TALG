package io.github.murcury6.talg;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/** Read-only, all-symbol news subscription. Credentials go only to Alpaca's fixed endpoint. */
final class NewsStream implements Runnable {
    static final URI ENDPOINT = URI.create("wss://stream.data.alpaca.markets/v1beta1/news");
    private final Path root;
    private long articles;
    private String lastArticleAt = "";
    private final LinkedHashSet<String> fingerprints = new LinkedHashSet<>();
    private java.util.List<NewsService.Article> index;

    NewsStream(Path root) { this.root = root; }

    @Override public void run() {
        int retry = 2;
        while (!Thread.currentThread().isInterrupted()) {
            WebSocket socket = null;
            try {
                var saved = new CredentialStore().load().orElseThrow(() -> new IllegalStateException("No saved Alpaca connection"));
                var settings = saved.settings();
                var listener = new Listener();
                status("CONNECTING", "", "");
                socket = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                        .newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                        .header("APCA-API-KEY-ID", settings.apiKey()).header("APCA-API-SECRET-KEY", settings.apiSecret())
                        .buildAsync(ENDPOINT, listener).get(15, TimeUnit.SECONDS);
                try { listener.ready.get(20, TimeUnit.SECONDS); }
                catch (Exception error) { throw new IllegalStateException("News subscription was not confirmed"); }
                retry = 2;
                while (!listener.closed.isDone()) {
                    try { listener.closed.get(25, TimeUnit.SECONDS); }
                    catch (java.util.concurrent.TimeoutException expected) {
                        socket.sendPing(ByteBuffer.wrap(new byte[]{1})).get(10, TimeUnit.SECONDS);
                        if (Duration.between(listener.lastMessage, Instant.now()).toSeconds() > 60)
                            throw new IllegalStateException("News heartbeat timed out");
                        status("SUBSCRIBED", listener.lastMessage.toString(), "");
                    }
                }
                throw new IllegalStateException("News socket closed");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                // Error class only: never persist HTTP request headers or credential-bearing objects.
                try { status("RECONNECTING", "", error.getClass().getSimpleName()); } catch (Exception ignored) { }
            } finally { if (socket != null) socket.abort(); }
            if (Thread.currentThread().isInterrupted()) break;
            try { Thread.sleep(retry * 1000L); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            retry = Math.min(60, retry * 2);
        }
    }

    synchronized void status(String state, String lastMessage, String error) throws Exception {
        Path path = root.resolve("work/news/stream-status.json");
        Files.createDirectories(path.getParent());
        NewsService.replaceFile(path, NewsService.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of(
                "checkedAt", Instant.now().toString(), "pid", ProcessHandle.current().pid(), "state", state,
                "endpoint", ENDPOINT.toString(), "subscription", "*", "receivedArticles", articles,
                "lastArticleAt", lastArticleAt, "lastMessageAt", lastMessage, "error", error)));
    }

    synchronized void archive(JsonNode event, Instant now) throws Exception {
        if (!event.path("T").asText().equals("n") || event.path("id").isMissingNode()
                || event.path("headline").asText().isBlank()) throw new IllegalArgumentException("Invalid news event");
        String fingerprint = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(NewsService.JSON.writeValueAsBytes(event)));
        if (fingerprints.contains(fingerprint)) return;
        Path directory = root.resolve("data/news-stream");
        Files.createDirectories(directory);
        String row = NewsService.JSON.writeValueAsString(Map.of("receivedAt", now.toString(), "fingerprint", fingerprint, "event", event)) + "\n";
        Files.writeString(directory.resolve(now.toString().substring(0, 10) + ".jsonl"), row,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        Path indexFile = root.resolve("work/news/stream-articles.json");
        Files.createDirectories(indexFile.getParent());
        if (index == null) index = Files.exists(indexFile) ? NewsService.JSON.readValue(Files.readAllBytes(indexFile),
                new com.fasterxml.jackson.core.type.TypeReference<java.util.List<NewsService.Article>>() {}) : java.util.List.of();
        var symbols = new java.util.ArrayList<String>();
        for (var symbol : event.path("symbols")) symbols.add(symbol.asText());
        String url = event.path("url").asText();
        if (NewsService.webUrl(url)) {
            var article = new NewsService.Article("alpaca-news", event.path("source").asText("Alpaca news"), "Stocks & ETFs",
                    NewsService.plain(event.path("headline").asText()), url, NewsService.plain(event.path("summary").asText()),
                    NewsService.date(event.path("created_at").asText()), now.toString(), now.toString(), symbols);
            index = NewsService.merge(index, java.util.List.of(article), now);
            NewsService.replaceFile(indexFile, NewsService.JSON.writeValueAsBytes(index));
        }
        fingerprints.add(fingerprint);
        if (fingerprints.size() > 20_000) fingerprints.removeFirst();
        articles++; lastArticleAt = now.toString();
    }

    final class Listener implements WebSocket.Listener {
        private final StringBuilder message = new StringBuilder();
        final CompletableFuture<Void> ready = new CompletableFuture<>(), closed = new CompletableFuture<>();
        volatile Instant lastMessage = Instant.now();
        public void onOpen(WebSocket socket) { socket.request(1); }
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            try {
                if (message.length() + data.length() > 4_000_000) throw new IllegalArgumentException("Oversized news frame");
                message.append(data);
                if (last) {
                    JsonNode batch = NewsService.JSON.readTree(message.toString()); message.setLength(0);
                    if (!batch.isArray()) throw new IllegalArgumentException("Expected news event array");
                    for (JsonNode event : batch) {
                        lastMessage = Instant.now();
                        switch (event.path("T").asText()) {
                            case "success" -> {
                                if (event.path("msg").asText().equals("authenticated"))
                                    socket.sendText("{\"action\":\"subscribe\",\"news\":[\"*\"]}", true);
                            }
                            case "subscription" -> {
                                boolean all = false;
                                for (var symbol : event.path("news")) if (symbol.asText().equals("*")) all = true;
                                if (!all) throw new IllegalStateException("All-news subscription not confirmed");
                                ready.complete(null); status("SUBSCRIBED", lastMessage.toString(), "");
                            }
                            case "n" -> { archive(event, lastMessage); status("SUBSCRIBED", lastMessage.toString(), ""); }
                            case "error" -> {
                                status("PROVIDER_ERROR", lastMessage.toString(), "Provider code " + event.path("code").asInt());
                                ready.completeExceptionally(new IllegalStateException("Provider rejected subscription"));
                                closed.complete(null); socket.abort();
                            }
                            default -> { }
                        }
                    }
                }
                socket.request(1);
            } catch (Exception error) {
                ready.completeExceptionally(error); closed.complete(null); socket.abort();
                try { status("STREAM_ERROR", lastMessage.toString(), error.getClass().getSimpleName()); } catch (Exception ignored) { }
            }
            return CompletableFuture.completedFuture(null);
        }
        public CompletionStage<?> onPong(WebSocket socket, ByteBuffer data) {
            lastMessage = Instant.now(); socket.request(1); return CompletableFuture.completedFuture(null);
        }
        public CompletionStage<?> onPing(WebSocket socket, ByteBuffer data) {
            lastMessage = Instant.now(); socket.request(1); return socket.sendPong(data);
        }
        public CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
            closed.complete(null); return CompletableFuture.completedFuture(null);
        }
        public void onError(WebSocket socket, Throwable error) { closed.complete(null); ready.completeExceptionally(error); }
    }
}

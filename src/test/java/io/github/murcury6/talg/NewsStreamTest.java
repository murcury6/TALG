package io.github.murcury6.talg;

import static org.junit.jupiter.api.Assertions.*;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NewsStreamTest {
    @TempDir Path root;
    private final ArrayList<String> sent = new ArrayList<>();
    private WebSocket socket() {
        return (WebSocket) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{WebSocket.class}, (proxy, method, args) -> {
            if (method.getName().equals("sendText")) sent.add(args[0].toString());
            if (method.getReturnType().equals(CompletableFuture.class)) return CompletableFuture.completedFuture(proxy);
            if (method.getReturnType().equals(boolean.class)) return false;
            return null;
        });
    }
    @Test void authenticatesBeforeSubscribingAndHandlesFragmentedFrames() throws Exception {
        var stream = new NewsStream(root); var listener = stream.new Listener(); var socket = socket();
        listener.onText(socket, "[{\"T\":\"success\",\"msg\":\"connected\"}]", true);
        assertTrue(sent.isEmpty());
        listener.onText(socket, "[{\"T\":\"success\",", false);
        listener.onText(socket, "\"msg\":\"authenticated\"}]", true);
        assertEquals(java.util.List.of("{\"action\":\"subscribe\",\"news\":[\"*\"]}"), sent);
        listener.onText(socket, "[{\"T\":\"subscription\",\"news\":[\"*\"]}]", true);
        assertTrue(listener.ready.isDone()); assertFalse(listener.ready.isCompletedExceptionally());
        assertEquals("SUBSCRIBED", NewsService.JSON.readTree(Files.readAllBytes(root.resolve("work/news/stream-status.json"))).path("state").asText());
    }
    @Test void archivesAllSymbolsAndRevisionsImmediatelyWithoutDuplicateFrames() throws Exception {
        var stream = new NewsStream(root);
        var event = NewsService.JSON.readTree("{\"T\":\"n\",\"id\":1,\"headline\":\"Company news\",\"url\":\"https://example.com/news/1\",\"symbols\":[\"AAPL\",\"UNLISTED\"],\"source\":\"test\"}");
        var now = Instant.now();
        stream.archive(event, now); stream.archive(event, now);
        ((com.fasterxml.jackson.databind.node.ObjectNode) event).put("headline", "Revised news");
        stream.archive(event, now);
        var rows = Files.readAllLines(root.resolve("data/news-stream/" + now.toString().substring(0, 10) + ".jsonl"));
        assertEquals(2, rows.size());
        assertEquals("UNLISTED", NewsService.JSON.readTree(rows.getFirst()).path("event").path("symbols").get(1).asText());
        var collected = new NewsService(root).load().articles();
        assertEquals(1, collected.size());
        assertEquals("Revised news", collected.getFirst().title());
        assertEquals(java.util.List.of("AAPL", "UNLISTED"), collected.getFirst().symbols());
    }
    @Test void providerDenialCannotLookLikeSuccessfulSubscription() throws Exception {
        var stream = new NewsStream(root); var listener = stream.new Listener();
        listener.onText(socket(), "[{\"T\":\"error\",\"code\":403,\"msg\":\"denied\"}]", true);
        assertTrue(listener.ready.isCompletedExceptionally()); assertTrue(listener.closed.isDone());
        assertTrue(sent.isEmpty());
    }
}

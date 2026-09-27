package io.github.murcury6.talg;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** A durable notification stream; events request evaluation and cannot submit orders. */
final class NewsModelEvents {
    static JsonNode read(Path root) throws java.io.IOException {
        return read(root, null);
    }
    static JsonNode read(Path root, String reference) throws java.io.IOException {
        Path file = root.resolve("work/news-tensor/news-events.json");
        if (reference != null) {
            var profiles = new ModelProfiles(root); profiles.revision(reference);
            file = profiles.revisionFolder(reference).resolve("runtime/news-events.json");
            if (Files.exists(file)) {
                var stream = PaperTestRunner.JSON.readTree(file.toFile());
                if (!reference.equals(stream.path("profile_revision").asText())) throw new java.io.IOException("News event profile does not match pinned revision");
                return stream;
            }
        }
        return Files.exists(file) ? PaperTestRunner.JSON.readTree(file.toFile()) : PaperTestRunner.JSON.createObjectNode().put("latest", 0).set("events", PaperTestRunner.JSON.createArrayNode());
    }
    static long latest(Path root, String reference) throws java.io.IOException { return read(root, reference).path("latest").asLong(); }
    static long latest(Path root) throws java.io.IOException { return read(root).path("latest").asLong(); }
    static List<JsonNode> after(JsonNode stream, long cursor) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode event : stream.path("events")) if (event.path("sequence").asLong() > cursor) result.add(event);
        return result;
    }
}

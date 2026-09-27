package io.github.murcury6.talg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Named editable models and immutable, content-addressed dependency revisions. */
final class ModelProfiles {
    private final Path root, index;
    ModelProfiles(Path root) { this.root = root.toAbsolutePath().normalize(); index = this.root.resolve("work/model-profiles/profiles.json"); }
    boolean enabled() { return Files.exists(index); }
    static String kind(boolean news) { return news ? "news" : "trade"; }
    private static void kind(String kind) { if (!Set.of("news", "trade", "stocks").contains(kind)) throw new IllegalArgumentException("Unknown model kind"); }
    private ObjectNode read() throws IOException {
        if (enabled()) return (ObjectNode) PaperTestRunner.JSON.readTree(index.toFile());
        var value = PaperTestRunner.JSON.createObjectNode(); value.put("version", 1);
        var selected = value.putObject("selected"); var profiles = value.putObject("profiles");
        for (String kind : List.of("news", "trade", "stocks")) {
            selected.put(kind, "default"); var entry = profiles.putObject(kind).putObject("default");
            entry.put("name", "Default"); entry.putObject("dependencies"); var files = entry.putObject("files");
            if (kind.equals("news")) { files.put("source", "work/models/news-rating.talg"); files.put("config", "work/models/news-tensor.json"); files.put("inputs", "work/models/news-inputs.json"); }
            if (kind.equals("trade")) files.put("config", "work/strategies/rapid-paper.json");
            if (kind.equals("stocks")) { files.put("source", "work/models/stock-selection.talg"); files.put("inputs", "work/models/stock-selection-inputs.json"); files.put("trading", "work/strategies/rapid-paper.json"); }
        }
        return value;
    }
    private void write(ObjectNode value) throws IOException {
        Files.createDirectories(index.getParent()); NewsService.replaceFile(index, PaperTestRunner.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
    }
    void initialize() throws IOException { synchronized (ModelProfiles.class) { if (!enabled()) write(read()); } }
    String selected(String kind) throws IOException { kind(kind); return read().path("selected").path(kind).asText("default"); }
    Map<String, String> list(String kind) throws IOException {
        kind(kind); var names = new LinkedHashMap<String, String>(); read().path("profiles").path(kind).fields().forEachRemaining(e -> names.put(e.getKey(), e.getValue().path("name").asText())); return names;
    }
    private JsonNode entry(JsonNode value, String kind, String id) throws IOException {
        kind(kind); JsonNode entry = value.path("profiles").path(kind).path(id);
        if (!entry.isObject()) throw new IOException("Missing " + kind + " profile: " + id); return entry;
    }
    void select(String kind, String id) throws IOException {
        synchronized (ModelProfiles.class) { var value = read(); entry(value, kind, id); ((ObjectNode)value.path("selected")).put(kind, id); write(value); }
    }
    Path file(String kind, String id, String role) throws IOException {
        String relative = entry(read(), kind, id).path("files").path(role).asText();
        if (relative.isBlank()) throw new IOException("Missing " + role + " file for " + kind);
        Path path = root.resolve(relative).normalize(); if (!path.startsWith(root)) throw new IOException("Model file must be inside the project"); return path;
    }
    JsonNode dependencies(String kind, String id) throws IOException { return entry(read(), kind, id).path("dependencies").deepCopy(); }
    String cloneProfile(String kind, String from, String name) throws IOException {
        if (name == null || name.isBlank() || name.strip().length() > 60) throw new IOException("Use a profile name of 1–60 characters");
        synchronized (ModelProfiles.class) {
            var value = read(); var original = entry(value, kind, from);
            for (var existing : value.path("profiles").path(kind)) if (existing.path("name").asText().equalsIgnoreCase(name.strip())) throw new IOException("That profile name already exists");
            String id = UUID.randomUUID().toString(); var copy = original.deepCopy(); ((ObjectNode)copy).put("name", name.strip()); ((ObjectNode)copy).remove("head");
            var files = (ObjectNode)copy.path("files"); var roles = new ArrayList<String>(); files.fieldNames().forEachRemaining(roles::add);
            for (String role : roles) {
                Path source = file(kind, from, role); String relative = "work/model-profiles/" + kind + "/" + id + "/" + source.getFileName();
                Path target = root.resolve(relative); Files.createDirectories(target.getParent());
                if (Files.exists(source)) Files.copy(source, target); else if (role.equals("inputs")) Files.writeString(target, "{}"); else throw new IOException("Save the source model before cloning: " + source);
                files.put(role, relative);
            }
            ((ObjectNode)value.path("profiles").path(kind)).set(id, copy); write(value); return id;
        }
    }
    void bind(String kind, String id, Map<String, String> references) throws IOException {
        synchronized (ModelProfiles.class) {
            var value = read(); var target = (ObjectNode)entry(value, kind, id); var deps = target.putObject("dependencies");
            for (var link : references.entrySet()) {
                if (!(kind.equals("trade") && Set.of("news", "stocks").contains(link.getKey()) || kind.equals("stocks") && link.getKey().equals("news"))) throw new IOException("Unsupported dependency; dependencies must flow Trade → Stocks → News or Trade → News");
                var revision = revision(link.getValue()); if (!revision.path("kind").asText().equals(link.getKey())) throw new IOException("Wrong dependency type");
                deps.put(link.getKey(), link.getValue());
            }
            target.remove("head"); write(value);
        }
    }
    String snapshot(String kind, String id) throws IOException {
        synchronized (ModelProfiles.class) {
            var value = read(); var profile = (ObjectNode)entry(value, kind, id);
            var deps = (ObjectNode)profile.path("dependencies");
            if (!kind.equals("news") && !deps.has("news") && Files.exists(file("news", "default", "config")) && Files.exists(file("news", "default", "source"))) deps.put("news", snapshot("news", "default"));
            if (kind.equals("trade") && !deps.has("stocks") && Files.exists(file("stocks", "default", "source"))) deps.put("stocks", snapshot("stocks", "default"));
            var payload = PaperTestRunner.JSON.createObjectNode();
            payload.put("kind", kind).put("profile", id).put("name", profile.path("name").asText());
            payload.set("dependencies", profile.path("dependencies").deepCopy()); var contents = payload.putObject("contents");
            var fields = profile.path("files").fields();
            while (fields.hasNext()) { var field = fields.next(); Path path = file(kind, id, field.getKey());
                if (Files.exists(path)) contents.put(field.getKey(), Files.readString(path).replace("\r\n", "\n").replaceFirst("^\ufeff", ""));
                else if (field.getKey().equals("inputs")) contents.put(field.getKey(), "{}"); else throw new IOException("Missing model file: " + path);
            }
            for (var link : payload.path("dependencies")) revision(link.asText());
            byte[] bytes = PaperTestRunner.JSON.writeValueAsBytes(payload); String hash = hash(bytes); Path manifest = revisionFolder(hash).resolve("manifest.json");
            if (!Files.exists(manifest)) { Files.createDirectories(manifest.getParent()); NewsService.replaceFile(manifest, bytes); }
            // Recursive snapshots may have updated other profiles; preserve those writes.
            var latest = read(); var saved = (ObjectNode)entry(latest, kind, id);
            saved.set("dependencies", payload.path("dependencies").deepCopy()); saved.put("head", hash); write(latest); return hash;
        }
    }
    Path revisionFolder(String reference) throws IOException {
        if (reference == null || !reference.matches("[0-9a-f]{64}")) throw new IOException("Invalid model revision");
        return root.resolve("work/model-profiles/revisions/" + reference);
    }
    JsonNode revision(String reference) throws IOException {
        byte[] bytes = Files.readAllBytes(revisionFolder(reference).resolve("manifest.json"));
        if (!hash(bytes).equals(reference)) throw new IOException("Pinned model revision was modified: " + reference);
        return PaperTestRunner.JSON.readTree(bytes);
    }
    String newsReference(String kind, String id) throws IOException {
        if (kind.equals("news")) return snapshot(kind, id);
        String ref = dependencies(kind, id).path("news").asText();
        if (ref.isBlank()) throw new IOException("Choose a pinned News profile in More → Profile links");
        revision(ref); return ref;
    }
    String label(String ref) throws IOException { var value = revision(ref); return value.path("name").asText() + " · " + ref.substring(0, 8); }
    static String hash(byte[] bytes) throws IOException {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }
}

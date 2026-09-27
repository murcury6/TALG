package io.github.murcury6.talg;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** File-based local worker calls; never receives broker credentials. */
final class ModelBridge {
    private final Path root;
    private String kind, profile;
    void profile(String kind, String profile) { this.kind = kind; this.profile = profile; }
    ModelBridge(Path root) { this.root = root.toAbsolutePath().normalize(); }
    JsonNode call(String command, String code, int offset, String search) throws Exception {
        Path result = Files.createTempFile("talg-model-result-", ".json");
        Path source = Files.createTempFile("talg-model-code-", ".talg");
        Path log = Files.createTempFile("talg-model-worker-", ".log");
        try {
            if (code != null) Files.writeString(source, code);
            Path python = root.resolve("work/news-tensor/venv/Scripts/python.exe");
            if (!Files.exists(python)) python = root.resolve(".pixi/envs/default/python.exe");
            if (!Files.exists(python)) throw new IOException("Python model runtime is missing; run tools/setup-news-tensor.ps1.");
            var args = new ArrayList<>(List.of(python.toString(), "-m", "talg_py.model_workbench", command,
                    "--root", root.toString(), "--out", result.toString(), "--offset", String.valueOf(offset), "--search", search));
            long runTimeout = 600;
            if (kind != null && !command.endsWith("validate") && !command.equals("validate")) {
                var profiles = new ModelProfiles(root); String ref = profiles.snapshot(kind, profile);
                args.add("--revision"); args.add(ref);
                String newsRef = kind.equals("news") ? ref : profiles.revision(ref).path("dependencies").path("news").asText();
                if (!newsRef.isBlank()) {
                    JsonNode config = PaperTestRunner.JSON.readTree(profiles.revision(newsRef).path("contents").path("config").asText());
                    if (config.path("engine").asText().equals("symbol_weighted_tensor_v1")) runTimeout = 3600;
                }
            }
            if (code != null) { args.add("--code"); args.add(source.toString()); }
            ProcessBuilder builder = new ProcessBuilder(args).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
            AlpacaProcessEnvironment.scrub(builder);
            Process process = builder.start();
            if (!process.waitFor((command.equals("run") || command.equals("refresh-news")) ? runTimeout : 90, TimeUnit.SECONDS)) {
                process.destroyForcibly(); throw new IOException("Model operation timed out; retained data remains available.");
            }
            JsonNode response = Files.size(result) > 0 ? PaperTestRunner.JSON.readTree(result.toFile()) : null;
            if (response != null && response.has("error")) throw new IOException(response.path("error").asText());
            if (process.exitValue() != 0 || response == null) {
                String error = Files.readString(log); throw new IOException("Model worker failed: " + error.substring(0, Math.min(1500, error.length())));
            }
            return response;
        } finally { Files.deleteIfExists(result); Files.deleteIfExists(source); Files.deleteIfExists(log); }
    }
}

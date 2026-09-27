package io.github.murcury6.talg;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

/** Syntax-checks trusted local R source before replacing an existing saved script. */
final class RScriptFiles {
    private RScriptFiles() {}

    static void save(Path projectRoot, Path destination, String contents) throws Exception {
        if (contents == null || contents.isBlank() || contents.length() > 60_000)
            throw new IllegalArgumentException("R source must contain 1–60,000 characters.");
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), "talg-script-", ".R");
        try {
            Files.writeString(temporary, contents, StandardCharsets.UTF_8);
            ProcessBuilder syntax = RRuntime.builder(projectRoot, "-e",
                    "parse(file=commandArgs(TRUE)[1])", temporary.toString());
            syntax.directory(projectRoot.toFile());
            AlpacaProcessEnvironment.scrub(syntax);
            run(syntax, 30, null);
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static void run(ProcessBuilder builder, int timeoutSeconds, AlpacaSettings credentials)
            throws IOException, InterruptedException {
        builder.redirectErrorStream(true);
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        Thread reader = Thread.startVirtualThread(() -> {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    synchronized (output) {
                        if (output.length() < 6000) output.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) { /* Exit status reports the failure. */ }
        });
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("R exceeded the " + timeoutSeconds + " second run limit.");
        }
        reader.join(2000);
        if (process.exitValue() != 0) {
            String message;
            synchronized (output) { message = output.toString().trim(); }
            if (credentials != null) message = message.replace(credentials.apiKey(), "<redacted>")
                    .replace(credentials.apiSecret(), "<redacted>");
            throw new IOException(message.isBlank() ? "R exited with code " + process.exitValue() : message);
        }
    }
}

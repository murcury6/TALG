package io.github.murcury6.talg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Reusable named indicator source files on the TALG drive. */
final class IndicatorRepository {
    private final Path projectRoot;
    private final Path directory;

    IndicatorRepository(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        directory = this.projectRoot.resolve("work/indicators");
    }

    static String name(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z][a-z0-9_]{0,39}"))
            throw new IllegalArgumentException("Name the indicator with letters, digits, or _; start with a letter.");
        return value;
    }

    Path path(String name) { return directory.resolve(name(name) + ".R"); }

    List<String> names() throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .map(file -> file.getFileName().toString())
                    .filter(file -> file.endsWith(".R"))
                    .map(file -> file.substring(0, file.length() - 2))
                    .filter(file -> file.matches("[a-z][a-z0-9_]{0,39}"))
                    .sorted().toList();
        }
    }

    String read(String name) throws IOException {
        return Files.readString(path(name), StandardCharsets.UTF_8);
    }

    void save(String name, String source) throws Exception {
        RScriptFiles.save(projectRoot, path(name), source);
    }
}

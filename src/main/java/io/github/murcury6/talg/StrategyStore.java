package io.github.murcury6.talg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** USB-local algorithm scripts; saving a script never enables order submission. */
final class StrategyStore {
    private final Path directory;

    StrategyStore(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }

    List<String> names() throws IOException {
        if (!Files.exists(directory)) return List.of();
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.strategy")) {
            for (Path file : files) {
                String filename = file.getFileName().toString();
                String name = filename.substring(0, filename.length() - ".strategy".length());
                try {
                    DeskProfile.validateName(name);
                    if (Files.isRegularFile(file)) names.add(name);
                } catch (IllegalArgumentException ignored) { /* Ignore unrelated files. */ }
            }
        }
        names.sort(Comparator.comparing(String::toLowerCase));
        return List.copyOf(names);
    }

    String load(String name) throws IOException {
        Path file = path(name);
        if (!Files.isRegularFile(file) || Files.size(file) > 50_000)
            throw new IOException("Strategy script is missing or too large.");
        String source = Files.readString(file, StandardCharsets.UTF_8);
        if (!StrategyScript.parse(source).name().equals(name))
            throw new IOException("Strategy name does not match its filename.");
        return source;
    }

    void save(String source) throws IOException {
        StrategyScript.Parsed strategy = StrategyScript.parse(source);
        Path target = path(strategy.name());
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "talg-strategy-", ".tmp");
        try {
            Files.writeString(temporary, source, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    Path path(String name) {
        DeskProfile.validateName(name);
        return directory.resolve(name + ".strategy");
    }
}

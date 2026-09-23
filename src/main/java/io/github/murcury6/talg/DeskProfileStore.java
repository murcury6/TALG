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

/** USB-local profile scripts and active-profile pointer; never writes credentials. */
final class DeskProfileStore {
    private static final String SUFFIX = ".desk.json";
    private static final int MAX_SCRIPT_BYTES = 1_000_000;
    private final Path directory;

    DeskProfileStore() { this(Path.of("work", "profiles")); }

    DeskProfileStore(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }

    List<String> names() throws IOException {
        if (!Files.exists(directory)) return List.of();
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + SUFFIX)) {
            for (Path file : files) {
                String filename = file.getFileName().toString();
                String name = filename.substring(0, filename.length() - SUFFIX.length());
                try {
                    DeskProfile.validateName(name);
                    if (Files.isRegularFile(file)) names.add(name);
                } catch (IllegalArgumentException ignored) { /* Ignore unrelated files. */ }
            }
        }
        names.sort(Comparator.comparing(String::toLowerCase));
        return List.copyOf(names);
    }

    String activeName() throws IOException {
        Path pointer = directory.resolve("active.txt");
        if (!Files.isRegularFile(pointer)) return "Default";
        String name = Files.readString(pointer, StandardCharsets.UTF_8).trim();
        DeskProfile.validateName(name);
        return name;
    }

    void setActive(String name) throws IOException {
        DeskProfile.validateName(name);
        atomicWrite(directory.resolve("active.txt"), name + System.lineSeparator());
    }

    boolean exists(String name) {
        return Files.isRegularFile(pathFor(name));
    }

    DeskProfile load(String name) throws IOException {
        try {
            DeskProfile profile = DeskProfile.parse(source(name));
            if (!profile.name().equals(name)) throw new IOException("Profile name does not match its filename.");
            return profile;
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid desk profile " + name + ": " + error.getMessage(), error);
        }
    }

    String source(String name) throws IOException {
        Path file = pathFor(name);
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_SCRIPT_BYTES)
            throw new IOException("Desk profile is missing or too large: " + name);
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    void save(DeskProfile profile) throws IOException {
        DeskProfile.validate(profile);
        String script = profile.script();
        if (script.getBytes(StandardCharsets.UTF_8).length > MAX_SCRIPT_BYTES)
            throw new IOException("Desk profile script exceeds 1 MB.");
        atomicWrite(pathFor(profile.name()), script);
    }

    Path pathFor(String name) {
        DeskProfile.validateName(name);
        return directory.resolve(name + SUFFIX);
    }

    private static void atomicWrite(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "talg-profile-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}

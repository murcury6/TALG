package io.github.murcury6.talg;

import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Optional;

/** Windows-user-bound DPAPI storage, outside the removable project directory. */
final class CredentialStore {
    private static final String VERSION = "TALG-ALPACA-1";
    private static final int MAX_FILE_BYTES = 16_384;
    private final Path file;

    CredentialStore() {
        this(defaultPath());
    }

    CredentialStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    Optional<Saved> load() throws IOException {
        if (!Files.exists(file)) return Optional.empty();
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_FILE_BYTES)
            throw new IOException("Saved credential file is invalid.");
        byte[] protectedBytes = Files.readAllBytes(file);
        byte[] plain = null;
        try {
            plain = Crypt32Util.cryptUnprotectData(protectedBytes,
                    WinCrypt.CRYPTPROTECT_UI_FORBIDDEN);
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(plain))) {
                if (!VERSION.equals(input.readUTF())) throw new IOException("Unsupported saved credential version.");
                AlpacaSettings settings = new AlpacaSettings(input.readUTF(), input.readUTF(),
                        input.readUTF(), input.readInt());
                String mode = input.readUTF();
                if (!mode.equals("paper") && !mode.equals("live"))
                    throw new IOException("Saved account mode is invalid.");
                if (input.available() != 0) throw new IOException("Saved credential file has extra data.");
                return Optional.of(new Saved(settings, mode));
            }
        } catch (RuntimeException error) {
            throw new IOException("Saved credentials could not be unlocked for this Windows user.", error);
        } finally {
            Arrays.fill(protectedBytes, (byte) 0);
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
    }

    void save(Saved saved) throws IOException {
        byte[] plain;
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(buffer)) {
            output.writeUTF(VERSION);
            output.writeUTF(saved.settings().apiKey());
            output.writeUTF(saved.settings().apiSecret());
            output.writeUTF(saved.settings().feed());
            output.writeInt(saved.settings().refreshSeconds());
            output.writeUTF(saved.mode());
            output.flush();
            plain = buffer.toByteArray();
        }
        byte[] protectedBytes;
        try {
            protectedBytes = Crypt32Util.cryptProtectData(plain,
                    WinCrypt.CRYPTPROTECT_UI_FORBIDDEN);
        } catch (RuntimeException error) {
            throw new IOException("Windows could not encrypt Alpaca credentials.", error);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
        try {
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), "talg-credentials-", ".tmp");
            try {
                Files.write(temporary, protectedBytes);
                try {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } finally {
            Arrays.fill(protectedBytes, (byte) 0);
        }
    }

    void delete() throws IOException {
        Files.deleteIfExists(file);
    }

    Path path() { return file; }

    private static Path defaultPath() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path root = localAppData == null || localAppData.isBlank()
                ? Path.of(System.getProperty("user.home"), "AppData", "Local") : Path.of(localAppData);
        return root.resolve("TALG").resolve("alpaca-credentials.dpapi");
    }

    record Saved(AlpacaSettings settings, String mode) {
        Saved {
            if (settings == null || (!"paper".equals(mode) && !"live".equals(mode)))
                throw new IllegalArgumentException("Invalid saved account settings");
        }
    }
}

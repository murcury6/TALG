package io.github.murcury6.talg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CredentialStoreTest {
    @TempDir Path directory;

    @Test void savesEncryptedCredentialsForTheCurrentWindowsUser() throws Exception {
        CredentialStore store = new CredentialStore(directory.resolve("alpaca.dpapi"));
        CredentialStore.Saved saved = new CredentialStore.Saved(
                new AlpacaSettings("PK-unique-test-key", "SK-unique-test-secret", "iex", 30), "paper");
        assertTrue(store.load().isEmpty());
        store.save(saved);
        String fileText = new String(Files.readAllBytes(store.path()), StandardCharsets.ISO_8859_1);
        assertFalse(fileText.contains("PK-unique-test-key"));
        assertFalse(fileText.contains("SK-unique-test-secret"));
        assertEquals(saved, store.load().orElseThrow());
        assertFalse(saved.settings().toString().contains("SK-unique-test-secret"));
        store.delete();
        assertTrue(store.load().isEmpty());
    }
}

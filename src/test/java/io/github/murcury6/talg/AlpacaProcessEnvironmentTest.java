package io.github.murcury6.talg;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class AlpacaProcessEnvironmentTest {
    @Test void stripsInheritedKeysBeforeRunningUserCode() {
        ProcessBuilder userCode = new ProcessBuilder("Rscript", "user.R");
        userCode.environment().put("APCA_API_KEY_ID", "inherited-key");
        userCode.environment().put("APCA_API_SECRET_KEY", "inherited-secret");
        userCode.environment().put("TALG_ALPACA_KEY", "session-key");
        userCode.environment().put("TALG_ALPACA_SECRET", "session-secret");
        AlpacaProcessEnvironment.scrub(userCode);
        assertFalse(userCode.environment().containsKey("APCA_API_KEY_ID"));
        assertFalse(userCode.environment().containsKey("APCA_API_SECRET_KEY"));
        assertFalse(userCode.environment().containsKey("TALG_ALPACA_KEY"));
        assertFalse(userCode.environment().containsKey("TALG_ALPACA_SECRET"));
    }
}

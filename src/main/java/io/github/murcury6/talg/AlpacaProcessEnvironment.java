package io.github.murcury6.talg;

import java.util.List;
import java.util.Locale;

/** Keep credentials only in the privileged fetch subprocess, not user-code subprocesses. */
final class AlpacaProcessEnvironment {
    private AlpacaProcessEnvironment() {}

    static void supply(ProcessBuilder builder, AlpacaSettings settings, String mode) {
        builder.environment().put("TALG_ALPACA_KEY", settings.apiKey());
        builder.environment().put("TALG_ALPACA_SECRET", settings.apiSecret());
        builder.environment().put("TALG_STOCK_FEED", settings.feed());
        builder.environment().put("TALG_ACCOUNT_MODE", mode);
    }

    static void scrub(ProcessBuilder builder) {
        for (String name : List.copyOf(builder.environment().keySet())) {
            String upper = name.toUpperCase(Locale.ROOT);
            if (upper.startsWith("APCA_API_") || upper.startsWith("TALG_ALPACA_"))
                builder.environment().remove(name);
        }
    }
}

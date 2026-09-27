package io.github.murcury6.talg;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="talg.rapidPreflight", matches="true")
class RapidBasketPreflightTest {
    @Test void checkTwentyAssetsAndBulkDataWithoutSendingOrders() throws Exception {
        var saved = new CredentialStore().load().orElseThrow(); assertEquals("paper", saved.mode());
        var broker = new RapidPaperRunner.Alpaca(saved.settings()); String id = broker.validate("2026-09-23", RapidPaperRunner.SYMBOLS);
        var account = broker.account(); assertEquals(id, account.id());
        for (String symbol : RapidPaperRunner.SYMBOLS) { assertEquals(0, account.positions().getOrDefault(symbol, java.math.BigDecimal.ZERO).signum()); assertFalse(account.openOrders().containsValue(symbol)); }
        var seed = broker.seed(RapidPaperRunner.SYMBOLS, Instant.now()); var market = broker.market(RapidPaperRunner.SYMBOLS); Instant now = Instant.now();
        long fresh = market.values().stream().filter(m -> RapidPaperRunner.fresh(m, now)).count();
        Map<String, Object> report = Map.of("checkedAt", now.toString(), "paperOnly", true, "tradableAssets", 20, "freshQuotes", fresh,
                "historySymbols", seed.size(), "marketOpen", account.open(), "ordersSent", 0);
        Files.createDirectories(Path.of("work/rapid-paper/research")); PaperTestRunner.JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of("work/rapid-paper/research/broker-preflight.json").toFile(), report);
        assertTrue(fresh >= 5, "Need at least five fresh symbol quotes"); System.out.println(PaperTestRunner.JSON.writeValueAsString(report));
    }
}

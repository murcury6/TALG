package io.github.murcury6.talg;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit read-only check using the app's saved paper credentials; never prints credentials or sends orders. */
@EnabledIfSystemProperty(named = "talg.paperPreflight", matches = "true")
class PaperPreflightTest {
    @Test void checkSavedPaperConnectionAndTomorrowWithoutSubmitting() throws Exception {
        var saved = new CredentialStore().load().orElseThrow(() -> new IllegalStateException("No saved connection"));
        assertEquals("paper", saved.mode(), "Saved connection must be PAPER");
        var broker = new PaperTestRunner.AlpacaBroker(saved.settings());
        var plan = new PaperTestRunner.Plan("2026-09-23", "SPY", 300, 12, 1000);
        String accountId = broker.validatePlan(plan);
        var check = broker.check("SPY", "");
        assertEquals(accountId, check.accountId());
        assertEquals(0, check.position().signum()); assertFalse(check.otherOrders());
        System.out.println("PAPER preflight passed: account active, SPY tradable, 2026-09-23 regular session confirmed, no SPY position or open orders. Market open now: " + check.open());
        var quote = new AlpacaMarketDataClient().fetch("SPY", saved.settings());
        System.out.println("SPY data connection passed. Observed price: " + quote.price() + "; observed time: " + quote.priceTime() + "; feed: " + quote.feed());
        assertTrue(quote.price() * 1.005 <= plan.cap(), "SPY one-share limit must fit the test cap");
    }
}

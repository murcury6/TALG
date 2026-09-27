package io.github.murcury6.talg;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CompanyFinancialsTest {
    @Test void excludesYearToDateCashFlowsKeepsRealQuartersAndLatestRestatement() throws Exception {
        String json = """
                {"facts":{"us-gaap":{"NetCashProvidedByUsedInOperatingActivities":{"units":{"USD":[
                {"start":"2026-01-01","end":"2026-03-31","val":10,"filed":"2026-04-20","form":"10-Q","accn":"a"},
                {"start":"2026-01-01","end":"2026-03-31","val":12,"filed":"2026-05-20","form":"10-Q/A","accn":"b"},
                {"start":"2026-01-01","end":"2026-06-30","val":30,"filed":"2026-07-20","form":"10-Q","accn":"c"},
                {"start":"2025-01-01","end":"2025-12-31","val":40,"filed":"2026-02-20","form":"10-K","accn":"d"}
                ]}}}}}
                """;
        var facts = CompanyFinancialsPanel.extract(NewsService.JSON.readTree(json));
        assertEquals(2, facts.size());
        assertEquals("12", facts.getFirst().value());
        assertEquals("Quarter", facts.getFirst().period());
        assertEquals("Annual", facts.getLast().period());
        assertEquals("b", facts.getFirst().accession());
    }
}

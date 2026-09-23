package io.github.murcury6.talg;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Dimension;
import org.junit.jupiter.api.Test;

class RPortfolioPanelTest {
    @Test void chartBitmapMatchesNarrowAndShortPanelAspectRatios() {
        assertEquals(new Dimension(600, 1000), RPortfolioPanel.renderDimensions(300, 500));
        assertEquals(new Dimension(933, 350), RPortfolioPanel.renderDimensions(400, 150));
        assertEquals(new Dimension(1400, 860), RPortfolioPanel.renderDimensions(700, 430));
    }

    @Test void unsizedCanvasGetsAValidInitialRender() {
        assertEquals(new Dimension(900, 550), RPortfolioPanel.renderDimensions(0, 0));
    }
}

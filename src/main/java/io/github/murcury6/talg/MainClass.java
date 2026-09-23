package io.github.murcury6.talg;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import javax.swing.SwingUtilities;

/** Desktop entry point. Orders require an explicit Trading-tab preview and confirmation. */
public final class MainClass {

    private MainClass() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || (args.length == 1 && "desktop".equals(args[0]))) {
            SwingUtilities.invokeLater(() -> new TalgDesktop().setVisible(true));
            return;
        }
        if (args.length != 4 || !"evaluate".equals(args[0])) {
            System.err.println("Usage: java -jar talg.jar [desktop | evaluate <signals.csv> <as-of-UTC> <capital-USD>]");
            System.exit(2);
        }

        Path input = Path.of(args[1]);
        Instant evaluatedAt = Instant.parse(args[2]);
        double capital = Double.parseDouble(args[3]);
        if (!Double.isFinite(capital) || capital <= 0) {
            throw new IllegalArgumentException("Capital must be a positive finite amount");
        }

        List<Signal> signals = SignalCsv.read(input);
        RiskEngine engine = new RiskEngine();
        System.out.println("symbol,status,shares,max_notional_usd,reason");
        for (Signal signal : signals) {
            Decision decision = engine.evaluate(signal, evaluatedAt, capital);
            System.out.printf("%s,%s,%d,%.2f,%s%n", signal.symbol(), decision.status(),
                    decision.shares(), decision.maxNotionalUsd(), decision.reason());
        }
    }
}

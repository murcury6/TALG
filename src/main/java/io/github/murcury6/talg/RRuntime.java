package io.github.murcury6.talg;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Runs bundled R with its native dependencies, including when Java was started directly. */
final class RRuntime {
    private RRuntime() {}

    static ProcessBuilder builder(Path projectRoot, String... arguments) {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path bundled = root.resolve(".pixi/envs/default");
        String active = System.getenv("CONDA_PREFIX");
        boolean insideBundledEnvironment = active != null && !active.isBlank()
                && Path.of(active).toAbsolutePath().normalize().equals(bundled);
        List<String> command = new ArrayList<>();
        Path pixi = root.resolve(".tools/pixi/pixi.exe");
        if (!insideBundledEnvironment && Files.isRegularFile(pixi)) {
            command.add(pixi.toString());
            command.add("run");
        }
        command.add("Rscript");
        command.addAll(Arrays.asList(arguments));
        return new ProcessBuilder(command);
    }
}

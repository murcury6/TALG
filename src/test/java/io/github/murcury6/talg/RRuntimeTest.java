package io.github.murcury6.talg;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RRuntimeTest {
    @TempDir Path project;

    @Test void launchesRThroughPixiWhenJavaDidNotInheritItsEnvironment() throws Exception {
        assertEquals(List.of("Rscript", "-e", "cat(1)"),
                RRuntime.builder(project, "-e", "cat(1)").command());
        Path pixi = project.resolve(".tools/pixi/pixi.exe");
        Files.createDirectories(pixi.getParent());
        Files.createFile(pixi);
        List<String> command = RRuntime.builder(project, "-e", "cat(1)").command();
        assertEquals(List.of(pixi.toString(), "run", "Rscript", "-e", "cat(1)"), command);
    }
}

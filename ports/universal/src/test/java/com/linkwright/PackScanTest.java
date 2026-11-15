package com.linkwright;

import com.linkwright.neoforge.NeoForgeJars;
import com.linkwright.neoforge.NeoForgePlatform;
import com.linkwright.scan.Dist;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Runs the analysis over a real NeoForge instance without writing anything into it. Enable with
 * {@code -Plinkwright.testGameDir=<instance folder>}.
 */
class PackScanTest {
    @Test
    void scanRealInstance() throws Exception {
        String dir = System.getProperty("linkwright.testGameDir");
        Assumptions.assumeTrue(dir != null && !dir.isBlank(), "no linkwright.testGameDir given");
        Path gameDir = Paths.get(dir);
        Assumptions.assumeTrue(Files.isDirectory(gameDir.resolve("mods")));

        // The CurseForge launcher keeps libraries in Install/libraries beside Instances/
        Path libraries = gameDir.getParent().getParent().resolve("Install").resolve("libraries");
        String neoForge = System.getProperty("linkwright.neoforge", "21.1.265");
        List<Path> jars = NeoForgeJars.locate(libraries, System.getProperty("linkwright.mc", "1.21.1"),
                System.getProperty("linkwright.neoform", "20240808.144430"), neoForge, Dist.CLIENT);
        System.out.println("Platform jars: " + jars);
        NeoForgePlatform platform = new NeoForgePlatform(gameDir, Dist.CLIENT, neoForge, jars);

        long start = System.nanoTime();
        Linkwright.Analysis analysis = Linkwright.analyze(platform, LinkwrightConfig.defaults());
        System.out.println(ReportWriter.render(LinkwrightConfig.defaults(), platform.loaderName(), "client",
                analysis.result.reports, analysis.approved.isEmpty() ? null : platform.overridesHint(analysis.approved)));
        System.out.println("Scanned " + analysis.modCount + " mods in " + (System.nanoTime() - start) / 1_000_000 + " ms");
    }
}

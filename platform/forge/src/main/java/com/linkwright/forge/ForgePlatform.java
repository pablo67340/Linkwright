package com.linkwright.forge;

import com.linkwright.fml.FmlPlatform;
import com.linkwright.fml.LaunchArgs;
import com.linkwright.fml.ModInfoRules;
import com.linkwright.link.McNames;
import com.linkwright.maven.MavenVersions;
import com.linkwright.scan.Dist;
import com.linkwright.scan.ForgeTomlReader;
import com.linkwright.scan.ModReader;
import com.linkwright.version.VersionScheme;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Forge 1.13 and later on ModLauncher. Forge has no dependency override setting, so approved
 * rules are relaxed in FML's discovered metadata between its scan and its sort.
 */
public final class ForgePlatform implements FmlPlatform {
    private static final Set<String> PLATFORM_IDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "minecraft", "forge", "fml", "javafml", "lowcodefml", "java")));

    private final Path gameDir;
    private final Dist dist;
    private final List<Path> platformJars;
    private final McNames names;
    private final ModInfoRules rules = new ModInfoRules("net.minecraftforge.fml.loading");

    public ForgePlatform(Path gameDir, Dist dist, String mcVersion, String forgeVersion, List<Path> platformJars) {
        this.gameDir = gameDir;
        this.dist = dist;
        this.platformJars = platformJars;
        this.names = namesFor(mcVersion, forgeVersion);
    }

    public static boolean present() {
        return new ModInfoRules("net.minecraftforge.fml.loading").present();
    }

    public static ForgePlatform fromLaunch(Path gameDir, Dist dist) {
        return new ForgePlatform(gameDir, dist, LaunchArgs.get("--fml.mcVersion"), LaunchArgs.get("--fml.forgeVersion"),
                ForgeJars.fromLaunch(dist));
    }

    /**
     * Forge 50 (1.20.6) moved the runtime to Mojang's names; before that, official class names with
     * SRG member names from 1.17, and MCP names before that.
     */
    static McNames namesFor(String mcVersion, String forgeVersion) {
        int forgeMajor = leadingInt(forgeVersion, 50);
        if (forgeMajor >= 50) {
            return McNames.MOJANG;
        }
        if (forgeMajor >= 37) {
            return McNames.SRG;
        }
        return forgeMajor >= 32 ? McNames.MCP_116 : McNames.MCP;
    }

    private static int leadingInt(String version, int fallback) {
        if (version == null) {
            return fallback;
        }
        int end = 0;
        while (end < version.length() && Character.isDigit(version.charAt(end))) {
            end++;
        }
        return end == 0 ? fallback : Integer.parseInt(version.substring(0, end));
    }

    @Override
    public boolean readyToRelax() {
        return rules.discovered();
    }

    @Override
    public String loaderName() {
        return "Forge";
    }

    @Override
    public Path gameDir() {
        return gameDir;
    }

    @Override
    public Dist dist() {
        return dist;
    }

    @Override
    public List<Path> modDirs() {
        return Collections.singletonList(gameDir.resolve("mods"));
    }

    @Override
    public List<ModReader> readers() {
        return Collections.<ModReader>singletonList(new ForgeTomlReader(ForgeTomlReader.FORGE, true));
    }

    @Override
    public VersionScheme versions() {
        return MavenVersions.INSTANCE;
    }

    @Override
    public List<Path> platformJars() {
        return platformJars;
    }

    @Override
    public Set<String> platformIds() {
        return PLATFORM_IDS;
    }

    @Override
    public McNames names() {
        return names;
    }

    @Override
    public Map<String, Set<String>> existingOverrides() {
        return Collections.emptyMap();
    }

    @Override
    public String overridesHint(Map<String, Set<String>> approved) {
        return null;
    }

    @Override
    public void relax(Map<String, List<String>> approved) throws Exception {
        rules.relax(approved);
    }
}

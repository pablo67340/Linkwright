package com.linkwright.forge;

import com.linkwright.LoaderMod;
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

    private static final String LOADING = "net.minecraftforge.fml.loading";

    private final Path gameDir;
    private final Dist dist;
    private final ModInfoRules rules = new ModInfoRules(LOADING);
    private List<Path> platformJars;
    private String forgeVersion;
    private McNames names;

    public ForgePlatform(Path gameDir, Dist dist, String mcVersion, String forgeVersion, List<Path> platformJars) {
        this.gameDir = gameDir;
        this.dist = dist;
        this.platformJars = platformJars;
        this.forgeVersion = forgeVersion;
        this.names = namesFor(mcVersion, forgeVersion);
    }

    /** Versions are read once FML has them; server shims and some launchers don't pass them on the command line. */
    private ForgePlatform(Path gameDir, Dist dist) {
        this.gameDir = gameDir;
        this.dist = dist;
    }

    public static boolean present() {
        return new ModInfoRules(LOADING).present();
    }

    public static ForgePlatform fromLaunch(Path gameDir, Dist dist) {
        return new ForgePlatform(gameDir, dist);
    }

    private synchronized void resolveVersions() {
        if (names != null) {
            return;
        }
        String mc = version("mcVersion", "--fml.mcVersion");
        String mcp = version("mcpVersion", "--fml.mcpVersion");
        forgeVersion = version("forgeVersion", "--fml.forgeVersion");
        names = namesFor(mc, forgeVersion);
        platformJars = ForgeJars.fromLaunch(dist, mc, mcp, forgeVersion);
    }

    private static String version(String property, String argument) {
        String value = LaunchArgs.versionInfo(LOADING, property);
        return value != null ? value : LaunchArgs.get(argument);
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
        resolveVersions();
        return platformJars;
    }

    @Override
    public Set<String> platformIds() {
        return PLATFORM_IDS;
    }

    @Override
    public LoaderMod loader() {
        resolveVersions();
        return LoaderMod.of("forge", forgeVersion, LoaderMod.find(platformJars, "forge-", "-universal.jar"),
                "net/minecraftforge/");
    }

    @Override
    public McNames names() {
        resolveVersions();
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

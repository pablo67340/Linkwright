package com.linkwright.neoforge;

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
 * NeoForge 20.2 and later on ModLauncher. FML 4 (NeoForge 21.0) and later take extra dependency
 * overrides; before that, approved rules are relaxed in FML's discovered metadata.
 */
public final class NeoForgePlatform implements FmlPlatform {
    private static final Set<String> PLATFORM_IDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "minecraft", "neoforge", "forge", "fml", "javafml", "lowcodefml", "java")));

    private static final String LOADING = "net.neoforged.fml.loading";

    private final Path gameDir;
    private final Dist dist;
    private final boolean overrides = FmlOverrides.supported();
    private final ModInfoRules rules = new ModInfoRules(LOADING);
    private List<Path> platformJars;
    private String neoForgeVersion;
    /** 20.5 moved metadata to neoforge.mods.toml and the runtime to Mojang's member names. */
    private Boolean modern;

    public NeoForgePlatform(Path gameDir, Dist dist, String neoForgeVersion, List<Path> platformJars) {
        this.gameDir = gameDir;
        this.dist = dist;
        this.platformJars = platformJars;
        this.neoForgeVersion = neoForgeVersion;
        this.modern = isModern(neoForgeVersion);
    }

    /** Versions are read once FML has them, falling back to the launch command. */
    private NeoForgePlatform(Path gameDir, Dist dist) {
        this.gameDir = gameDir;
        this.dist = dist;
    }

    public static boolean present() {
        try {
            Class.forName(LOADING + ".FMLLoader", false, NeoForgePlatform.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static NeoForgePlatform fromLaunch(Path gameDir, Dist dist) {
        return new NeoForgePlatform(gameDir, dist);
    }

    private synchronized boolean modern() {
        if (modern == null) {
            neoForgeVersion = version("neoForgeVersion", "--fml.neoForgeVersion");
            modern = isModern(neoForgeVersion);
            platformJars = NeoForgeJars.locate(LaunchArgs.libraries(), version("mcVersion", "--fml.mcVersion"),
                    version("neoFormVersion", "--fml.neoFormVersion"), neoForgeVersion, dist);
        }
        return modern;
    }

    private static String version(String property, String argument) {
        String value = LaunchArgs.versionInfo(LOADING, property);
        return value != null ? value : LaunchArgs.get(argument);
    }

    static boolean isModern(String neoForgeVersion) {
        if (neoForgeVersion == null) {
            return true;
        }
        String[] parts = neoForgeVersion.split("[.-]");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major > 20 || (major == 20 && minor >= 5);
        } catch (NumberFormatException e) {
            return true;
        }
    }

    @Override
    public boolean readyToRelax() {
        // With overrides: once FML has loaded fml.toml, so its own copy will not replace ours
        return overrides ? FmlOverrides.configLoaded() : rules.discovered();
    }

    @Override
    public String loaderName() {
        return "NeoForge";
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
        return Collections.<ModReader>singletonList(modern()
                ? new ForgeTomlReader(ForgeTomlReader.NEOFORGE, false)
                : new ForgeTomlReader(ForgeTomlReader.FORGE, true));
    }

    @Override
    public VersionScheme versions() {
        return MavenVersions.INSTANCE;
    }

    @Override
    public List<Path> platformJars() {
        modern();
        return platformJars;
    }

    @Override
    public Set<String> platformIds() {
        return PLATFORM_IDS;
    }

    @Override
    public LoaderMod loader() {
        modern();
        return LoaderMod.of("neoforge", neoForgeVersion, LoaderMod.find(platformJars, "neoforge-", "-universal.jar"),
                "net/neoforged/neoforge/");
    }

    @Override
    public McNames names() {
        return modern() ? McNames.MOJANG : McNames.SRG;
    }

    @Override
    public Map<String, Set<String>> existingOverrides() {
        return FmlToml.readRemovals(gameDir.resolve("config").resolve("fml.toml"));
    }

    @Override
    public String overridesHint(Map<String, Set<String>> approved) {
        if (!overrides) {
            return null;
        }
        return "To ship these approvals without Linkwright, put this line in config/fml.toml\n"
                + "(merge it with any dependencyOverrides line already there). This only relaxes the\n"
                + "version check; the adapted changes above still need Linkwright or a hand-written patch.\n  "
                + FmlToml.overridesLine(approved) + "\n";
    }

    @Override
    public void relax(Map<String, List<String>> approved) throws Exception {
        if (overrides) {
            FmlOverrides.add(approved);
        } else {
            rules.relax(approved);
        }
    }
}

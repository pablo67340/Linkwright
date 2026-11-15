package com.linkwright.neoforge;

import com.linkwright.LoaderPlatform;
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

/** NeoForge 20.2 and later. */
public final class NeoForgePlatform implements LoaderPlatform {
    private static final Set<String> PLATFORM_IDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "minecraft", "neoforge", "forge", "fml", "javafml", "lowcodefml", "java")));

    private final Path gameDir;
    private final Dist dist;
    private final List<Path> platformJars;
    /** 20.5 moved metadata to neoforge.mods.toml and the runtime to Mojang's member names. */
    private final boolean modern;

    public NeoForgePlatform(Path gameDir, Dist dist, String neoForgeVersion, List<Path> platformJars) {
        this.gameDir = gameDir;
        this.dist = dist;
        this.platformJars = platformJars;
        this.modern = isModern(neoForgeVersion);
    }

    public static boolean present() {
        try {
            Class.forName("net.neoforged.fml.loading.FMLLoader", false, NeoForgePlatform.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static NeoForgePlatform fromLaunch(Path gameDir, Dist dist) {
        return new NeoForgePlatform(gameDir, dist, NeoForgeJars.launchArgument("--fml.neoForgeVersion"),
                NeoForgeJars.fromLaunch(dist));
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

    /** Whether the overrides must go in now: FML has loaded fml.toml, so its own copy will not replace ours. */
    public boolean configLoaded() {
        return FmlOverrides.configLoaded();
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
        return Collections.<ModReader>singletonList(modern
                ? new ForgeTomlReader(ForgeTomlReader.NEOFORGE, false)
                : new ForgeTomlReader(ForgeTomlReader.FORGE, true));
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
        return modern ? McNames.MOJANG : McNames.SRG;
    }

    @Override
    public Map<String, Set<String>> existingOverrides() {
        return FmlToml.readRemovals(gameDir.resolve("config").resolve("fml.toml"));
    }

    @Override
    public String overridesHint(Map<String, Set<String>> approved) {
        if (!FmlOverrides.supported()) {
            return null;
        }
        return "To ship these approvals without Linkwright, put this line in config/fml.toml\n"
                + "(merge it with any dependencyOverrides line already there). This only relaxes the\n"
                + "version check; the adapted changes above still need Linkwright or a hand-written patch.\n  "
                + FmlToml.overridesLine(approved) + "\n";
    }

    @Override
    public void relax(Map<String, List<String>> overrides) throws Exception {
        FmlOverrides.add(overrides);
    }
}

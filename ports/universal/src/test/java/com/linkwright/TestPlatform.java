package com.linkwright;

import com.linkwright.link.McNames;
import com.linkwright.maven.MavenVersions;
import com.linkwright.scan.Dist;
import com.linkwright.scan.ForgeTomlReader;
import com.linkwright.scan.ModReader;
import com.linkwright.version.VersionScheme;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A NeoForge-like platform over a bare mods folder, with no Minecraft jars. */
final class TestPlatform implements LoaderPlatform {
    private final Path modsDir;
    private final LoaderMod loader;

    TestPlatform(Path modsDir) {
        this(modsDir, null);
    }

    TestPlatform(Path modsDir, LoaderMod loader) {
        this.modsDir = modsDir;
        this.loader = loader;
    }

    @Override
    public LoaderMod loader() {
        return loader;
    }

    @Override
    public String loaderName() {
        return "Test";
    }

    @Override
    public Path gameDir() {
        return modsDir.getParent();
    }

    @Override
    public Dist dist() {
        return Dist.CLIENT;
    }

    @Override
    public List<Path> modDirs() {
        return List.of(modsDir);
    }

    @Override
    public List<ModReader> readers() {
        return List.of(new ForgeTomlReader(ForgeTomlReader.NEOFORGE, false));
    }

    @Override
    public VersionScheme versions() {
        return MavenVersions.INSTANCE;
    }

    @Override
    public List<Path> platformJars() {
        return List.of();
    }

    @Override
    public Set<String> platformIds() {
        return Set.of("minecraft", "neoforge", "java");
    }

    @Override
    public McNames names() {
        return McNames.MOJANG;
    }

    @Override
    public Map<String, Set<String>> existingOverrides() {
        return Map.of();
    }

    @Override
    public String overridesHint(Map<String, Set<String>> approved) {
        return null;
    }

    @Override
    public void relax(Map<String, List<String>> overrides) {
    }
}

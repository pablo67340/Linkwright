package com.linkwright.fabric;

import com.linkwright.LoaderMod;
import com.linkwright.LoaderPlatform;
import com.linkwright.LwLog;
import com.linkwright.link.McNames;
import com.linkwright.scan.Dist;
import com.linkwright.scan.FabricModReader;
import com.linkwright.scan.ModReader;
import com.linkwright.version.VersionScheme;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * Fabric Loader 0.14 and later, read once the game is located (and remapped) but before any mod
 * is. Approved rules are relaxed in each mod's metadata as the loader reads it, the same place
 * Fabric applies {@code config/fabric_loader_dependencies.json}.
 */
final class FabricPlatform implements LoaderPlatform {
    private static final Set<String> PLATFORM_IDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "minecraft", "java", "fabricloader")));
    private static final String IMPL = "net.fabricmc.loader.impl.";
    private static final String API = "net.fabricmc.loader.api.";

    private final Path gameDir;
    private final Dist dist;
    private final List<Path> gameJars;
    private final Path loaderJar;
    private final String loaderVersion;
    private final FabricVersions versions;

    private final Method getId;
    private final Method getDependencies;
    private final Method setDependencies;
    private final Method getModId;
    private final Method getKind;
    private final Constructor<?> newDependency;
    private volatile Map<String, List<String>> approved = Collections.emptyMap();

    private FabricPlatform(ClassLoader classLoader, Path gameDir, Dist dist, List<Path> gameJars, Path loaderJar,
                           String loaderVersion) throws ReflectiveOperationException {
        this.gameDir = gameDir;
        this.dist = dist;
        this.gameJars = gameJars;
        this.loaderJar = loaderJar;
        this.loaderVersion = loaderVersion;
        this.versions = new FabricVersions(classLoader);
        Class<?> metadata = Class.forName(IMPL + "metadata.LoaderModMetadata", false, classLoader);
        Class<?> dependency = Class.forName(API + "metadata.ModDependency", false, classLoader);
        Class<?> kind = Class.forName(API + "metadata.ModDependency$Kind", false, classLoader);
        getId = metadata.getMethod("getId");
        getDependencies = metadata.getMethod("getDependencies");
        setDependencies = metadata.getMethod("setDependencies", Collection.class);
        getModId = dependency.getMethod("getModId");
        getKind = dependency.getMethod("getKind");
        newDependency = Class.forName(IMPL + "metadata.ModDependencyImpl", false, classLoader)
                .getConstructor(kind, String.class, List.class);
    }

    /** @param classLoader the one holding Fabric Loader, which need not be the agent's */
    static FabricPlatform fromLoader(ClassLoader classLoader) throws ReflectiveOperationException {
        Class<?> impl = Class.forName(IMPL + "FabricLoaderImpl", false, classLoader);
        Object loader = impl.getField("INSTANCE").get(null);
        Path gameDir = (Path) impl.getMethod("getGameDir").invoke(loader);
        Dist dist = "CLIENT".equals(String.valueOf(impl.getMethod("getEnvironmentType").invoke(loader)))
                ? Dist.CLIENT : Dist.SERVER;
        Object provider = impl.getMethod("getGameProvider").invoke(loader);
        Path loaderJar = null;
        try {
            loaderJar = Paths.get(impl.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            LwLog.debug("Fabric Loader's jar not located: " + e);
        }
        String version = null;
        try {
            version = (String) impl.getField("VERSION").get(null);
        } catch (Exception e) {
            LwLog.debug("Fabric Loader's version not found: " + e);
        }
        return new FabricPlatform(classLoader, gameDir, dist, gameJars(provider), loaderJar, version);
    }

    /** The game jars as the game runs them, which in production are already remapped. */
    @SuppressWarnings("unchecked")
    private static List<Path> gameJars(Object provider) {
        for (Class<?> c = provider.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField("gameJars");
                field.setAccessible(true);
                return new ArrayList<>((List<Path>) field.get(provider));
            } catch (NoSuchFieldException ignored) {
            } catch (Exception e) {
                break;
            }
        }
        try {
            return Collections.singletonList((Path) provider.getClass().getMethod("getGameJar").invoke(provider));
        } catch (Exception e) {
            LwLog.debug("Minecraft's jar not located: " + e);
            return Collections.emptyList();
        }
    }

    @Override
    public String loaderName() {
        return "Fabric";
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
        String folder = System.getProperty("fabric.modsFolder");
        return Collections.singletonList(folder != null ? Paths.get(folder) : gameDir.resolve("mods"));
    }

    @Override
    public List<ModReader> readers() {
        return Collections.<ModReader>singletonList(new FabricModReader());
    }

    @Override
    public VersionScheme versions() {
        return versions;
    }

    @Override
    public List<Path> platformJars() {
        List<Path> jars = new ArrayList<>(gameJars);
        if (loaderJar != null && Files.isRegularFile(loaderJar)) {
            jars.add(loaderJar);
        }
        return jars;
    }

    @Override
    public Set<String> platformIds() {
        return PLATFORM_IDS;
    }

    @Override
    public LoaderMod loader() {
        return LoaderMod.of("fabricloader", loaderVersion, loaderJar, "net/fabricmc/loader/", "net/fabricmc/api/");
    }

    /** Intermediary names through 1.21; Minecraft has shipped unobfuscated since 26.1. */
    @Override
    public McNames names() {
        for (Path jar : gameJars) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                if (zip.getEntry(McNames.INTERMEDIARY.blockPos + ".class") != null) {
                    return McNames.INTERMEDIARY;
                }
                if (zip.getEntry(McNames.MOJANG.blockPos + ".class") != null) {
                    return McNames.MOJANG;
                }
            } catch (Exception e) {
                LwLog.debug("Linkwright could not read " + jar + ": " + e);
            }
        }
        return McNames.NONE;
    }

    @Override
    public Map<String, Set<String>> existingOverrides() {
        return Collections.emptyMap();
    }

    @Override
    public String overridesHint(Map<String, Set<String>> approved) {
        return null;
    }

    /** Takes effect as Fabric Loader reads each mod; see {@link #relaxMod}. */
    @Override
    public void relax(Map<String, List<String>> overrides) {
        approved = overrides;
    }

    /** Swaps each approved rule in one mod's metadata for one that accepts any version. */
    void relaxMod(Object metadata) throws ReflectiveOperationException {
        List<String> dependencies = approved.get((String) getId.invoke(metadata));
        if (dependencies == null) {
            return;
        }
        Collection<?> current = (Collection<?>) getDependencies.invoke(metadata);
        List<Object> updated = new ArrayList<>(current.size());
        List<String> relaxed = new ArrayList<>();
        for (Object rule : current) {
            String modId = (String) getModId.invoke(rule);
            Object kind = getKind.invoke(rule);
            if (dependencies.contains(modId) && "DEPENDS".equals(((Enum<?>) kind).name())) {
                updated.add(newDependency.newInstance(kind, modId, Collections.singletonList("*")));
                relaxed.add(modId);
            } else {
                updated.add(rule);
            }
        }
        if (!relaxed.isEmpty()) {
            setDependencies.invoke(metadata, updated);
            LwLog.info("Linkwright relaxed " + getId.invoke(metadata) + "'s rule(s) on " + relaxed + " in Fabric Loader");
        }
    }
}

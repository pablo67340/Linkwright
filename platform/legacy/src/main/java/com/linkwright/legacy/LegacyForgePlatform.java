package com.linkwright.legacy;

import com.linkwright.LoaderMod;
import com.linkwright.LoaderPlatform;
import com.linkwright.LwLog;
import com.linkwright.link.McNames;
import com.linkwright.scan.Dist;
import com.linkwright.scan.LegacyForgeReader;
import com.linkwright.scan.ModReader;
import com.linkwright.version.VersionScheme;
import net.minecraft.launchwrapper.Launch;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Forge 1.7 to 1.12 on LaunchWrapper. Forge has no dependency override setting, so approved rules
 * are relaxed on FML's mod containers just before it checks them.
 */
final class LegacyForgePlatform implements LoaderPlatform {
    private static final Set<String> PLATFORM_IDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "minecraft", "mcp", "FML", "fml", "Forge", "forge")));

    private final String fml;
    private final ClassLoader classLoader;
    private final Path gameDir;
    private final Dist dist;
    private final String mcVersion;
    private final VersionScheme versions;

    private LegacyForgePlatform(String fml, ClassLoader classLoader, Path gameDir, Dist dist, String mcVersion)
            throws ReflectiveOperationException {
        this.fml = fml;
        this.classLoader = classLoader;
        this.gameDir = gameDir;
        this.dist = dist;
        this.mcVersion = mcVersion;
        this.versions = new LegacyVersions(fml, classLoader);
    }

    /** Called once FML has identified the mods, so every class used here is loaded and set up. */
    static LegacyForgePlatform fromLaunch(Path gameDir) throws ReflectiveOperationException {
        // LaunchWrapper loads a tweaker's package from the parent class loader, which can load
        // a second, empty copy of FML from the Forge jar.
        ClassLoader classLoader = Launch.classLoader;
        String fml = classLoader.getResource("net/minecraftforge/fml/common/Loader.class") != null
                ? "net.minecraftforge.fml" : "cpw.mods.fml";
        Field mc = Class.forName(fml + ".relauncher.FMLInjectionData", true, classLoader).getDeclaredField("mccversion");
        mc.setAccessible(true);
        Object side = Class.forName(fml + ".relauncher.FMLLaunchHandler", true, classLoader).getMethod("side").invoke(null);
        Dist dist = (Boolean) side.getClass().getMethod("isClient").invoke(side) ? Dist.CLIENT : Dist.SERVER;
        return new LegacyForgePlatform(fml, classLoader, gameDir, dist, (String) mc.get(null));
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

    /** FML also loads mods from a folder named after the game version inside the mods folder. */
    @Override
    public List<Path> modDirs() {
        Path mods = gameDir.resolve("mods");
        return mcVersion == null ? Collections.singletonList(mods) : Arrays.asList(mods, mods.resolve(mcVersion));
    }

    @Override
    public List<ModReader> readers() {
        return Collections.<ModReader>singletonList(new LegacyForgeReader());
    }

    @Override
    public VersionScheme versions() {
        return versions;
    }

    /**
     * Forge's own jar. The Minecraft jar on disk is still obfuscated (Forge renames its classes as
     * they load), so it cannot be indexed and links into Minecraft count as unverifiable.
     */
    @Override
    public List<Path> platformJars() {
        URL loader = classLoader.getResource(fml.replace('.', '/') + "/common/Loader.class");
        try {
            String location = loader.toString();
            if (location.startsWith("jar:")) {
                return Collections.singletonList(Paths.get(new URI(location.substring(4, location.indexOf("!/")))));
            }
        } catch (Exception e) {
            LwLog.debug("Forge's jar not located from " + loader + ": " + e);
        }
        return Collections.emptyList();
    }

    @Override
    public Set<String> platformIds() {
        return PLATFORM_IDS;
    }

    /** Forge's own container, which FML checks rules against: {@code Forge} before 1.8, {@code forge} after. */
    @Override
    public LoaderMod loader() {
        try {
            Class<?> loaderType = Class.forName(fml + ".common.Loader", true, classLoader);
            Object loader = loaderType.getMethod("instance").invoke(null);
            for (Object mod : (List<?>) loaderType.getMethod("getModList").invoke(loader)) {
                String modId = (String) call(mod, "getModId");
                if ("forge".equalsIgnoreCase(modId)) {
                    List<Path> jars = platformJars();
                    return LoaderMod.of(modId, (String) call(mod, "getVersion"), jars.isEmpty() ? null : jars.get(0),
                            "net/minecraftforge/", "cpw/mods/fml/");
                }
            }
        } catch (ReflectiveOperationException e) {
            LwLog.debug("Forge's container not found: " + e);
        }
        return null;
    }

    @Override
    public McNames names() {
        if (mcVersion != null && mcVersion.startsWith("1.7")) {
            return McNames.MCP_17;
        }
        return mcVersion != null && mcVersion.startsWith("1.8") ? McNames.MCP_18 : McNames.MCP;
    }

    @Override
    public Map<String, Set<String>> existingOverrides() {
        return Collections.emptyMap();
    }

    @Override
    public String overridesHint(Map<String, Set<String>> approved) {
        return null;
    }

    /**
     * Makes each approved rule accept any version. FML reads a mod's rules into
     * {@code DefaultArtifactVersion}s held by its container, and marking one {@code unbounded}
     * is how FML itself spells a rule without a version.
     */
    @Override
    public void relax(Map<String, List<String>> overrides) throws Exception {
        Class<?> loaderType = Class.forName(fml + ".common.Loader", true, classLoader);
        Object loader = loaderType.getMethod("instance").invoke(null);
        Set<String> relaxed = new HashSet<>();
        for (Object mod : (List<?>) loaderType.getMethod("getModList").invoke(loader)) {
            String modId = (String) call(mod, "getModId");
            List<String> dependencies = overrides.get(modId);
            if (dependencies == null) {
                continue;
            }
            Map<Object, Boolean> seen = new IdentityHashMap<>();
            for (String getter : new String[]{"getRequirements", "getDependencies", "getDependants"}) {
                for (Object rule : (Collection<?>) call(mod, getter)) {
                    String label = (String) call(rule, "getLabel");
                    if (dependencies.contains(label) && seen.put(rule, Boolean.TRUE) == null && unbound(rule)) {
                        relaxed.add(modId + "->" + label);
                    }
                }
            }
        }
        if (!relaxed.isEmpty()) {
            LwLog.info("Linkwright relaxed " + relaxed.size() + " dependency rule(s) in FML: " + overrides);
        }
    }

    private static boolean unbound(Object rule) throws IllegalAccessException {
        for (Class<?> c = rule.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field unbounded = c.getDeclaredField("unbounded");
                unbounded.setAccessible(true);
                unbounded.setBoolean(rule, true);
                return true;
            } catch (NoSuchFieldException ignored) {
            }
        }
        LwLog.debug("Linkwright cannot relax " + rule + " (" + rule.getClass().getName() + ")");
        return false;
    }

    /** Calls a public no-argument method, even when it is declared on a class that is not public. */
    private static Object call(Object target, String name) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }
}

package com.linkwright.legacy;

import com.linkwright.Linkwright;
import com.linkwright.LwLog;
import com.linkwright.link.PatchSet;
import com.linkwright.scan.LegacyForgeReader;
import com.linkwright.scan.ModReader;
import com.linkwright.version.VersionScheme;
import net.minecraft.launchwrapper.Launch;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Runs at the start of FML's {@code Loader.sortModList}, after the mods are identified and their
 * rules read but before any rule is checked.
 */
public final class LegacyHook {
    static volatile Path gameDir;
    static volatile PatchSet patches;
    private static boolean ran;

    private LegacyHook() {
    }

    /**
     * From the tweaker: FML's coremod pass has read the mods folder, but FML lists it again for
     * mods once the game starts. Only FML's relauncher classes, which are loaded already, are used.
     */
    static void prepare() {
        try {
            Path dir = (gameDir != null ? gameDir : Paths.get("")).toAbsolutePath().normalize();
            List<Path> modDirs = new ArrayList<>();
            modDirs.add(dir.resolve("mods"));
            String mcVersion = mcVersion();
            if (mcVersion != null) {
                modDirs.add(dir.resolve("mods").resolve(mcVersion));
            }
            Linkwright.prepare(dir, modDirs, Collections.<ModReader>singletonList(new LegacyForgeReader()), BY_TEXT);
        } catch (Throwable t) {
            LwLog.error("Linkwright could not check the mods folder for duplicate mods", t);
        }
    }

    private static String mcVersion() {
        try {
            ClassLoader classLoader = Launch.classLoader;
            String fml = classLoader.getResource("net/minecraftforge/fml/common/Loader.class") != null
                    ? "net.minecraftforge.fml" : "cpw.mods.fml";
            Field mc = Class.forName(fml + ".relauncher.FMLInjectionData", true, classLoader).getDeclaredField("mccversion");
            mc.setAccessible(true);
            return (String) mc.get(null);
        } catch (Throwable t) {
            LwLog.debug("Minecraft's version is not known yet: " + t);
            return null;
        }
    }

    /** Only the mods' ids and files matter this early, not how their versions compare. */
    private static final VersionScheme BY_TEXT = new VersionScheme() {
        @Override
        public Boolean satisfies(String range, String version) {
            return null;
        }

        @Override
        public int compare(String a, String b) {
            return a.compareTo(b);
        }
    };

    public static synchronized void beforeSort() {
        if (ran) {
            return;
        }
        ran = true;
        try {
            Path dir = (gameDir != null ? gameDir : Paths.get("")).toAbsolutePath().normalize();
            LegacyForgePlatform platform = LegacyForgePlatform.fromLaunch(dir);
            Linkwright.Result result = Linkwright.run(platform);
            patches = new PatchSet(result.patches);
            if (!result.overrides.isEmpty()) {
                platform.relax(result.overrides);
            }
        } catch (Throwable t) {
            LwLog.error("Linkwright could not finish; Forge will check mod versions as usual", t);
        }
    }
}

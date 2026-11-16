package com.linkwright.legacy;

import com.linkwright.Linkwright;
import com.linkwright.LwLog;
import com.linkwright.link.PatchSet;

import java.nio.file.Path;
import java.nio.file.Paths;

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

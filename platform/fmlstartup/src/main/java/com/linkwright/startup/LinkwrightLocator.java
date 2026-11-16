package com.linkwright.startup;

import com.linkwright.Linkwright;
import com.linkwright.LwLog;
import com.linkwright.neoforge.NeoForgePlatform;
import com.linkwright.scan.Dist;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;

/**
 * Linkwright's entry point on FML 10 and later (NeoForge 21.9+), which start without ModLauncher.
 *
 * <p>FML loads any jar in the mods folder that declares a mod file locator before it discovers
 * mods, and asks each locator for candidates after reading {@code fml.toml} and before checking
 * dependencies. This locator contributes no files; it uses that moment to analyse the mods and
 * add the approved dependency overrides. {@link BridgeProcessor} then applies the patches.
 *
 * <p>FML 4 to 9 load locators from the mods folder too, but there Linkwright runs as a
 * ModLauncher service instead, so this does nothing when ModLauncher is present.
 */
public final class LinkwrightLocator implements IModFileCandidateLocator {
    static volatile Linkwright.Result result = Linkwright.Result.EMPTY;
    private static boolean ran;

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        synchronized (LinkwrightLocator.class) {
            if (ran || modLauncherPresent()) {
                return;
            }
            ran = true;
        }
        NeoForgePlatform platform = NeoForgePlatform.fromLaunch(context.gameDirectory(), dist());
        try {
            Linkwright.Result analysed = Linkwright.run(platform);
            if (!analysed.overrides.isEmpty()) {
                platform.relax(analysed.overrides);
            }
            result = analysed;
        } catch (Throwable t) {
            LwLog.error("Linkwright failed; leaving " + platform.loaderName() + "'s dependency checks untouched", t);
        }
    }

    /** As FML decides it, without needing FML's own {@code Dist} type. */
    private static Dist dist() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null && loader.getResource("net/minecraft/client/main/Main.class") != null ? Dist.CLIENT : Dist.SERVER;
    }

    private static boolean modLauncherPresent() {
        try {
            Class.forName("cpw.mods.modlauncher.Launcher", false, LinkwrightLocator.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public String toString() {
        return "linkwright";
    }
}

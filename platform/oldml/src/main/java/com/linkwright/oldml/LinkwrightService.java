package com.linkwright.oldml;

import com.linkwright.Linkwright;
import com.linkwright.LwLog;
import com.linkwright.fml.LaunchArgs;
import com.linkwright.fml.ModInfoRules;
import com.linkwright.forge.ForgePlatform;
import com.linkwright.scan.Dist;
import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Linkwright's entry point on ModLauncher 2 to 8 (Forge 1.13 to 1.16.5). FML finds this jar in the
 * mods folder because it declares a transformation service.
 *
 * <p>ModLauncher runs every service's {@code initialize}, then every service's scan, then asks
 * each for its transformers. FML reads and checks the mods inside its scan, so the rules are
 * relaxed from within it, through {@link LinkwrightLocator}; see {@link OldFml}.
 */
public final class LinkwrightService implements ITransformationService {
    /**
     * Where the locator finds {@link #relaxDiscovered}. FML loads the locator in a class loader of
     * its own, so the two share nothing but JDK types.
     */
    static final String RELAX_HOOK = "linkwright.relaxDiscovered";

    private final ModInfoRules rules = new ModInfoRules("net.minecraftforge.fml.loading");
    private ForgePlatform platform;
    private Linkwright.Result result;
    private boolean relaxed;

    @Override
    public String name() {
        return "linkwright";
    }

    @Override
    public void onLoad(IEnvironment environment, Set<String> otherServices) {
    }

    @Override
    public void initialize(IEnvironment environment) {
        runCurseforgeSyncFirst(environment);
        if (!ForgePlatform.present()) {
            LwLog.warn("Linkwright: Forge's loader not found; doing nothing");
            result = Linkwright.Result.EMPTY;
            return;
        }
        Path gameDir = environment.getProperty(IEnvironment.Keys.GAMEDIR.get()).orElse(Paths.get("").toAbsolutePath());
        Dist dist = Dist.fromLaunchTarget(environment.getProperty(IEnvironment.Keys.LAUNCHTARGET.get()).orElse(null));
        platform = ForgePlatform.fromLaunch(gameDir, dist);
        // FML lists the mods folder in its scan, after every service has initialized
        Linkwright.prepare(platform);
        try {
            Path jar = LaunchArgs.jarFile(LinkwrightService.class.getProtectionDomain().getCodeSource().getLocation().toString());
            if (OldFml.registerLocator(jar)) {
                System.getProperties().put(RELAX_HOOK, (Runnable) this::relaxDiscovered);
            }
        } catch (Throwable t) {
            LwLog.error("Linkwright could not register its mod locator", t);
        }
    }

    /**
     * CurseforgeSync offers its sync under this key from {@code onLoad}. ModLauncher visits
     * services in no fixed order, so if Linkwright comes first it runs the sync itself; otherwise
     * the sync has already happened and the call returns at once.
     */
    private static void runCurseforgeSyncFirst(IEnvironment environment) {
        Optional<Runnable> sync;
        try {
            sync = environment.getProperty(IEnvironment.buildKey("curseforgesync.sync", Runnable.class).get());
        } catch (Throwable t) {
            return;
        }
        if (sync.isPresent()) {
            sync.get().run();
        }
    }

    @Override
    public void beginScanning(IEnvironment environment) {
    }

    /** Called by the locator once FML has read every other mod's metadata, before it checks any. */
    private synchronized void relaxDiscovered() {
        System.getProperties().remove(RELAX_HOOK);
        if (relaxed) {
            return;
        }
        relaxed = true;
        try {
            Map<String, List<String>> overrides = analyse().overrides;
            if (!overrides.isEmpty()) {
                rules.relax(OldFml.locatedModFiles(), overrides);
            }
        } catch (Throwable t) {
            LwLog.error("Linkwright failed; leaving Forge's dependency checks untouched", t);
        }
    }

    private Linkwright.Result analyse() throws Exception {
        if (result == null) {
            result = Linkwright.Result.EMPTY;
            result = Linkwright.run(platform);
        }
        return result;
    }

    /** ModLauncher asks for transformers after every scan, so FML has sorted the mods by now. */
    @Override
    @SuppressWarnings("rawtypes")
    public List<ITransformer> transformers() {
        if (platform != null && !relaxed) {
            relaxed = true;
            System.getProperties().remove(RELAX_HOOK);
            try {
                Map<String, List<String>> overrides = analyse().overrides;
                if (!overrides.isEmpty() && OldFml.resortFailed(rules, overrides) > 0) {
                    LwLog.info("Linkwright relaxed " + overrides + " and sorted Forge's mod list again");
                }
            } catch (Throwable t) {
                LwLog.error("Linkwright failed; leaving Forge's dependency checks untouched", t);
            }
        }
        if (result == null || result.patches.isEmpty()) {
            return Collections.emptyList();
        }
        List<ITransformer> transformers = new ArrayList<ITransformer>();
        transformers.add(new BridgeTransformer(result.patches));
        return transformers;
    }
}

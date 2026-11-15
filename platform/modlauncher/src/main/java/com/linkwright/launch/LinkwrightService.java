package com.linkwright.launch;

import com.linkwright.Linkwright;
import com.linkwright.LwLog;
import com.linkwright.neoforge.NeoForgePlatform;
import com.linkwright.scan.Dist;
import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Linkwright's entry point on ModLauncher 9 to 11. FML finds this jar in the mods folder because
 * it declares a transformation service, and ModLauncher drives it through these phases.
 *
 * <p>ModLauncher runs every service's {@code initialize} before any service's
 * {@code beginScanning}, and visits services in the same order both times. NeoForge reads
 * {@code fml.toml} in its own {@code initialize} and sorts mods, checking dependency versions, in
 * its {@code beginScanning}. So if FML's config is already loaded when our {@code initialize}
 * runs, we are behind FML and must act now; otherwise we are ahead of it, and our
 * {@code beginScanning} is the last moment before the sort. Either way the rules are relaxed
 * before the check, and on this same boot.
 */
public final class LinkwrightService implements ITransformationService {
    private NeoForgePlatform platform;
    private Linkwright.Result result;

    @Override
    public String name() {
        return "linkwright";
    }

    @Override
    public void onLoad(IEnvironment environment, Set<String> otherServices) {
    }

    @Override
    public void initialize(IEnvironment environment) {
        Path gameDir = environment.getProperty(IEnvironment.Keys.GAMEDIR.get()).orElse(Paths.get("").toAbsolutePath());
        Dist dist = Dist.fromLaunchTarget(environment.getProperty(IEnvironment.Keys.LAUNCHTARGET.get()).orElse(null));
        if (!NeoForgePlatform.present()) {
            LwLog.warn("Linkwright: this loader is not supported by this build; doing nothing");
            result = Linkwright.Result.EMPTY;
            return;
        }
        platform = NeoForgePlatform.fromLaunch(gameDir, dist);
        if (platform.configLoaded()) {
            runAndApply();
        }
    }

    @Override
    public List<Resource> beginScanning(IEnvironment environment) {
        if (platform != null) {
            runAndApply();
        }
        return Collections.emptyList();
    }

    private void runAndApply() {
        try {
            if (result == null) {
                result = Linkwright.run(platform);
            }
            if (!result.overrides.isEmpty()) {
                platform.relax(result.overrides);
            }
        } catch (Throwable t) {
            LwLog.error("Linkwright failed; leaving " + platform.loaderName() + "'s dependency checks untouched", t);
            if (result == null) {
                result = Linkwright.Result.EMPTY;
            }
        }
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public List transformers() {
        if (result == null || result.patches.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.<ITransformer<?>>singletonList(BridgeTransformer.create(result.patches));
    }
}

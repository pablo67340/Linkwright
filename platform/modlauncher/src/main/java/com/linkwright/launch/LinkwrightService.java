package com.linkwright.launch;

import com.linkwright.Linkwright;
import com.linkwright.LwLog;
import com.linkwright.fml.FmlPlatform;
import com.linkwright.forge.ForgePlatform;
import com.linkwright.neoforge.NeoForgePlatform;
import com.linkwright.scan.Dist;
import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Linkwright's entry point on ModLauncher 9 to 11 (Forge 1.17 and later, NeoForge before 21.9).
 * FML finds this jar in the mods folder because it declares a transformation service, and
 * ModLauncher drives it through these phases.
 *
 * <p>ModLauncher runs every service's {@code initialize}, then every service's
 * {@code beginScanning}, then every service's {@code completeScan}, visiting services in the
 * same order each time. Whether Linkwright sits before or after FML in that order, one of these
 * phases falls between FML becoming ready to take changed rules and FML checking them: NeoForge
 * 21 reads {@code fml.toml} in {@code initialize} and checks in {@code beginScanning}; Forge and
 * older NeoForge discover mods in {@code beginScanning} and check in {@code completeScan}. So each
 * phase asks the loader whether it is ready, and the first one that is applies the rules.
 */
public final class LinkwrightService implements ITransformationService {
    private FmlPlatform platform;
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
        Path gameDir = environment.getProperty(IEnvironment.Keys.GAMEDIR.get()).orElse(Paths.get("").toAbsolutePath());
        Dist dist = Dist.fromLaunchTarget(environment.getProperty(IEnvironment.Keys.LAUNCHTARGET.get()).orElse(null));
        if (NeoForgePlatform.present()) {
            platform = NeoForgePlatform.fromLaunch(gameDir, dist);
        } else if (ForgePlatform.present()) {
            platform = ForgePlatform.fromLaunch(gameDir, dist);
        } else {
            LwLog.warn("Linkwright: no supported loader found; doing nothing");
            result = Linkwright.Result.EMPTY;
            return;
        }
        tryRelax();
    }

    @Override
    public List<Resource> beginScanning(IEnvironment environment) {
        tryRelax();
        return Collections.emptyList();
    }

    @Override
    public List<Resource> completeScan(IModuleLayerManager layerManager) {
        tryRelax();
        return Collections.emptyList();
    }

    private void tryRelax() {
        if (platform == null || relaxed || !platform.readyToRelax()) {
            return;
        }
        relaxed = true;
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
    public List<? extends ITransformer<?>> transformers() {
        if (result == null || result.patches.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.singletonList(new BridgeTransformer(result.patches));
    }
}

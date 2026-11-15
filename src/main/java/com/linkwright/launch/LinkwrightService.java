package com.linkwright.launch;

import com.linkwright.Linkwright;
import com.linkwright.LwLog;
import com.linkwright.scan.Dist;
import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

/**
 * Linkwright's entry point. FML finds this jar in the mods folder because it declares a
 * transformation service, and ModLauncher drives it through these phases.
 *
 * <p>ModLauncher runs every service's {@code initialize} before any service's
 * {@code beginScanning}, and visits services in the same order both times. FML reads
 * {@code fml.toml} in its own {@code initialize} and sorts mods, checking dependency versions, in
 * its {@code beginScanning}. So if FML's config is already loaded when our {@code initialize}
 * runs, we are behind FML and must act now; otherwise we are ahead of it, and our
 * {@code beginScanning} is the last moment before the sort. Either way the overrides are in place
 * before the check, and on this same boot.
 */
public final class LinkwrightService implements ITransformationService {
    private Path gameDir;
    private Dist dist = Dist.CLIENT;
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
        gameDir = environment.getProperty(IEnvironment.Keys.GAMEDIR.get()).orElse(Paths.get("").toAbsolutePath());
        dist = Dist.fromLaunchTarget(environment.getProperty(IEnvironment.Keys.LAUNCHTARGET.get()).orElse(null));
        if (FmlOverrides.configLoaded()) {
            runAndApply();
        }
    }

    @Override
    public List<Resource> beginScanning(IEnvironment environment) {
        runAndApply();
        return List.of();
    }

    private void runAndApply() {
        try {
            if (result == null) {
                result = Linkwright.run(gameDir, dist);
            }
            if (!result.overrides().isEmpty()) {
                FmlOverrides.add(result.overrides());
            }
        } catch (Throwable t) {
            LwLog.error("Linkwright failed; leaving NeoForge's dependency checks untouched", t);
            if (result == null) {
                result = Linkwright.Result.EMPTY;
            }
        }
    }

    @Override
    public List<? extends ITransformer<?>> transformers() {
        if (result == null || result.bridges().isEmpty()) {
            return List.of();
        }
        return List.of(new BridgeTransformer(result.bridges()));
    }
}

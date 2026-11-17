package com.linkwright.legacy;

import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.LaunchClassLoader;

import java.io.File;
import java.util.List;

/**
 * Named by the jar's {@code TweakClass} manifest entry. FML adds it ahead of its own deobfuscation
 * step, so the transformer is in place before FML's {@code Loader} class loads.
 */
public final class LinkwrightTweaker implements ITweaker {
    @Override
    public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) {
        if (gameDir != null) {
            LegacyHook.gameDir = gameDir.toPath();
        }
    }

    @Override
    public void injectIntoClassLoader(LaunchClassLoader classLoader) {
        classLoader.addTransformerExclusion("com.linkwright.");
        classLoader.registerTransformer(LinkwrightTransformer.class.getName());
        LegacyHook.prepare();
    }

    @Override
    public String getLaunchTarget() {
        return "";
    }

    @Override
    public String[] getLaunchArguments() {
        return new String[0];
    }
}

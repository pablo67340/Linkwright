package com.linkwright.neoforge;

import com.linkwright.LwLog;
import com.linkwright.scan.Dist;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the Minecraft and NeoForge jars the game is running from, so links that pass through
 * vanilla classes (a mod method that now takes {@code Level} where the caller has a
 * {@code ServerLevel}) can be checked instead of assumed.
 */
public final class NeoForgeJars {
    private NeoForgeJars() {
    }

    public static List<Path> locate(Path libraryDir, String mcVersion, String neoFormVersion, String neoForgeVersion, Dist dist) {
        List<Path> jars = new ArrayList<>();
        if (libraryDir == null || neoForgeVersion == null) {
            LwLog.debug("Minecraft jars not located (libraries=" + libraryDir + ", mc=" + mcVersion + ", neoform="
                    + neoFormVersion + ", neoforge=" + neoForgeVersion + "); vanilla links will be assumed present");
            return jars;
        }
        String side = dist == Dist.CLIENT ? "client" : "server";
        Path neoforge = libraryDir.resolve("net/neoforged/neoforge").resolve(neoForgeVersion);
        // FML 10 and later: the installer writes the game with NeoForge's patches already applied
        Path patched = libraryDir.resolve("net/neoforged/minecraft-" + side + "-patched").resolve(neoForgeVersion)
                .resolve("minecraft-" + side + "-patched-" + neoForgeVersion + ".jar");
        if (Files.isRegularFile(patched)) {
            jars.add(patched);
        } else if (mcVersion != null && neoFormVersion != null) {
            String mc = mcVersion + "-" + neoFormVersion;
            // Patched classes first, so they shadow the vanilla copies in the srg jar
            add(jars, neoforge.resolve("neoforge-" + neoForgeVersion + "-" + side + ".jar"));
            add(jars, libraryDir.resolve("net/minecraft").resolve(side).resolve(mc).resolve(side + "-" + mc + "-srg.jar"));
        } else {
            LwLog.debug("Minecraft " + side + " jar for NeoForge " + neoForgeVersion + " not found under " + libraryDir);
        }
        add(jars, neoforge.resolve("neoforge-" + neoForgeVersion + "-universal.jar"));
        return jars;
    }

    private static void add(List<Path> jars, Path jar) {
        if (Files.isRegularFile(jar)) {
            jars.add(jar);
        } else {
            LwLog.debug("Expected platform jar not found: " + jar);
        }
    }
}

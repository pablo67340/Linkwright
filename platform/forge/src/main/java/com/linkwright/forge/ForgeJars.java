package com.linkwright.forge;

import com.linkwright.LwLog;
import com.linkwright.fml.LaunchArgs;
import com.linkwright.scan.Dist;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Finds the Minecraft and Forge jars the game is running from. */
public final class ForgeJars {
    private ForgeJars() {
    }

    public static List<Path> fromLaunch(Dist dist, String mcVersion, String mcpVersion, String forgeVersion) {
        return locate(LaunchArgs.libraries(), mcVersion, mcpVersion, forgeVersion, dist);
    }

    /** {@code mcpVersion} may be null: from 1.21.11 the Minecraft jars are stored under the bare game version. */
    public static List<Path> locate(Path libraryDir, String mcVersion, String mcpVersion, String forgeVersion, Dist dist) {
        List<Path> jars = new ArrayList<>();
        if (libraryDir == null || mcVersion == null || forgeVersion == null) {
            LwLog.debug("Minecraft jars not located (libraries=" + libraryDir + ", mc=" + mcVersion + ", mcp="
                    + mcpVersion + ", forge=" + forgeVersion + "); vanilla links will be assumed present");
            return jars;
        }
        String side = dist == Dist.CLIENT ? "client" : "server";
        String forge = mcVersion + "-" + forgeVersion;
        Path forgeDir = libraryDir.resolve("net/minecraftforge/forge").resolve(forge);
        Path sideDir = libraryDir.resolve("net/minecraft").resolve(side);
        // Patched classes first, so they shadow the vanilla copies
        add(jars, forgeDir.resolve("forge-" + forge + "-" + side + ".jar"));
        Path vanilla = null;
        if (mcpVersion != null) {
            String mc = mcVersion + "-" + mcpVersion;
            vanilla = first(sideDir.resolve(mc).resolve(side + "-" + mc + "-srg.jar"),
                    sideDir.resolve(mc).resolve(side + "-" + mc + "-official.jar"));
        }
        if (vanilla == null) {
            // 26.x ships unobfuscated, so the unpacked jar already has Mojang's names; before that it is obfuscated
            Path dir = sideDir.resolve(mcVersion);
            vanilla = first(dir.resolve(side + "-" + mcVersion + "-official.jar"),
                    dir.resolve(side + "-" + mcVersion + "-unpacked.jar"));
        }
        if (vanilla != null) {
            jars.add(vanilla);
        } else {
            LwLog.debug("Minecraft " + side + " jar for " + mcVersion + " not found under " + sideDir);
        }
        add(jars, forgeDir.resolve("forge-" + forge + "-universal.jar"));
        return jars;
    }

    private static Path first(Path... candidates) {
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static void add(List<Path> jars, Path jar) {
        if (Files.isRegularFile(jar)) {
            jars.add(jar);
        } else {
            LwLog.debug("Expected platform jar not found: " + jar);
        }
    }
}

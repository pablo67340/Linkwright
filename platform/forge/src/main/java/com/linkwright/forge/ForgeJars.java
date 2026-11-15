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

    public static List<Path> fromLaunch(Dist dist) {
        return locate(LaunchArgs.libraries(), LaunchArgs.get("--fml.mcVersion"), LaunchArgs.get("--fml.mcpVersion"),
                LaunchArgs.get("--fml.forgeVersion"), dist);
    }

    public static List<Path> locate(Path libraryDir, String mcVersion, String mcpVersion, String forgeVersion, Dist dist) {
        List<Path> jars = new ArrayList<>();
        if (libraryDir == null || mcVersion == null || mcpVersion == null || forgeVersion == null) {
            LwLog.debug("Minecraft jars not located (libraries=" + libraryDir + ", mc=" + mcVersion + ", mcp="
                    + mcpVersion + ", forge=" + forgeVersion + "); vanilla links will be assumed present");
            return jars;
        }
        String side = dist == Dist.CLIENT ? "client" : "server";
        String forge = mcVersion + "-" + forgeVersion;
        String mc = mcVersion + "-" + mcpVersion;
        Path forgeDir = libraryDir.resolve("net/minecraftforge/forge").resolve(forge);
        Path mcDir = libraryDir.resolve("net/minecraft").resolve(side).resolve(mc);
        // Patched classes first, so they shadow the vanilla copies
        add(jars, forgeDir.resolve("forge-" + forge + "-" + side + ".jar"));
        if (!add(jars, mcDir.resolve(side + "-" + mc + "-srg.jar"))) {
            add(jars, mcDir.resolve(side + "-" + mc + "-official.jar"));
        }
        add(jars, forgeDir.resolve("forge-" + forge + "-universal.jar"));
        return jars;
    }

    private static boolean add(List<Path> jars, Path jar) {
        if (Files.isRegularFile(jar)) {
            jars.add(jar);
            return true;
        }
        LwLog.debug("Expected platform jar not found: " + jar);
        return false;
    }
}

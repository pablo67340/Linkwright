package com.linkwright.scan;

import com.linkwright.LwLog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the Minecraft and NeoForge jars the game is running from, so links that pass through
 * vanilla classes (a mod method that now takes {@code Level} where the caller has a
 * {@code ServerLevel}) can be checked instead of assumed.
 */
public final class PlatformJars {
    private PlatformJars() {
    }

    /** Reads the library folder and versions from the launch command, as both launchers and server scripts pass them. */
    public static List<Path> fromLaunch(Dist dist) {
        String command = System.getProperty("sun.java.command", "");
        String libraries = System.getProperty("libraryDirectory");
        Path libraryDir = libraries != null ? Paths.get(libraries) : librariesFromModLauncher();
        return locate(libraryDir, argument(command, "--fml.mcVersion"), argument(command, "--fml.neoFormVersion"),
                argument(command, "--fml.neoForgeVersion"), dist);
    }

    public static List<Path> locate(Path libraryDir, String mcVersion, String neoFormVersion, String neoForgeVersion, Dist dist) {
        List<Path> jars = new ArrayList<>();
        if (libraryDir == null || mcVersion == null || neoFormVersion == null || neoForgeVersion == null) {
            LwLog.debug("Minecraft jars not located (libraries=" + libraryDir + ", mc=" + mcVersion + ", neoform="
                    + neoFormVersion + ", neoforge=" + neoForgeVersion + "); vanilla links will be assumed present");
            return jars;
        }
        String side = dist == Dist.CLIENT ? "client" : "server";
        String mc = mcVersion + "-" + neoFormVersion;
        Path neoforge = libraryDir.resolve("net/neoforged/neoforge").resolve(neoForgeVersion);
        // Patched classes first, so they shadow the vanilla copies in the srg jar
        add(jars, neoforge.resolve("neoforge-" + neoForgeVersion + "-" + side + ".jar"));
        add(jars, libraryDir.resolve("net/minecraft").resolve(side).resolve(mc).resolve(side + "-" + mc + "-srg.jar"));
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

    private static String argument(String command, String name) {
        String[] parts = command.split("\\s+");
        for (int i = 0; i + 1 < parts.length; i++) {
            if (parts[i].equals(name)) {
                return parts[i + 1];
            }
        }
        return null;
    }

    /** ModLauncher lives at {@code <libraries>/cpw/mods/modlauncher/<version>/modlauncher-<version>.jar}. */
    private static Path librariesFromModLauncher() {
        try {
            Class<?> service = Class.forName("cpw.mods.modlauncher.api.ITransformationService");
            Path jar = Paths.get(service.getProtectionDomain().getCodeSource().getLocation().toURI());
            return jar.getParent().getParent().getParent().getParent().getParent();
        } catch (Throwable t) {
            return null;
        }
    }
}

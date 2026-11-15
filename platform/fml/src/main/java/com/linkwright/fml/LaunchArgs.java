package com.linkwright.fml;

import java.nio.file.Path;
import java.nio.file.Paths;

/** What FML's launch command says about the installation, as launchers and server scripts pass it. */
public final class LaunchArgs {
    private LaunchArgs() {
    }

    /** A {@code --name value} argument of the launch command, or {@code null}. */
    public static String get(String name) {
        String[] parts = System.getProperty("sun.java.command", "").split("\\s+");
        for (int i = 0; i + 1 < parts.length; i++) {
            if (parts[i].equals(name)) {
                return parts[i + 1];
            }
        }
        return null;
    }

    /**
     * The libraries folder: the {@code libraryDirectory} property when set, otherwise found from
     * where ModLauncher sits, {@code <libraries>/cpw/mods/modlauncher/<version>/modlauncher-<version>.jar}.
     */
    public static Path libraries() {
        String property = System.getProperty("libraryDirectory");
        if (property != null) {
            return Paths.get(property);
        }
        try {
            Class<?> service = Class.forName("cpw.mods.modlauncher.api.ITransformationService");
            Path jar = Paths.get(service.getProtectionDomain().getCodeSource().getLocation().toURI());
            return jar.getParent().getParent().getParent().getParent().getParent();
        } catch (Throwable t) {
            return null;
        }
    }
}

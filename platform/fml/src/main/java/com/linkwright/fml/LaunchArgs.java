package com.linkwright.fml;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** What FML's launch command says about the installation, as launchers and server scripts pass it. */
public final class LaunchArgs {
    /** Classes whose jars sit five folders below the libraries folder, as {@code <group>/<name>/<version>/<jar>}. */
    private static final String[] LIBRARY_CLASSES = {
            "cpw.mods.modlauncher.api.ITransformationService",
            "net.minecraftforge.fml.loading.FMLLoader",
            "net.neoforged.fml.loading.FMLLoader"};

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
     * A {@code String} property of FML's {@code VersionInfo} (such as {@code mcVersion}), or
     * {@code null} before FML has set it. FML has it as {@code FMLLoader.versionInfo()} up to
     * NeoForge 21.8 and as {@code FMLLoader.getCurrent().getVersionInfo()} from FML 10.
     */
    public static String versionInfo(String loadingPackage, String property) {
        try {
            Class<?> loader = Class.forName(loadingPackage + ".FMLLoader");
            Object info;
            try {
                info = loader.getMethod("versionInfo").invoke(null);
            } catch (NoSuchMethodException e) {
                Object current = loader.getMethod("getCurrentOrNull").invoke(null);
                info = current == null ? null : current.getClass().getMethod("getVersionInfo").invoke(current);
            }
            if (info == null) {
                return null;
            }
            Object value = info.getClass().getMethod(property).invoke(info);
            return value instanceof String && !((String) value).isEmpty() ? (String) value : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The libraries folder: the {@code libraryDirectory} property when set, otherwise found from
     * where ModLauncher or FML sits, {@code <libraries>/<group path>/<name>/<version>/<name>-<version>.jar}.
     */
    public static Path libraries() {
        String property = System.getProperty("libraryDirectory");
        if (property != null) {
            return Paths.get(property);
        }
        for (String name : LIBRARY_CLASSES) {
            Path libraries = librariesFrom(name);
            if (libraries != null) {
                return libraries;
            }
        }
        return null;
    }

    private static Path librariesFrom(String className) {
        try {
            Class<?> type = Class.forName(className, false, LaunchArgs.class.getClassLoader());
            Path jar = jarFile(type.getProtectionDomain().getCodeSource().getLocation().toString());
            Path libraries = jar.getParent().getParent().getParent().getParent().getParent();
            return Files.isDirectory(libraries.resolve("net")) ? libraries : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The jar behind a code source such as {@code file:/x.jar}, {@code jar:file:/x.jar!/} or {@code union:/x.jar%2312!/}. */
    static Path jarFile(String location) throws Exception {
        String s = location;
        if (s.startsWith("jar:")) {
            s = s.substring(4);
        }
        int bang = s.indexOf("!/");
        if (bang >= 0) {
            s = s.substring(0, bang);
        }
        if (s.startsWith("union:")) {
            s = "file:" + s.substring(6);
            int hash = s.lastIndexOf("%23");
            if (hash >= 0) {
                s = s.substring(0, hash);
            }
        }
        return Paths.get(new URI(s));
    }
}

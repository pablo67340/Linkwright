package com.linkwright;

import com.linkwright.scan.ModEntry;
import com.linkwright.scan.ModJar;
import com.linkwright.scan.ModScanner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Problems in the mods folder that no dependency rule describes, but that still stop the game. */
final class Diagnostics {
    /** Classes read per jar to find the Java version it was built for. */
    private static final int SAMPLE = 8;
    private static final int JAVA_OFFSET = 44;

    private Diagnostics() {
    }

    static List<String> check(ModScanner.ModSet mods, int runtimeClassVersion) {
        List<String> warnings = new ArrayList<>();
        duplicates(mods.topLevel(), warnings);
        javaVersions(mods.jars(), runtimeClassVersion, warnings);
        return warnings;
    }

    /** The class file version this JVM runs, such as 65 for Java 21. */
    static int runtimeClassVersion() {
        try {
            return (int) Double.parseDouble(System.getProperty("java.class.version"));
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static void duplicates(List<ModEntry> topLevel, List<String> warnings) {
        Map<String, List<ModEntry>> byId = new LinkedHashMap<>();
        for (ModEntry entry : topLevel) {
            List<ModEntry> list = byId.get(entry.modId());
            if (list == null) {
                list = new ArrayList<>();
                byId.put(entry.modId(), list);
            }
            list.add(entry);
        }
        for (Map.Entry<String, List<ModEntry>> e : byId.entrySet()) {
            Set<String> files = new LinkedHashSet<>();
            for (ModEntry entry : e.getValue()) {
                files.add(entry.jar().name() + " (" + entry.version() + ")");
            }
            if (files.size() > 1) {
                String why = Duplicates.last().kept.get(e.getKey());
                warnings.add(e.getKey() + " is in the mods folder more than once: " + join(files)
                        + ". Keep only one of them." + (why == null ? "" : " Linkwright left them all because " + why + "."));
            }
        }
    }

    private static void javaVersions(List<ModJar> jars, int runtimeClassVersion, List<String> warnings) {
        for (ModJar jar : jars) {
            if (jar.isNested()) {
                continue;
            }
            int needed = highestClassVersion(jar);
            if (needed > runtimeClassVersion) {
                warnings.add(jar.name() + " needs Java " + (needed - JAVA_OFFSET) + " or newer, but the game is running on Java "
                        + (runtimeClassVersion - JAVA_OFFSET) + ". Switch the launcher to a newer Java, or use a"
                        + " version of the mod made for this one.");
            }
        }
    }

    private static int highestClassVersion(ModJar jar) {
        int highest = 0;
        int read = 0;
        try {
            for (String name : jar.entryNames()) {
                if (!name.endsWith(".class") || name.startsWith("META-INF/") || name.endsWith("module-info.class")) {
                    continue;
                }
                byte[] bytes = jar.read(name);
                if (bytes != null && bytes.length >= 8 && (bytes[0] & 0xFF) == 0xCA && (bytes[1] & 0xFF) == 0xFE
                        && (bytes[2] & 0xFF) == 0xBA && (bytes[3] & 0xFF) == 0xBE) {
                    highest = Math.max(highest, ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF));
                }
                if (++read >= SAMPLE) {
                    break;
                }
            }
        } catch (IOException e) {
            LwLog.debug("Linkwright could not read " + jar + ": " + e);
        }
        return highest;
    }

    private static String join(Set<String> items) {
        StringBuilder out = new StringBuilder();
        for (String item : items) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(item);
        }
        return out.toString();
    }
}

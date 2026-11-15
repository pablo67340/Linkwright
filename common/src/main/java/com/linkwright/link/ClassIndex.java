package com.linkwright.link;

import com.linkwright.LwLog;
import com.linkwright.scan.ModJar;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every class in every scanned jar, by internal name. When the loader could not locate the
 * Minecraft jars, anything that resolves into them is treated as "cannot prove missing".
 */
public final class ClassIndex {
    private static final ClassInfo ABSENT = null;

    private final Map<String, ModJar> owners = new HashMap<>();
    private final Map<String, ClassInfo> infos = new HashMap<>();

    public ClassIndex(List<ModJar> jars) {
        for (ModJar jar : jars) {
            try {
                for (String entry : jar.entryNames()) {
                    if (entry.endsWith(".class") && !entry.startsWith("META-INF/") && !entry.endsWith("module-info.class")) {
                        String name = entry.substring(0, entry.length() - 6);
                        if (!owners.containsKey(name)) {
                            owners.put(name, jar);
                        }
                    }
                }
            } catch (IOException e) {
                LwLog.debug("Could not index " + jar + ": " + e);
            }
        }
    }

    public boolean contains(String internalName) {
        return owners.containsKey(internalName);
    }

    public ModJar jarOf(String internalName) {
        return owners.get(internalName);
    }

    public byte[] bytes(String internalName) {
        ModJar jar = owners.get(internalName);
        if (jar == null) {
            return null;
        }
        try {
            return jar.read(internalName + ".class");
        } catch (IOException e) {
            return null;
        }
    }

    public synchronized ClassInfo info(String internalName) {
        if (internalName == null) {
            return null;
        }
        if (infos.containsKey(internalName)) {
            return infos.get(internalName);
        }
        ClassInfo info = ABSENT;
        byte[] bytes = bytes(internalName);
        if (bytes != null) {
            try {
                info = ClassInfo.parse(bytes);
            } catch (RuntimeException unreadable) {
                info = ABSENT;
            }
        }
        infos.put(internalName, info);
        return info;
    }
}

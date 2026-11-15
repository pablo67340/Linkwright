package com.linkwright.link;

import com.linkwright.LwLog;
import com.linkwright.scan.ModJar;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every class in every scanned jar, by internal name. Minecraft and NeoForge themselves are not
 * here: anything that resolves into them is treated as "cannot prove missing".
 */
public final class ClassIndex {
    private final Map<String, ModJar> owners = new HashMap<>();
    private final Map<String, Optional<ClassInfo>> infos = new HashMap<>();

    public ClassIndex(List<ModJar> jars) {
        for (ModJar jar : jars) {
            try {
                for (String entry : jar.entryNames()) {
                    if (entry.endsWith(".class") && !entry.startsWith("META-INF/") && !entry.endsWith("module-info.class")) {
                        owners.putIfAbsent(entry.substring(0, entry.length() - 6), jar);
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
        return infos.computeIfAbsent(internalName, name -> {
            byte[] bytes = bytes(name);
            if (bytes == null) {
                return Optional.empty();
            }
            try {
                return Optional.of(ClassInfo.parse(bytes));
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }).orElse(null);
    }
}

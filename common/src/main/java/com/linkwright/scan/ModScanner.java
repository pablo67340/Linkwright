package com.linkwright.scan;

import com.linkwright.LwLog;
import com.linkwright.version.VersionScheme;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Finds every mod in the mods folders, including ones shipped inside other jars. */
public final class ModScanner {
    private static final int MAX_NESTING = 4;

    private ModScanner() {
    }

    public static final class ModSet implements AutoCloseable {
        private final List<ModJar> jars;
        private final Map<String, ModEntry> mods;
        private final List<ModEntry> topLevel;

        ModSet(List<ModJar> jars, Map<String, ModEntry> mods, List<ModEntry> topLevel) {
            this.jars = Collections.unmodifiableList(jars);
            this.mods = Collections.unmodifiableMap(mods);
            this.topLevel = Collections.unmodifiableList(topLevel);
        }

        public List<ModJar> jars() {
            return jars;
        }

        public Map<String, ModEntry> mods() {
            return mods;
        }

        /** Every mod declared by a jar sitting directly in a mods folder, duplicates included. */
        public List<ModEntry> topLevel() {
            return topLevel;
        }

        @Override
        public void close() {
            for (ModJar jar : jars) {
                jar.close();
            }
        }
    }

    public static ModSet scan(List<Path> modDirs, List<ModReader> readers, VersionScheme versions) throws IOException {
        List<ModJar> jars = new ArrayList<>();
        Map<String, ModEntry> mods = new LinkedHashMap<>();
        List<ModEntry> topLevel = new ArrayList<>();
        for (Path dir : modDirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            List<Path> files = new ArrayList<>();
            DirectoryStream<Path> listing = Files.newDirectoryStream(dir);
            try {
                for (Path p : listing) {
                    if (p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar") && Files.isRegularFile(p)) {
                        files.add(p);
                    }
                }
            } finally {
                listing.close();
            }
            Collections.sort(files);
            for (Path file : files) {
                visit(ModJar.top(file), 0, readers, versions, jars, mods, topLevel);
            }
        }
        return new ModSet(jars, mods, topLevel);
    }

    private static void visit(ModJar jar, int depth, List<ModReader> readers, VersionScheme versions,
                              List<ModJar> jars, Map<String, ModEntry> mods, List<ModEntry> topLevel) {
        jars.add(jar);
        try {
            Set<String> nested = new LinkedHashSet<>();
            for (ModReader reader : readers) {
                for (ModEntry entry : reader.read(jar)) {
                    if (depth == 0) {
                        topLevel.add(entry);
                    }
                    ModEntry existing = mods.get(entry.modId());
                    // Like jar-in-jar resolution, the newest copy of a mod wins when several jars carry it
                    if (existing == null || newer(versions, entry.version(), existing.version())) {
                        mods.put(entry.modId(), entry);
                    }
                }
                nested.addAll(reader.nestedJars(jar));
            }
            if (depth < MAX_NESTING) {
                for (String entry : nested) {
                    visit(jar.nested(entry), depth + 1, readers, versions, jars, mods, topLevel);
                }
            }
        } catch (Exception e) {
            LwLog.debug("Skipping unreadable jar " + jar + ": " + e);
        }
    }

    private static boolean newer(VersionScheme versions, String a, String b) {
        try {
            return versions.compare(a, b) > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }
}

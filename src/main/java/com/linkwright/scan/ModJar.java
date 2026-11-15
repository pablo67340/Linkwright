package com.linkwright.scan;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * One jar on disk, or one jar nested inside another under {@code META-INF/jarjar/}.
 *
 * <p>Nested jars are only expanded into memory when something asks for their contents, and that
 * copy is held softly, so a pack where every dependency rule is met never pays for it.
 */
public final class ModJar implements Closeable {
    private static final String JARJAR_DIR = "META-INF/jarjar/";

    private final String name;
    private final Path path;
    private final ModJar parent;
    private final String entryInParent;

    private ZipFile zip;
    private Set<String> names;
    private SoftReference<Map<String, byte[]>> nestedEntries = new SoftReference<>(null);

    private ModJar(String name, Path path, ModJar parent, String entryInParent) {
        this.name = name;
        this.path = path;
        this.parent = parent;
        this.entryInParent = entryInParent;
    }

    public static ModJar top(Path path) {
        return new ModJar(path.getFileName().toString(), path, null, null);
    }

    public String name() {
        return name;
    }

    public boolean isNested() {
        return parent != null;
    }

    public List<ModJar> nestedJars() throws IOException {
        List<ModJar> nested = new ArrayList<>();
        for (String entry : entryNames()) {
            if (entry.startsWith(JARJAR_DIR) && entry.endsWith(".jar") && entry.indexOf('/', JARJAR_DIR.length()) < 0) {
                nested.add(new ModJar(name + "!/" + entry.substring(JARJAR_DIR.length()), null, this, entry));
            }
        }
        return nested;
    }

    public synchronized Set<String> entryNames() throws IOException {
        if (names == null) {
            if (parent == null) {
                Set<String> collected = new LinkedHashSet<>();
                Enumeration<? extends ZipEntry> entries = zip().entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.isDirectory()) {
                        collected.add(entry.getName());
                    }
                }
                names = Collections.unmodifiableSet(collected);
            } else {
                names = Collections.unmodifiableSet(new LinkedHashSet<>(nested().keySet()));
            }
        }
        return names;
    }

    /** Returns the entry's bytes, or {@code null} when the jar has no such entry. */
    public synchronized byte[] read(String entry) throws IOException {
        if (parent == null) {
            ZipEntry zipEntry = zip().getEntry(entry);
            if (zipEntry == null) {
                return null;
            }
            try (InputStream in = zip().getInputStream(zipEntry)) {
                return in.readAllBytes();
            }
        }
        return nested().get(entry);
    }

    public boolean has(String entry) throws IOException {
        return entryNames().contains(entry);
    }

    private ZipFile zip() throws IOException {
        if (zip == null) {
            zip = new ZipFile(path.toFile());
        }
        return zip;
    }

    private Map<String, byte[]> nested() throws IOException {
        Map<String, byte[]> entries = nestedEntries.get();
        if (entries == null) {
            byte[] bytes = parent.read(entryInParent);
            entries = new LinkedHashMap<>();
            if (bytes != null) {
                try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                    ZipEntry entry;
                    while ((entry = in.getNextEntry()) != null) {
                        if (!entry.isDirectory()) {
                            entries.put(entry.getName(), in.readAllBytes());
                        }
                    }
                }
            }
            nestedEntries = new SoftReference<>(entries);
        }
        return entries;
    }

    @Override
    public synchronized void close() {
        if (zip != null) {
            try {
                zip.close();
            } catch (IOException ignored) {
            }
            zip = null;
        }
        nestedEntries = new SoftReference<>(null);
    }

    @Override
    public String toString() {
        return name;
    }
}

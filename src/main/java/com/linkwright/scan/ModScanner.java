package com.linkwright.scan;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.linkwright.LwLog;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Finds every mod in a mods folder, including ones shipped inside other jars, and reads their
 * dependency rules from {@code META-INF/neoforge.mods.toml}.
 */
public final class ModScanner {
    private static final String MODS_TOML = "META-INF/neoforge.mods.toml";
    private static final int MAX_NESTING = 4;

    private ModScanner() {
    }

    public record ModSet(List<ModJar> jars, Map<String, ModEntry> mods) implements AutoCloseable {
        @Override
        public void close() {
            jars.forEach(ModJar::close);
        }
    }

    public static ModSet scan(Path modsDir) throws IOException {
        List<ModJar> jars = new ArrayList<>();
        Map<String, ModEntry> mods = new LinkedHashMap<>();
        if (!Files.isDirectory(modsDir)) {
            return new ModSet(jars, mods);
        }
        List<Path> files;
        try (Stream<Path> listing = Files.list(modsDir)) {
            files = listing.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
        }
        for (Path file : files) {
            visit(ModJar.top(file), 0, jars, mods);
        }
        return new ModSet(Collections.unmodifiableList(jars), Collections.unmodifiableMap(mods));
    }

    private static void visit(ModJar jar, int depth, List<ModJar> jars, Map<String, ModEntry> mods) {
        jars.add(jar);
        try {
            if (jar.has(MODS_TOML)) {
                for (ModEntry entry : readMods(jar)) {
                    ModEntry existing = mods.get(entry.modId());
                    // Like JarInJar, the newest copy of a mod wins when several jars carry it
                    if (existing == null || entry.artifactVersion().compareTo(existing.artifactVersion()) > 0) {
                        mods.put(entry.modId(), entry);
                    }
                }
            }
            if (depth < MAX_NESTING) {
                for (ModJar nested : jar.nestedJars()) {
                    visit(nested, depth + 1, jars, mods);
                }
            }
        } catch (Exception e) {
            LwLog.debug("Skipping unreadable jar " + jar + ": " + e);
        }
    }

    static List<ModEntry> readMods(ModJar jar) throws IOException {
        byte[] toml = jar.read(MODS_TOML);
        if (toml == null) {
            return List.of();
        }
        UnmodifiableConfig config = new TomlParser().parse(new StringReader(new String(toml, StandardCharsets.UTF_8)));
        String jarVersion = manifestVersion(jar);

        Map<String, List<Dependency>> dependencies = new LinkedHashMap<>();
        Object depsNode = config.get(List.of("dependencies"));
        if (depsNode instanceof UnmodifiableConfig deps) {
            for (UnmodifiableConfig.Entry entry : deps.entrySet()) {
                List<Dependency> parsed = new ArrayList<>();
                if (entry.getValue() instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof UnmodifiableConfig dep) {
                            Dependency d = readDependency(dep);
                            if (d != null) {
                                parsed.add(d);
                            }
                        }
                    }
                }
                dependencies.put(entry.getKey(), parsed);
            }
        }

        List<ModEntry> result = new ArrayList<>();
        Object modsNode = config.get(List.of("mods"));
        if (modsNode instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof UnmodifiableConfig mod)) {
                    continue;
                }
                Object modId = mod.get(List.of("modId"));
                if (!(modId instanceof String id) || id.isBlank()) {
                    continue;
                }
                Object versionNode = mod.get(List.of("version"));
                String version = versionNode instanceof String s ? s : "1";
                if (version.contains("${file.jarVersion}")) {
                    version = version.replace("${file.jarVersion}", jarVersion == null ? "0.0NONE" : jarVersion);
                }
                result.add(new ModEntry(id, version, new DefaultArtifactVersion(version), jar,
                        List.copyOf(dependencies.getOrDefault(id, List.of()))));
            }
        }
        return result;
    }

    private static Dependency readDependency(UnmodifiableConfig dep) {
        Object modId = dep.get(List.of("modId"));
        if (!(modId instanceof String id)) {
            return null;
        }
        Dependency.Type type = Dependency.Type.REQUIRED;
        if (dep.get(List.of("type")) instanceof String t) {
            try {
                type = Dependency.Type.valueOf(t.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        Dependency.Side side = Dependency.Side.BOTH;
        if (dep.get(List.of("side")) instanceof String s) {
            try {
                side = Dependency.Side.valueOf(s.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
            }
        }
        // A missing or blank range means any version, as FML's IModInfo.UNBOUNDED does
        String spec = dep.get(List.of("versionRange")) instanceof String r ? r.trim() : "";
        VersionRange range;
        try {
            range = VersionRange.createFromVersionSpec(spec.isEmpty() ? "[0,)" : spec);
        } catch (InvalidVersionSpecificationException e) {
            range = null;
        }
        return new Dependency(id, spec, range, type, side);
    }

    private static String manifestVersion(ModJar jar) {
        try {
            byte[] bytes = jar.read("META-INF/MANIFEST.MF");
            if (bytes == null) {
                return null;
            }
            return new Manifest(new ByteArrayInputStream(bytes)).getMainAttributes().getValue("Implementation-Version");
        } catch (IOException e) {
            return null;
        }
    }
}

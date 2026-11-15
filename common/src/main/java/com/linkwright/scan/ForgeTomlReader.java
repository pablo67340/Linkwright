package com.linkwright.scan;

import com.linkwright.util.Toml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads {@code META-INF/mods.toml} (Forge 1.13 and later, NeoForge before 20.5) or
 * {@code META-INF/neoforge.mods.toml} (NeoForge 20.5 and later), the same way FML does.
 */
public final class ForgeTomlReader implements ModReader {
    public static final String FORGE = "META-INF/mods.toml";
    public static final String NEOFORGE = "META-INF/neoforge.mods.toml";
    private static final String JARJAR_DIR = "META-INF/jarjar/";

    private final String file;
    /** Before FML 2, a dependency's {@code mandatory} flag stood in for {@code type}. */
    private final boolean mandatoryFlag;

    public ForgeTomlReader(String file, boolean mandatoryFlag) {
        this.file = file;
        this.mandatoryFlag = mandatoryFlag;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ModEntry> read(ModJar jar) throws IOException {
        byte[] bytes = jar.read(file);
        if (bytes == null) {
            return Collections.emptyList();
        }
        Map<String, Object> toml = Toml.parse(new String(bytes, StandardCharsets.UTF_8));
        String jarVersion = jar.manifestVersion();

        Map<String, List<Dependency>> dependencies = new LinkedHashMap<>();
        Object depsNode = toml.get("dependencies");
        if (depsNode instanceof Map) {
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) depsNode).entrySet()) {
                List<Dependency> parsed = new ArrayList<>();
                if (entry.getValue() instanceof List) {
                    for (Object item : (List<Object>) entry.getValue()) {
                        if (item instanceof Map) {
                            Dependency d = readDependency((Map<String, Object>) item);
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
        Object modsNode = toml.get("mods");
        if (modsNode instanceof List) {
            for (Object item : (List<Object>) modsNode) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Map<String, Object> mod = (Map<String, Object>) item;
                Object modId = mod.get("modId");
                if (!(modId instanceof String) || ((String) modId).trim().isEmpty()) {
                    continue;
                }
                Object versionNode = mod.get("version");
                String version = versionNode instanceof String ? (String) versionNode : "1";
                if (version.contains("${file.jarVersion}")) {
                    version = version.replace("${file.jarVersion}", jarVersion == null ? "0.0NONE" : jarVersion);
                }
                List<Dependency> deps = dependencies.get(modId);
                result.add(new ModEntry((String) modId, version, jar,
                        deps == null ? Collections.<Dependency>emptyList() : deps));
            }
        }
        return result;
    }

    private Dependency readDependency(Map<String, Object> dep) {
        Object modId = dep.get("modId");
        if (!(modId instanceof String)) {
            return null;
        }
        Dependency.Type type = Dependency.Type.REQUIRED;
        Object typeNode = dep.get("type");
        if (typeNode instanceof String) {
            try {
                type = Dependency.Type.valueOf(((String) typeNode).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                return null;
            }
        } else if (mandatoryFlag && Boolean.FALSE.equals(dep.get("mandatory"))) {
            type = Dependency.Type.OPTIONAL;
        }
        Dependency.Side side = Dependency.Side.BOTH;
        Object sideNode = dep.get("side");
        if (sideNode instanceof String) {
            try {
                side = Dependency.Side.valueOf(((String) sideNode).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
            }
        }
        Object range = dep.get("versionRange");
        return new Dependency((String) modId, range instanceof String ? (String) range : "", type, side);
    }

    @Override
    public List<String> nestedJars(ModJar jar) throws IOException {
        List<String> nested = new ArrayList<>();
        for (String entry : jar.entryNames()) {
            if (entry.startsWith(JARJAR_DIR) && entry.endsWith(".jar") && entry.indexOf('/', JARJAR_DIR.length()) < 0) {
                nested.add(entry);
            }
        }
        return nested;
    }
}

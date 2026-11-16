package com.linkwright.scan;

import com.linkwright.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Reads {@code fabric.mod.json} the way Fabric Loader does. A rule's version may be one predicate
 * or a list of them, any of which will do; a list is kept as one range joined by
 * {@link #OR}. Mods listed under {@code provides} are aliases of the declaring mod.
 */
public final class FabricModReader implements ModReader {
    public static final String FILE = "fabric.mod.json";
    /** Joins the alternatives of one rule; Fabric's own predicate syntax never contains it. */
    public static final String OR = " || ";

    @Override
    public List<ModEntry> read(ModJar jar) throws IOException {
        Map<String, Object> mod = metadata(jar);
        if (mod == null) {
            return Collections.emptyList();
        }
        String modId = string(mod.get("id"));
        if (modId == null) {
            return Collections.emptyList();
        }
        String version = string(mod.get("version"));
        if (version == null) {
            version = "0";
        }
        Dependency.Side side = side(string(mod.get("environment")));
        List<Dependency> dependencies = new ArrayList<>();
        if (number(mod.get("schemaVersion")) == 0) {
            add(dependencies, mod.get("requires"), Dependency.Type.REQUIRED, side);
        } else {
            add(dependencies, mod.get("depends"), Dependency.Type.REQUIRED, side);
            add(dependencies, mod.get("recommends"), Dependency.Type.ADVISORY, side);
            add(dependencies, mod.get("breaks"), Dependency.Type.INCOMPATIBLE, side);
            add(dependencies, mod.get("conflicts"), Dependency.Type.DISCOURAGED, side);
        }

        List<ModEntry> mods = new ArrayList<>();
        mods.add(new ModEntry(modId, version, jar, dependencies));
        Object provides = mod.get("provides");
        if (provides instanceof List) {
            for (Object alias : (List<?>) provides) {
                String id = string(alias);
                if (id != null) {
                    mods.add(new ModEntry(id, version, jar, Collections.<Dependency>emptyList()));
                }
            }
        }
        return mods;
    }

    @Override
    public List<String> nestedJars(ModJar jar) throws IOException {
        Map<String, Object> mod = metadata(jar);
        Object jars = mod == null ? null : mod.get("jars");
        if (!(jars instanceof List)) {
            return Collections.emptyList();
        }
        List<String> nested = new ArrayList<>();
        for (Object item : (List<?>) jars) {
            String file = item instanceof Map ? string(((Map<?, ?>) item).get("file")) : null;
            if (file != null && jar.has(file)) {
                nested.add(file);
            }
        }
        return nested;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> metadata(ModJar jar) throws IOException {
        byte[] bytes = jar.read(FILE);
        if (bytes == null) {
            return null;
        }
        try {
            Object parsed = Json.parse(new String(bytes, StandardCharsets.UTF_8));
            return parsed instanceof Map ? (Map<String, Object>) parsed : null;
        } catch (IllegalArgumentException e) {
            throw new IOException("unreadable " + FILE + " in " + jar + ": " + e.getMessage(), e);
        }
    }

    /** A mod that only loads on one side only needs its rules met there. */
    private static Dependency.Side side(String environment) {
        if ("client".equals(environment)) {
            return Dependency.Side.CLIENT;
        }
        return "server".equals(environment) ? Dependency.Side.SERVER : Dependency.Side.BOTH;
    }

    private static void add(List<Dependency> out, Object rules, Dependency.Type type, Dependency.Side side) {
        if (!(rules instanceof Map)) {
            return;
        }
        for (Map.Entry<?, ?> rule : ((Map<?, ?>) rules).entrySet()) {
            String modId = string(rule.getKey());
            if (modId == null) {
                continue;
            }
            String range;
            if (rule.getValue() instanceof List) {
                StringBuilder joined = new StringBuilder();
                for (Object alternative : (List<?>) rule.getValue()) {
                    String predicate = string(alternative);
                    if (predicate != null) {
                        joined.append(joined.length() == 0 ? "" : OR).append(predicate);
                    }
                }
                range = joined.toString();
            } else {
                range = string(rule.getValue());
            }
            out.add(new Dependency(modId, range == null || range.equals("*") ? "" : range, type, side));
        }
    }

    private static String string(Object value) {
        if (!(value instanceof String)) {
            return null;
        }
        String trimmed = ((String) value).trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 1;
    }
}

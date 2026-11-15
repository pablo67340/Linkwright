package com.linkwright.neoforge;

import com.linkwright.LwLog;
import com.linkwright.util.Io;
import com.linkwright.util.Toml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The {@code dependencyOverrides} table of {@code config/fml.toml}. */
final class FmlToml {
    private FmlToml() {
    }

    /** For each dependent mod, the dependencies it already has a {@code "-modid"} override for. */
    @SuppressWarnings("unchecked")
    static Map<String, Set<String>> readRemovals(Path fmlToml) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        try {
            if (!Files.exists(fmlToml)) {
                return result;
            }
            Object overrides = Toml.parse(Io.readString(fmlToml)).get("dependencyOverrides");
            if (overrides instanceof Map) {
                for (Map.Entry<String, Object> entry : ((Map<String, Object>) overrides).entrySet()) {
                    if (!(entry.getValue() instanceof List)) {
                        continue;
                    }
                    for (Object item : (List<Object>) entry.getValue()) {
                        if (item instanceof String && ((String) item).startsWith("-")) {
                            Set<String> set = result.get(entry.getKey());
                            if (set == null) {
                                set = new LinkedHashSet<>();
                                result.put(entry.getKey(), set);
                            }
                            set.add(((String) item).substring(1));
                        }
                    }
                }
            }
        } catch (Exception e) {
            LwLog.debug("Could not read " + fmlToml + ": " + e);
        }
        return result;
    }

    /** The line to put in fml.toml to get the same result without Linkwright. */
    static String overridesLine(Map<String, Set<String>> removals) {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, Set<String>> e : removals.entrySet()) {
            if (body.length() > 0) {
                body.append(", ");
            }
            body.append(e.getKey()).append(" = [");
            boolean first = true;
            for (String v : e.getValue()) {
                body.append(first ? "" : ", ").append("\"-").append(v).append('"');
                first = false;
            }
            body.append(']');
        }
        return "dependencyOverrides = { " + body + " }";
    }
}

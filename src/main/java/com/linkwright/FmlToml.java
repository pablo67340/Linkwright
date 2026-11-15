package com.linkwright;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** The {@code dependencyOverrides} table of {@code config/fml.toml}. */
final class FmlToml {
    private FmlToml() {
    }

    /** For each dependent mod, the dependencies it already has a {@code "-modid"} override for. */
    static Map<String, Set<String>> readRemovals(Path fmlToml) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        try {
            if (!Files.exists(fmlToml)) {
                return result;
            }
            UnmodifiableConfig config = new TomlParser().parse(new StringReader(Files.readString(fmlToml, StandardCharsets.UTF_8)));
            if (config.get(List.of("dependencyOverrides")) instanceof UnmodifiableConfig overrides) {
                for (UnmodifiableConfig.Entry entry : overrides.entrySet()) {
                    if (entry.getValue() instanceof List<?> list) {
                        for (Object item : list) {
                            if (item instanceof String s && s.startsWith("-")) {
                                result.computeIfAbsent(entry.getKey(), k -> new LinkedHashSet<>()).add(s.substring(1));
                            }
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
        String body = removals.entrySet().stream()
                .map(e -> e.getKey() + " = [" + e.getValue().stream().map(v -> "\"-" + v + "\"").collect(Collectors.joining(", ")) + "]")
                .collect(Collectors.joining(", "));
        return "dependencyOverrides = { " + body + " }";
    }
}

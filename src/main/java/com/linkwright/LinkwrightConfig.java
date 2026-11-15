package com.linkwright;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** {@code config/linkwright.toml}. */
public record LinkwrightConfig(Mode mode, boolean bridgeCalls, Set<String> ignore, Set<String> forceApprove) {
    public enum Mode {
        AUTO, REPORT, OFF
    }

    static final String DEFAULT_CONTENTS = """
            # Linkwright: dependency reducer and link patcher.
            #
            # When a mod asks for a different version of another mod than the one installed, Linkwright
            # checks every class, method, field and mixin target the first mod uses in the second.
            #
            # mode = "auto"    Approve rules whose links all check out, so the game keeps launching, and
            #                  rewrite calls to methods that gained parameters. Everything is logged to
            #                  logs/linkwright-report.txt.
            # mode = "report"  Only write the report; NeoForge's dependency errors stay as they are.
            # mode = "off"     Do nothing.
            mode = "auto"

            # Adapt calls, field accesses and interface methods that changed shape, when there is exactly
            # one lossless way to do it. Set to false to only approve exact matches.
            bridgeCalls = true

            # Rules Linkwright should never touch, written as "dependent->dependency".
            # Example: ignore = ["somemod->create"]
            ignore = []

            # Rules to approve even when verification finds problems. This can crash the game; use it
            # only after reading the report. Same "dependent->dependency" format.
            forceApprove = []
            """;

    public static LinkwrightConfig defaults() {
        return new LinkwrightConfig(Mode.AUTO, true, Set.of(), Set.of());
    }

    public static LinkwrightConfig load(Path file) {
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, DEFAULT_CONTENTS, StandardCharsets.UTF_8);
                return defaults();
            }
            UnmodifiableConfig config = new TomlParser().parse(new StringReader(Files.readString(file, StandardCharsets.UTF_8)));
            Mode mode = Mode.AUTO;
            if (config.get(List.of("mode")) instanceof String s) {
                try {
                    mode = Mode.valueOf(s.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    LwLog.warn("Unknown mode \"" + s + "\" in " + file + "; using auto");
                }
            }
            boolean bridge = !(config.get(List.of("bridgeCalls")) instanceof Boolean b) || b;
            return new LinkwrightConfig(mode, bridge, pairs(config.get(List.of("ignore"))),
                    pairs(config.get(List.of("forceApprove"))));
        } catch (IOException | RuntimeException e) {
            LwLog.warn("Could not read " + file + " (" + e.getMessage() + "); using defaults");
            return defaults();
        }
    }

    private static Set<String> pairs(Object node) {
        Set<String> result = new LinkedHashSet<>();
        if (node instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s) {
                    result.add(s.replace(" ", "").toLowerCase(Locale.ROOT));
                }
            }
        }
        return result;
    }
}

package com.linkwright;

import com.linkwright.util.Io;
import com.linkwright.util.Toml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** {@code config/linkwright.toml}. */
public final class LinkwrightConfig {
    public enum Mode {
        AUTO, REPORT, OFF
    }

    static final String DEFAULT_CONTENTS = ""
            + "# Linkwright: dependency reducer and link patcher.\n"
            + "#\n"
            + "# When a mod asks for a different version of another mod than the one installed, or for an\n"
            + "# older NeoForge, Forge or Fabric Loader, Linkwright checks every class, method, field and\n"
            + "# mixin target the first mod uses in the second.\n"
            + "#\n"
            + "# mode = \"auto\"    Approve rules whose links all check out, so the game keeps launching, and\n"
            + "#                  adapt calls to methods that changed shape. Everything is logged to\n"
            + "#                  logs/linkwright-report.txt.\n"
            + "# mode = \"report\"  Only write the report; the loader's dependency errors stay as they are.\n"
            + "# mode = \"off\"     Do nothing.\n"
            + "mode = \"auto\"\n"
            + "\n"
            + "# Adapt calls, field accesses and interface methods that changed shape, when there is exactly\n"
            + "# one lossless way to do it. Set to false to only approve exact matches.\n"
            + "bridgeCalls = true\n"
            + "\n"
            + "# When a mod is in the mods folder more than once, keep the copy added last and disable the\n"
            + "# others by renaming them to .jar.disabled. Only happens when the files' creation times\n"
            + "# clearly show which came last; otherwise both stay and the report explains why.\n"
            + "disableDuplicates = true\n"
            + "\n"
            + "# Let a mod that asks for a later build of your loader run on yours, such as NeoForge 21.1.266\n"
            + "# when 21.1.265 is installed. Only when the versions differ in their last number, and every\n"
            + "# class, method, field and mixin target the mod uses in the loader checks out.\n"
            + "olderLoaderBuilds = true\n"
            + "\n"
            + "# Rules Linkwright should never touch, written as \"dependent->dependency\".\n"
            + "# Example: ignore = [\"somemod->create\", \"othermod->neoforge\"]\n"
            + "ignore = []\n"
            + "\n"
            + "# Rules to approve even when verification finds problems. This can crash the game; use it\n"
            + "# only after reading the report. Same \"dependent->dependency\" format.\n"
            + "forceApprove = []\n";

    private final Mode mode;
    private final boolean bridgeCalls;
    private final boolean disableDuplicates;
    private final boolean olderLoaderBuilds;
    private final Set<String> ignore;
    private final Set<String> forceApprove;

    public LinkwrightConfig(Mode mode, boolean bridgeCalls, Set<String> ignore, Set<String> forceApprove) {
        this(mode, bridgeCalls, true, true, ignore, forceApprove);
    }

    public LinkwrightConfig(Mode mode, boolean bridgeCalls, boolean disableDuplicates, boolean olderLoaderBuilds,
                            Set<String> ignore, Set<String> forceApprove) {
        this.mode = mode;
        this.bridgeCalls = bridgeCalls;
        this.disableDuplicates = disableDuplicates;
        this.olderLoaderBuilds = olderLoaderBuilds;
        this.ignore = Collections.unmodifiableSet(new LinkedHashSet<>(ignore));
        this.forceApprove = Collections.unmodifiableSet(new LinkedHashSet<>(forceApprove));
    }

    public Mode mode() {
        return mode;
    }

    public boolean bridgeCalls() {
        return bridgeCalls;
    }

    public boolean disableDuplicates() {
        return disableDuplicates;
    }

    public boolean olderLoaderBuilds() {
        return olderLoaderBuilds;
    }

    public Set<String> ignore() {
        return ignore;
    }

    public Set<String> forceApprove() {
        return forceApprove;
    }

    public static LinkwrightConfig defaults() {
        return new LinkwrightConfig(Mode.AUTO, true, Collections.<String>emptySet(), Collections.<String>emptySet());
    }

    public static LinkwrightConfig load(Path file) {
        try {
            if (!Files.exists(file)) {
                Io.writeString(file, DEFAULT_CONTENTS);
                return defaults();
            }
            Map<String, Object> config = Toml.parse(Io.readString(file));
            Mode mode = Mode.AUTO;
            Object modeValue = config.get("mode");
            if (modeValue instanceof String) {
                try {
                    mode = Mode.valueOf(((String) modeValue).trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    LwLog.warn("Unknown mode \"" + modeValue + "\" in " + file + "; using auto");
                }
            }
            Object bridgeValue = config.get("bridgeCalls");
            boolean bridge = !(bridgeValue instanceof Boolean) || (Boolean) bridgeValue;
            Object duplicatesValue = config.get("disableDuplicates");
            boolean duplicates = !(duplicatesValue instanceof Boolean) || (Boolean) duplicatesValue;
            Object olderValue = config.get("olderLoaderBuilds");
            boolean older = !(olderValue instanceof Boolean) || (Boolean) olderValue;
            return new LinkwrightConfig(mode, bridge, duplicates, older, pairs(config.get("ignore")),
                    pairs(config.get("forceApprove")));
        } catch (Exception e) {
            LwLog.warn("Could not read " + file + " (" + e.getMessage() + "); using defaults");
            return defaults();
        }
    }

    private static Set<String> pairs(Object node) {
        Set<String> result = new LinkedHashSet<>();
        if (node instanceof List) {
            for (Object item : (List<?>) node) {
                if (item instanceof String) {
                    result.add(((String) item).replace(" ", "").toLowerCase(Locale.ROOT));
                }
            }
        }
        return result;
    }
}

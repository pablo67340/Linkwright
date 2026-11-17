package com.linkwright.scan;

import java.util.Collections;
import java.util.List;

/** A mod declared in some jar's metadata, or the loader itself standing in as one. */
public final class ModEntry {
    private final String modId;
    private final String version;
    private final ModJar jar;
    private final List<Dependency> dependencies;
    private final List<String> packages;

    public ModEntry(String modId, String version, ModJar jar, List<Dependency> dependencies) {
        this(modId, version, jar, dependencies, null);
    }

    private ModEntry(String modId, String version, ModJar jar, List<Dependency> dependencies, List<String> packages) {
        this.modId = modId;
        this.version = version;
        this.jar = jar;
        this.dependencies = Collections.unmodifiableList(dependencies);
        this.packages = packages == null ? null : Collections.unmodifiableList(packages);
    }

    /**
     * The loader as a dependency. It shares its jar's packages with the game and its own libraries,
     * so it owns only the classes under {@code packages} (internal names ending in '/').
     */
    public static ModEntry loader(String modId, String version, ModJar jar, List<String> packages) {
        return new ModEntry(modId, version, jar, Collections.<Dependency>emptyList(), packages);
    }

    public String modId() {
        return modId;
    }

    public String version() {
        return version;
    }

    public ModJar jar() {
        return jar;
    }

    public List<Dependency> dependencies() {
        return dependencies;
    }

    public boolean isLoader() {
        return packages != null;
    }

    /** For the loader, the package prefixes it owns in its jar; {@code null} for a mod, which owns its whole jar. */
    public List<String> packages() {
        return packages;
    }

    @Override
    public String toString() {
        return modId + " " + version;
    }
}

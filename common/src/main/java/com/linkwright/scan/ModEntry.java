package com.linkwright.scan;

import java.util.Collections;
import java.util.List;

/** A mod declared in some jar's metadata. */
public final class ModEntry {
    private final String modId;
    private final String version;
    private final ModJar jar;
    private final List<Dependency> dependencies;

    public ModEntry(String modId, String version, ModJar jar, List<Dependency> dependencies) {
        this.modId = modId;
        this.version = version;
        this.jar = jar;
        this.dependencies = Collections.unmodifiableList(dependencies);
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

    @Override
    public String toString() {
        return modId + " " + version;
    }
}

package com.linkwright.scan;

import org.apache.maven.artifact.versioning.ArtifactVersion;

import java.util.List;

/** A mod declared in some jar's {@code neoforge.mods.toml}. */
public record ModEntry(String modId, String version, ArtifactVersion artifactVersion, ModJar jar, List<Dependency> dependencies) {
    @Override
    public String toString() {
        return modId + " " + version;
    }
}

package com.linkwright.scan;

import org.apache.maven.artifact.versioning.VersionRange;

/** One {@code [[dependencies.<mod>]]} entry, read the same way FML reads it. */
public record Dependency(String modId, String rangeSpec, VersionRange range, Type type, Side side) {
    public enum Type {
        REQUIRED, OPTIONAL, INCOMPATIBLE, DISCOURAGED
    }

    public enum Side {
        BOTH, CLIENT, SERVER;

        public boolean appliesTo(Dist dist) {
            return this == BOTH || (this == CLIENT) == (dist == Dist.CLIENT);
        }
    }
}

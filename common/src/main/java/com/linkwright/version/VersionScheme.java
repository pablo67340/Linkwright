package com.linkwright.version;

/**
 * How a loader compares versions and reads version ranges. Every loader brings its own: Forge and
 * NeoForge use Maven ranges, legacy FML a copy of the same, Fabric and Quilt semantic version
 * predicates. Platforms implement this with the loader's own classes, so Linkwright reaches the
 * same verdict the loader will.
 */
public interface VersionScheme {
    /**
     * Whether {@code version} satisfies {@code range}, or {@code null} when the range cannot be
     * read (the loader will report that itself, so Linkwright leaves the rule alone).
     */
    Boolean satisfies(String range, String version);

    /** Orders two versions, for picking the copy of a mod the loader will keep. */
    int compare(String a, String b);
}

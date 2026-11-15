package com.linkwright.maven;

import com.linkwright.version.VersionScheme;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;

/** Maven version ranges, as Forge and NeoForge read them, through the loader's own maven-artifact. */
public final class MavenVersions implements VersionScheme {
    public static final MavenVersions INSTANCE = new MavenVersions();

    private MavenVersions() {
    }

    @Override
    public Boolean satisfies(String range, String version) {
        String spec = range.trim();
        if (spec.isEmpty() || spec.equals("*")) {
            return Boolean.TRUE;
        }
        try {
            return VersionRange.createFromVersionSpec(spec).containsVersion(new DefaultArtifactVersion(version));
        } catch (InvalidVersionSpecificationException e) {
            return null;
        }
    }

    @Override
    public int compare(String a, String b) {
        return new DefaultArtifactVersion(a).compareTo(new DefaultArtifactVersion(b));
    }
}

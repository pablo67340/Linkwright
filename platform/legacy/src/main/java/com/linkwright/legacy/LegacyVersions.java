package com.linkwright.legacy;

import com.linkwright.version.VersionScheme;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Legacy FML's own copy of Maven's version rules, used through the same calls FML makes when it
 * checks a mod's rules ({@code VersionParser.parseVersionReference}, then
 * {@code containsVersion} on the dependency's processed version).
 */
final class LegacyVersions implements VersionScheme {
    private final Method parseReference;
    private final Method containsVersion;
    private final Constructor<?> artifactVersion;
    private final Constructor<?> comparableVersion;

    /** @param fml {@code cpw.mods.fml} before Forge 1.8, {@code net.minecraftforge.fml} after */
    LegacyVersions(String fml, ClassLoader classLoader) throws ReflectiveOperationException {
        String versioning = fml + ".common.versioning.";
        parseReference = Class.forName(versioning + "VersionParser", true, classLoader).getMethod("parseVersionReference", String.class);
        Class<?> artifact = Class.forName(versioning + "ArtifactVersion", true, classLoader);
        containsVersion = artifact.getMethod("containsVersion", artifact);
        artifactVersion = Class.forName(versioning + "DefaultArtifactVersion", true, classLoader).getConstructor(String.class, String.class);
        comparableVersion = Class.forName(versioning + "ComparableVersion", true, classLoader).getConstructor(String.class);
    }

    @Override
    public Boolean satisfies(String range, String version) {
        try {
            Object rule = parseReference.invoke(null, "mod@" + range);
            return (Boolean) containsVersion.invoke(rule, artifactVersion.newInstance("mod", version));
        } catch (ReflectiveOperationException e) {
            // FML reports a range it cannot read itself
            return null;
        }
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public int compare(String a, String b) {
        try {
            return ((Comparable) comparableVersion.newInstance(a)).compareTo(comparableVersion.newInstance(b));
        } catch (ReflectiveOperationException e) {
            return a.compareTo(b);
        }
    }
}

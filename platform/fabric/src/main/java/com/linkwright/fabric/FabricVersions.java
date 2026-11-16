package com.linkwright.fabric;

import com.linkwright.scan.FabricModReader;
import com.linkwright.version.VersionScheme;

import java.lang.reflect.Method;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Fabric Loader's own version and predicate parsing, so every answer matches the loader's. */
final class FabricVersions implements VersionScheme {
    private static final Pattern OR = Pattern.compile(Pattern.quote(FabricModReader.OR));

    private final Method parseVersion;
    private final Method parsePredicate;

    FabricVersions(ClassLoader classLoader) throws ReflectiveOperationException {
        parseVersion = Class.forName("net.fabricmc.loader.api.Version", false, classLoader)
                .getMethod("parse", String.class);
        parsePredicate = Class.forName("net.fabricmc.loader.api.metadata.version.VersionPredicate", false, classLoader)
                .getMethod("parse", String.class);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Boolean satisfies(String range, String version) {
        if (range == null || range.trim().isEmpty()) {
            return Boolean.TRUE;
        }
        try {
            Object parsed = parseVersion.invoke(null, version);
            for (String alternative : OR.split(range)) {
                if (((Predicate<Object>) parsePredicate.invoke(null, alternative.trim())).test(parsed)) {
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            // Fabric Loader rejects the range or version itself
            return null;
        }
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public int compare(String a, String b) {
        try {
            return ((Comparable) parseVersion.invoke(null, a)).compareTo(parseVersion.invoke(null, b));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return a.compareTo(b);
        }
    }
}

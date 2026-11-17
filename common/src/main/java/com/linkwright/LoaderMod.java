package com.linkwright;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The loader as something mods declare version rules on, such as {@code neoforge} or {@code fabricloader}. */
public final class LoaderMod {
    public final Set<String> ids;
    public final String version;
    public final Path jar;
    /** Package prefixes (internal names ending in '/') the loader owns inside {@link #jar}. */
    public final List<String> packages;

    public LoaderMod(Set<String> ids, String version, Path jar, List<String> packages) {
        this.ids = Collections.unmodifiableSet(new LinkedHashSet<>(ids));
        this.version = version;
        this.jar = jar;
        this.packages = Collections.unmodifiableList(packages);
    }

    public static LoaderMod of(String id, String version, Path jar, String... packages) {
        return new LoaderMod(Collections.singleton(id), version, jar, Arrays.asList(packages));
    }

    /** The first jar whose file name starts with {@code prefix} and ends with {@code suffix}, or {@code null}. */
    public static Path find(List<Path> jars, String prefix, String suffix) {
        if (jars == null) {
            return null;
        }
        for (Path jar : jars) {
            String name = jar.getFileName().toString();
            if (name.startsWith(prefix) && name.endsWith(suffix)) {
                return jar;
            }
        }
        return null;
    }
}

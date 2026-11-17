package com.linkwright;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A mod in the mods folder twice: the copy added last stays, even when it is the older version,
 * and every case where the files cannot tell which came last leaves both alone.
 */
class DuplicatesTest {
    private static final long HOUR = 3600000L;
    /** Release dates carried over from the download, long before either copy was added. */
    private static final long BUILT = System.currentTimeMillis() - 400 * 24 * HOUR;

    @TempDir
    Path temp;

    @Test
    void disablesTheCopyAddedFirstEvenWhenItIsNewer() throws Exception {
        Path mods = Files.createDirectories(temp.resolve("mods"));
        Path newer = jar(mods, "create-6.0.10.jar", toml("create", "6.0.10"), null);
        Path older = jar(mods, "create-6.0.9.jar", toml("create", "6.0.9"), null);
        added(newer, 3 * HOUR);
        added(older, HOUR);

        Duplicates.Outcome outcome = resolve(mods);

        assertEquals(1, outcome.disabled.size(), outcome.kept.toString());
        assertTrue(outcome.disabled.get(0).startsWith("create was in the mods folder more than once. Kept create-6.0.9.jar"),
                outcome.disabled.get(0));
        assertFalse(Files.exists(newer));
        assertTrue(Files.exists(mods.resolve("create-6.0.10.jar.disabled")));
        assertTrue(Files.exists(older));

        Linkwright.Analysis analysis = Linkwright.analyze(new TestPlatform(mods), LinkwrightConfig.defaults());
        assertTrue(analysis.warnings.isEmpty(), analysis.warnings.toString());
    }

    @Test
    void leavesCopiesAddedTogether() throws Exception {
        Path mods = Files.createDirectories(temp.resolve("mods"));
        added(jar(mods, "a-1.jar", toml("a", "1"), null), HOUR);
        added(jar(mods, "a-2.jar", toml("a", "2"), null), HOUR - 10000L);

        Duplicates.Outcome outcome = resolve(mods);

        assertTrue(outcome.disabled.isEmpty());
        assertEquals("the copies were added within a minute of each other", outcome.kept.get("a"));
        assertTrue(Files.exists(mods.resolve("a-1.jar")) && Files.exists(mods.resolve("a-2.jar")));

        Linkwright.Analysis analysis = Linkwright.analyze(new TestPlatform(mods), LinkwrightConfig.defaults());
        assertTrue(analysis.warnings.get(0).endsWith("Linkwright left them all because the copies were added within a minute of each other."),
                analysis.warnings.toString());
    }

    @Test
    void leavesFilesWithoutACreationTime() throws Exception {
        Path mods = Files.createDirectories(temp.resolve("mods"));
        Path first = jar(mods, "a-1.jar", toml("a", "1"), null);
        Path second = jar(mods, "a-2.jar", toml("a", "2"), null);
        added(first, 3 * HOUR);
        long now = System.currentTimeMillis() - HOUR;
        Files.setAttribute(second, "basic:creationTime", FileTime.fromMillis(now));
        Files.setLastModifiedTime(second, FileTime.fromMillis(now));

        Duplicates.Outcome outcome = resolve(mods);

        assertTrue(outcome.disabled.isEmpty());
        assertEquals("the file system does not record when a-2.jar was added", outcome.kept.get("a"));
    }

    @Test
    void leavesAnOlderCopyThatAlsoCarriesAnotherMod() throws Exception {
        Path mods = Files.createDirectories(temp.resolve("mods"));
        added(jar(mods, "bundle-1.jar", toml("a", "1") + mod("b", "1"), null), 3 * HOUR);
        added(jar(mods, "a-2.jar", toml("a", "2"), null), HOUR);

        Duplicates.Outcome outcome = resolve(mods);

        assertTrue(outcome.disabled.isEmpty());
        assertEquals("bundle-1.jar also contains [b], which a-2.jar does not", outcome.kept.get("a"));
    }

    @Test
    void leavesAnOlderCopyTheLoaderLoadedBeforeLinkwright() throws Exception {
        Path mods = Files.createDirectories(temp.resolve("mods"));
        added(jar(mods, "a-1.jar", toml("a", "1"),
                "META-INF/services/cpw.mods.modlauncher.api.ITransformationService"), 3 * HOUR);
        added(jar(mods, "a-2.jar", toml("a", "2"), null), HOUR);

        Duplicates.Outcome outcome = resolve(mods);

        assertTrue(outcome.disabled.isEmpty());
        assertEquals("a-1.jar is loaded by the loader before Linkwright runs", outcome.kept.get("a"));
    }

    @Test
    void neverOverwritesAnEarlierDisabledCopy() throws Exception {
        Path mods = Files.createDirectories(temp.resolve("mods"));
        added(jar(mods, "a-1.jar", toml("a", "1"), null), 3 * HOUR);
        added(jar(mods, "a-2.jar", toml("a", "2"), null), HOUR);
        Files.write(mods.resolve("a-1.jar.disabled"), new byte[]{1});

        Duplicates.Outcome outcome = resolve(mods);

        assertTrue(outcome.disabled.isEmpty());
        assertEquals("a-1.jar.disabled already exists", outcome.kept.get("a"));
        assertEquals(1, Files.size(mods.resolve("a-1.jar.disabled")));
    }

    private static Duplicates.Outcome resolve(Path mods) {
        TestPlatform platform = new TestPlatform(mods);
        return Duplicates.resolve(platform.modDirs(), platform.readers(), platform.versions());
    }

    /** Marks a file as added {@code ago} milliseconds ago, from a download built long before. */
    private static Path added(Path file, long ago) throws IOException {
        Files.setAttribute(file, "basic:creationTime", FileTime.fromMillis(System.currentTimeMillis() - ago));
        Files.setLastModifiedTime(file, FileTime.fromMillis(BUILT));
        return file;
    }

    private static String toml(String modId, String version) {
        return "modLoader = \"javafml\"\nloaderVersion = \"[1,)\"\nlicense = \"MIT\"\n" + mod(modId, version);
    }

    private static String mod(String modId, String version) {
        return "[[mods]]\nmodId = \"" + modId + "\"\nversion = \"" + version + "\"\n";
    }

    private static Path jar(Path dir, String name, String toml, String extraEntry) throws IOException {
        Path file = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("META-INF/neoforge.mods.toml"));
            zip.write(toml.getBytes(StandardCharsets.UTF_8));
            if (extraEntry != null) {
                zip.putNextEntry(new ZipEntry(extraEntry));
                zip.write("x.Y\n".getBytes(StandardCharsets.UTF_8));
            }
        }
        return file;
    }
}

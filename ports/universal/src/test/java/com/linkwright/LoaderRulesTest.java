package com.linkwright;

import com.linkwright.link.PairReport;
import com.linkwright.maven.MavenVersions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.*;

/**
 * A fake NeoForge 21.1.265 and a mod that pins it to exactly 21.1.243, like colonyworks does.
 * Linkwright should approve the rule when every call into NeoForge still resolves, refuse it when
 * one does not, and leave rules alone that only a newer NeoForge would satisfy.
 */
class LoaderRulesTest {
    private static final String NEO = "net/neoforged/neoforge/common/NeoForge";
    private static final String PINNED = "[[dependencies.colonyworks]]\nmodId = \"neoforge\"\ntype = \"required\"\n"
            + "versionRange = \"[21.1.243]\"\nordering = \"NONE\"\nside = \"BOTH\"\n";

    @TempDir
    Path temp;

    @Test
    void readsWhetherAVersionIsAboveARange() {
        MavenVersions maven = MavenVersions.INSTANCE;
        assertTrue(Linkwright.aboveRange(maven, "[21.1.243]", "21.1.265"));
        assertTrue(Linkwright.aboveRange(maven, "[21.1,21.2)", "21.2.5"));
        assertTrue(Linkwright.aboveRange(maven, "(,21.1.200]", "21.1.265"));
        assertFalse(Linkwright.aboveRange(maven, "[21.1.300,)", "21.1.265"));
        assertFalse(Linkwright.aboveRange(maven, "[21.1.200,21.1.250],[21.1.300,)", "21.1.265"));
        assertFalse(Linkwright.aboveRange(maven, "*", "21.1.265"));
    }

    @Test
    void approvesANewerLoaderWhenEveryCallResolves() throws Exception {
        Path mods = Files.createDirectories(temp.resolve("mods"));
        jar(mods.resolve("colonyworks.jar"), Map.of("cw/Main", caller("cw/Main", NEO, "hello")),
                toml("colonyworks", "0.3.4", PINNED));

        Linkwright.Analysis analysis = Linkwright.analyze(platform(mods), LinkwrightConfig.defaults());
        System.out.println(ReportWriter.render(LinkwrightConfig.defaults(), "NeoForge", "client",
                analysis.result.reports, null, analysis.warnings));

        PairReport report = analysis.result.reports.get(0);
        assertEquals(PairReport.Decision.APPROVED, report.decision, report.problems.toString());
        assertTrue(report.dependency.isLoader());
        assertTrue(report.linksChecked > 0);
        assertEquals(Map.of("colonyworks", List.of("neoforge")), analysis.result.overrides);
    }

    @Test
    void refusesWhenTheLoaderLostAMethodOrClass() throws Exception {
        Path mods = Files.createDirectories(temp.resolve("mods"));
        jar(mods.resolve("colonyworks.jar"), Map.of("cw/Main", caller("cw/Main", NEO, "gone"),
                "cw/Other", caller("cw/Other", "net/neoforged/neoforge/common/Removed", "hello")),
                toml("colonyworks", "0.3.4", PINNED));

        Linkwright.Analysis analysis = Linkwright.analyze(platform(mods), LinkwrightConfig.defaults());

        PairReport report = analysis.result.reports.get(0);
        assertEquals(PairReport.Decision.REFUSED, report.decision);
        assertEquals(2, report.problems.size(), report.problems.toString());
        assertTrue(analysis.result.overrides.isEmpty());
    }

    @Test
    void leavesRulesThatNeedANewerLoader() throws Exception {
        Path mods = Files.createDirectories(temp.resolve("mods"));
        jar(mods.resolve("newmod.jar"), Map.of("nm/Main", caller("nm/Main", NEO, "hello")), toml("newmod", "1.0",
                "[[dependencies.newmod]]\nmodId = \"neoforge\"\ntype = \"required\"\nversionRange = \"[21.1.300,)\"\n"));

        Linkwright.Analysis analysis = Linkwright.analyze(platform(mods), LinkwrightConfig.defaults());

        assertTrue(analysis.result.reports.isEmpty());
    }

    @Test
    void leavesLoaderRulesWhenTheLoaderIsUnknown() throws Exception {
        Path mods = Files.createDirectories(temp.resolve("mods"));
        jar(mods.resolve("colonyworks.jar"), Map.of("cw/Main", caller("cw/Main", NEO, "hello")),
                toml("colonyworks", "0.3.4", PINNED));

        Linkwright.Analysis analysis = Linkwright.analyze(new TestPlatform(mods), LinkwrightConfig.defaults());

        assertTrue(analysis.result.reports.isEmpty());
    }

    @Test
    void warnsAboutDuplicatesAndJarsBuiltForANewerJava() throws Exception {
        Path mods = Files.createDirectories(temp.resolve("mods"));
        jar(mods.resolve("create-6.0.9.jar"), Map.of("c/A", type("c/A", V17)), toml("create", "6.0.9", ""));
        jar(mods.resolve("create-6.0.10.jar"), Map.of("c/A", type("c/A", V17)), toml("create", "6.0.10", ""));
        jar(mods.resolve("shiny.jar"), Map.of("s/A", type("s/A", V21)), toml("shiny", "1.0", ""));

        Linkwright.Analysis analysis = Linkwright.analyze(new TestPlatform(mods), LinkwrightConfig.defaults(), 61);

        System.out.println(ReportWriter.render(LinkwrightConfig.defaults(), "Test", "client",
                analysis.result.reports, null, analysis.warnings));
        assertEquals(2, analysis.warnings.size(), analysis.warnings.toString());
        assertTrue(analysis.warnings.get(0).startsWith("create is in the mods folder more than once"));
        assertTrue(analysis.warnings.get(1).startsWith("shiny.jar needs Java 21 or newer, but the game is running on Java 17"));
    }

    private TestPlatform platform(Path mods) throws IOException {
        Path neoforge = temp.resolve("neoforge-21.1.265-universal.jar");
        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put(NEO, api(NEO));
        // The loader jar's other packages are not the loader's to answer for
        classes.put("net/minecraft/world/Thing", type("net/minecraft/world/Thing", V17));
        jar(neoforge, classes, null);
        return new TestPlatform(mods, LoaderMod.of("neoforge", "21.1.265", neoforge, "net/neoforged/neoforge/"));
    }

    private static String toml(String modId, String version, String deps) {
        return "modLoader = \"javafml\"\nloaderVersion = \"[1,)\"\nlicense = \"MIT\"\n[[mods]]\nmodId = \"" + modId
                + "\"\nversion = \"" + version + "\"\n" + deps;
    }

    private static void jar(Path file, Map<String, byte[]> classes, String toml) throws IOException {
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            if (toml != null) {
                zip.putNextEntry(new ZipEntry("META-INF/neoforge.mods.toml"));
                zip.write(toml.getBytes(StandardCharsets.UTF_8));
            }
            for (Map.Entry<String, byte[]> e : classes.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey() + ".class"));
                zip.write(e.getValue());
            }
        }
    }

    private static byte[] type(String name, int version) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(version, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static void hello()}. */
    private static byte[] api(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "hello", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class whose {@code run()} calls {@code owner.method()}. */
    private static byte[] caller(String name, String owner, String method) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(INVOKESTATIC, owner, method, "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}

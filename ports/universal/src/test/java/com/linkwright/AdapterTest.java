package com.linkwright;

import com.linkwright.link.BridgePatcher;
import com.linkwright.link.FieldBridge;
import com.linkwright.link.ImplBridge;
import com.linkwright.link.PairReport;
import com.linkwright.link.Patch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.*;

/**
 * "modb" 3.0 changed four things "moda" (built for modb 1.x) depends on:
 * <ul>
 *     <li>{@code Api.sum(int, int)} became {@code sum(long, int)}: argument widened</li>
 *     <li>{@code Api.join(String, String)} became {@code join(String, Ctx, String)}: parameter added in the middle</li>
 *     <li>{@code Box.count} went from {@code int} to {@code Integer}: field type changed</li>
 *     <li>{@code Shape.area()} went from {@code float} to {@code double}: interface method changed</li>
 * </ul>
 * All four should be adapted, and the adapted code should run and give the right answers.
 */
class AdapterTest {
    @TempDir
    Path temp;

    @Test
    void adaptsWideningInsertionFieldsAndImplementations() throws Exception {
        Path mods = Files.createDirectories(temp.resolve("mods"));
        Map<String, byte[]> modb = Map.of("b/Api", api(), "b/Ctx", ctx(), "b/Shape", shape(), "b/Box", box());
        Map<String, byte[]> moda = Map.of("a/User", user(), "a/Square", square());
        jar(mods.resolve("modb.jar"), modb, toml("modb", "3.0", ""));
        jar(mods.resolve("moda.jar"), moda, toml("moda", "1.0", """
                [[dependencies.moda]]
                    modId = "modb"
                    versionRange = "[1,2)"
                """));

        Linkwright.Analysis analysis = Linkwright.analyze(new TestPlatform(mods), LinkwrightConfig.defaults());
        System.out.println(ReportWriter.render(LinkwrightConfig.defaults(), "Test", "client",
                analysis.result.reports, null));
        PairReport report = analysis.result.reports.get(0);
        assertEquals(PairReport.Decision.APPROVED, report.decision, report.problems.toString());
        List<Patch> patches = analysis.result.patches;
        assertEquals(5, patches.size(), patches.toString());
        assertEquals(2, patches.stream().filter(p -> p instanceof FieldBridge).count());
        assertEquals(1, patches.stream().filter(p -> p instanceof ImplBridge).count());

        Map<String, byte[]> all = new HashMap<>(modb);
        for (Map.Entry<String, byte[]> e : moda.entrySet()) {
            ClassNode node = new ClassNode();
            new ClassReader(e.getValue()).accept(node, 0);
            BridgePatcher.apply(node, patches);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            all.put(e.getKey(), writer.toByteArray());
        }
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = all.get(name.replace('.', '/'));
                if (bytes == null) {
                    throw new ClassNotFoundException(name);
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        Class<?> user = loader.loadClass("a.User");
        Class<?> ctxClass = loader.loadClass("b.Ctx");
        Class<?> boxClass = loader.loadClass("b.Box");

        assertEquals(5L, user.getMethod("add", int.class, int.class).invoke(null, 2, 3));
        Object ctx = ctxClass.getConstructor(String.class).newInstance("X");
        assertEquals("aXc", user.getMethod("wrap", String.class, String.class, ctxClass).invoke(null, "a", "c", ctx));

        Object box = boxClass.getConstructor().newInstance();
        boxClass.getField("count").set(box, 41);
        assertEquals(42, user.getMethod("bump", boxClass).invoke(null, box));
        assertEquals(42, boxClass.getField("count").get(box));

        Object square = loader.loadClass("a.Square").getConstructor().newInstance();
        assertEquals(4.0d, loader.loadClass("b.Shape").getMethod("area").invoke(square));
    }

    @Test
    void refusesUnconvertibleResultsAndPrimitiveInsertion() throws Exception {
        Path mods = Files.createDirectories(temp.resolve("mods"));
        ClassWriter cw = begin("b/Api", "java/lang/Object");
        // A String cannot stand in for the long callers expect, and an added int has no safe value
        staticReturn(cw, "sum", "(II)Ljava/lang/String;");
        staticReturn(cw, "join", "(Ljava/lang/String;ILjava/lang/String;)Ljava/lang/String;");
        cw.visitEnd();
        jar(mods.resolve("modb.jar"), Map.of("b/Api", cw.toByteArray(), "b/Ctx", ctx(), "b/Shape", shape(), "b/Box", box()),
                toml("modb", "3.0", ""));
        jar(mods.resolve("moda.jar"), Map.of("a/User", user(), "a/Square", square()), toml("moda", "1.0", """
                [[dependencies.moda]]
                    modId = "modb"
                    versionRange = "[1,2)"
                """));
        Linkwright.Analysis analysis = Linkwright.analyze(new TestPlatform(mods), LinkwrightConfig.defaults());
        PairReport report = analysis.result.reports.get(0);
        System.out.println(report.problems);
        assertEquals(PairReport.Decision.REFUSED, report.decision);
        assertTrue(report.problems.stream().anyMatch(p -> p.subject.contains("sum")));
        assertTrue(report.problems.stream().anyMatch(p -> p.subject.contains("join")));
    }

    private static String toml(String modId, String version, String deps) {
        return "modLoader = \"javafml\"\nloaderVersion = \"[1,)\"\nlicense = \"MIT\"\n[[mods]]\nmodId = \"" + modId
                + "\"\nversion = \"" + version + "\"\n" + deps;
    }

    private static void jar(Path file, Map<String, byte[]> classes, String toml) throws IOException {
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("META-INF/neoforge.mods.toml"));
            zip.write(toml.getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, byte[]> e : classes.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey() + ".class"));
                zip.write(e.getValue());
            }
        }
    }

    private static ClassWriter begin(String name, String superName, String... interfaces) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, superName, interfaces.length == 0 ? null : interfaces);
        return cw;
    }

    private static void defaultConstructor(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void staticReturn(ClassWriter cw, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static byte[] api() {
        ClassWriter cw = begin("b/Api", "java/lang/Object");
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "sum", "(JI)J", null, null);
        mv.visitCode();
        mv.visitVarInsn(LLOAD, 0);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitInsn(I2L);
        mv.visitInsn(LADD);
        mv.visitInsn(LRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "join", "(Ljava/lang/String;Lb/Ctx;Ljava/lang/String;)Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, "b/Ctx", "name", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] ctx() {
        ClassWriter cw = begin("b/Ctx", "java/lang/Object");
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "name", "Ljava/lang/String;", null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/lang/String;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitFieldInsn(PUTFIELD, "b/Ctx", "name", "Ljava/lang/String;");
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        mv = cw.visitMethod(ACC_PUBLIC, "name", "()Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, "b/Ctx", "name", "Ljava/lang/String;");
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] shape() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, "b/Shape", null, "java/lang/Object", null);
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "area", "()D", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] box() {
        ClassWriter cw = begin("b/Box", "java/lang/Object");
        cw.visitField(ACC_PUBLIC, "count", "Ljava/lang/Integer;", null, null).visitEnd();
        defaultConstructor(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Compiled against modb 1.x. */
    private static byte[] user() {
        ClassWriter cw = begin("a/User", "java/lang/Object");
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "add", "(II)J", null, null);
        mv.visitCode();
        mv.visitVarInsn(ILOAD, 0);
        mv.visitVarInsn(ILOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, "b/Api", "sum", "(II)J", false);
        mv.visitInsn(LRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "wrap", "(Ljava/lang/String;Ljava/lang/String;Lb/Ctx;)Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, "b/Api", "join", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "bump", "(Lb/Box;)I", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, "b/Box", "count", "I");
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IADD);
        mv.visitFieldInsn(PUTFIELD, "b/Box", "count", "I");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, "b/Box", "count", "I");
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Implements modb 1.x's {@code float area()}. */
    private static byte[] square() {
        ClassWriter cw = begin("a/Square", "java/lang/Object", "b/Shape");
        defaultConstructor(cw);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "area", "()F", null, null);
        mv.visitCode();
        mv.visitLdcInsn(4.0f);
        mv.visitInsn(FRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}

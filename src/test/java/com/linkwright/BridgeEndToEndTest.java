package com.linkwright;

import com.linkwright.link.Patch;
import com.linkwright.link.BridgePatcher;
import com.linkwright.link.PairReport;
import com.linkwright.scan.Dist;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
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
 * Two fake mods: "modb" 3.0 changed {@code Api.check(String, List)} to also take a {@code Ctx},
 * and {@code Api.tag(String)} to {@code tag(Ctx, String)}; "moda" pins modb to [1,2) and still
 * calls the old signatures. Linkwright should approve the rule, bridge both calls, and the
 * patched code should actually run.
 */
class BridgeEndToEndTest {
    @TempDir
    Path temp;

    @Test
    void approvesAndBridgesChangedSignatures() throws Exception {
        Path mods = Files.createDirectories(temp.resolve("mods"));
        Map<String, byte[]> modbClasses = Map.of("b/Api", api(), "b/Ctx", ctx(), "b/Holder", holder());
        Map<String, byte[]> modaClasses = Map.of("a/User", user());
        jar(mods.resolve("modb.jar"), modbClasses, toml("modb", "3.0", ""));
        jar(mods.resolve("moda.jar"), modaClasses, toml("moda", "1.0", """
                [[dependencies.moda]]
                    modId = "modb"
                    type = "required"
                    versionRange = "[1,2)"
                    ordering = "AFTER"
                    side = "BOTH"
                """));

        Linkwright.Analysis analysis = Linkwright.analyze(mods, List.of(), Map.of(), LinkwrightConfig.defaults(), Dist.CLIENT);
        System.out.println(ReportWriter.render(LinkwrightConfig.defaults(), Dist.CLIENT,
                analysis.result().reports(), analysis.approved()));

        PairReport report = analysis.result().reports().get(0);
        assertEquals(PairReport.Decision.APPROVED, report.decision, report.problems.toString());
        assertEquals(Map.of("moda", List.of("modb")), analysis.result().overrides());
        List<Patch> patches = analysis.result().patches();
        assertEquals(2, patches.size());

        ClassNode node = new ClassNode();
        new ClassReader(modaClasses.get("a/User")).accept(node, 0);
        assertEquals(2, BridgePatcher.apply(node, patches));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);

        Map<String, byte[]> all = new HashMap<>(modbClasses);
        all.put("a/User", writer.toByteArray());
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
        Class<?> ctxClass = loader.loadClass("b.Ctx");
        Class<?> holderClass = loader.loadClass("b.Holder");
        Object ctx = ctxClass.getConstructor(String.class).newInstance("from-ctx");
        Object holder = holderClass.getConstructor(ctxClass).newInstance(ctx);
        Class<?> user = loader.loadClass("a.User");

        Method run = user.getMethod("run", String.class, List.class, holderClass);
        assertEquals("from-ctx", run.invoke(null, "s", List.of(), holder));
        Method label = user.getMethod("label", ctxClass, String.class);
        assertEquals("from-ctx:x", label.invoke(null, ctx, "x"));
    }

    @Test
    void refusesWhenAMethodIsGone() throws Exception {
        Path mods = Files.createDirectories(temp.resolve("mods"));
        jar(mods.resolve("modb.jar"), Map.of("b/Api", emptyClass("b/Api"), "b/Ctx", ctx(), "b/Holder", holder()),
                toml("modb", "3.0", ""));
        jar(mods.resolve("moda.jar"), Map.of("a/User", user()), toml("moda", "1.0", """
                [[dependencies.moda]]
                    modId = "modb"
                    versionRange = "[1,2)"
                """));
        Linkwright.Analysis analysis = Linkwright.analyze(mods, List.of(), Map.of(), LinkwrightConfig.defaults(), Dist.CLIENT);
        PairReport report = analysis.result().reports().get(0);
        assertEquals(PairReport.Decision.REFUSED, report.decision);
        assertTrue(analysis.result().overrides().isEmpty());
        assertEquals(2, report.problems.size(), report.problems.toString());
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

    private static ClassWriter begin(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        return cw;
    }

    private static byte[] emptyClass(String name) {
        ClassWriter cw = begin(name);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static String check(String, List, Ctx)} returns ctx.name(); {@code static String tag(Ctx, String)} returns name + ":" + s. */
    private static byte[] api() {
        ClassWriter cw = begin("b/Api");
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "check", "(Ljava/lang/String;Ljava/util/List;Lb/Ctx;)Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, "b/Ctx", "name", "()Ljava/lang/String;", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "tag", "(Lb/Ctx;Ljava/lang/String;)Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, "b/Ctx", "name", "()Ljava/lang/String;", false);
        mv.visitLdcInsn(":");
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] ctx() {
        ClassWriter cw = begin("b/Ctx");
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

    private static byte[] holder() {
        ClassWriter cw = begin("b/Holder");
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "ctx", "Lb/Ctx;", null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(Lb/Ctx;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitFieldInsn(PUTFIELD, "b/Holder", "ctx", "Lb/Ctx;");
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        mv = cw.visitMethod(ACC_PUBLIC, "ctx", "()Lb/Ctx;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, "b/Holder", "ctx", "Lb/Ctx;");
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Compiled against modb 1.x: calls {@code Api.check(String, List)} and {@code Api.tag(String)}. */
    private static byte[] user() {
        ClassWriter cw = begin("a/User");
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "(Ljava/lang/String;Ljava/util/List;Lb/Holder;)Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, "b/Api", "check", "(Ljava/lang/String;Ljava/util/List;)Ljava/lang/String;", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "label", "(Lb/Ctx;Ljava/lang/String;)Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, "b/Api", "tag", "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}

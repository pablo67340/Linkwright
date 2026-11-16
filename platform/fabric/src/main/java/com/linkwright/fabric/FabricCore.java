package com.linkwright.fabric;

import com.linkwright.Linkwright;
import com.linkwright.LwLog;
import com.linkwright.link.Asm;
import com.linkwright.link.PatchSet;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Linkwright on Fabric, loaded beside Fabric Loader by {@link AgentBridge}. Hooks
 * {@code DependencyOverrides}, runs the analysis as the loader creates it, relaxes approved
 * rules as each mod is read, then patches mod classes as they load.
 */
public final class FabricCore implements ClassFileTransformer {
    private static final String HOOK = "com/linkwright/fabric/FabricHook";

    private volatile PatchSet patches;
    private volatile FabricPlatform platform;
    private boolean ran;

    public FabricCore() {
        try {
            LwLog.use(new FabricLog());
        } catch (Throwable t) {
            LwLog.debug("Fabric Loader's logger is unavailable: " + t);
        }
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> redefined, ProtectionDomain domain,
                            byte[] bytes) {
        if (className == null || bytes == null) {
            return null;
        }
        try {
            if (AgentBridge.OVERRIDES.equals(className)) {
                return hook(bytes);
            }
            PatchSet set = patches;
            return set == null || set.isEmpty() ? null : set.apply(className, bytes);
        } catch (Throwable t) {
            LwLog.error("Linkwright could not transform " + className, t);
            return null;
        }
    }

    /** @param overridesClass Fabric Loader's {@code DependencyOverrides} */
    public synchronized void prepare(Object overridesClass) {
        if (ran) {
            return;
        }
        ran = true;
        try {
            FabricPlatform fabric = FabricPlatform.fromLoader(((Class<?>) overridesClass).getClassLoader());
            Linkwright.Result result = Linkwright.run(fabric);
            patches = new PatchSet(result.patches);
            if (!result.overrides.isEmpty()) {
                fabric.relax(result.overrides);
                platform = fabric;
            }
        } catch (Throwable t) {
            LwLog.error("Linkwright could not finish; Fabric Loader will check mod versions as usual", t);
        }
    }

    public void apply(Object metadata) {
        FabricPlatform fabric = platform;
        if (fabric == null) {
            return;
        }
        try {
            fabric.relaxMod(metadata);
        } catch (Throwable t) {
            LwLog.error("Linkwright could not relax a rule; Fabric Loader will check it as usual", t);
        }
    }

    private static byte[] hook(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        final int[] hooks = new int[1];
        reader.accept(new ClassVisitor(Asm.API, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor method = super.visitMethod(access, name, desc, signature, exceptions);
                if ("<init>".equals(name) && "(Ljava/nio/file/Path;)V".equals(desc)) {
                    hooks[0]++;
                    return new MethodVisitor(Asm.API, method) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            super.visitLdcInsn(Type.getObjectType(AgentBridge.OVERRIDES));
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "prepare", "(Ljava/lang/Object;)V", false);
                        }
                    };
                }
                if ("apply".equals(name) && "(Lnet/fabricmc/loader/impl/metadata/LoaderModMetadata;)V".equals(desc)) {
                    hooks[0]++;
                    return new MethodVisitor(Asm.API, method) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "apply", "(Ljava/lang/Object;)V", false);
                        }
                    };
                }
                return method;
            }
        }, 0);
        if (hooks[0] != 2) {
            LwLog.warn("Linkwright does not recognise this Fabric Loader and will stay inactive");
            return null;
        }
        return writer.toByteArray();
    }
}

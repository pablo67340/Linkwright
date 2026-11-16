package com.linkwright.legacy;

import com.linkwright.LwLog;
import com.linkwright.link.PatchSet;
import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Hooks FML's mod sorting and then applies approved patches to classes as they load. */
public final class LinkwrightTransformer implements IClassTransformer {
    private static final String HOOK = "com/linkwright/legacy/LegacyHook";

    @Override
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        if ("cpw.mods.fml.common.Loader".equals(transformedName)
                || "net.minecraftforge.fml.common.Loader".equals(transformedName)) {
            return hookSort(transformedName, bytes);
        }
        PatchSet set = LegacyHook.patches;
        if (set == null || set.isEmpty()) {
            return bytes;
        }
        byte[] patched = set.apply(transformedName.replace('.', '/'), bytes);
        return patched != null ? patched : bytes;
    }

    private static byte[] hookSort(String loader, byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        final boolean[] hooked = new boolean[1];
        reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor method = super.visitMethod(access, name, desc, signature, exceptions);
                if (!"sortModList".equals(name) || !"()V".equals(desc)) {
                    return method;
                }
                hooked[0] = true;
                return new MethodVisitor(Opcodes.ASM5, method) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "beforeSort", "()V", false);
                    }
                };
            }
        }, 0);
        if (!hooked[0]) {
            LwLog.warn("Linkwright found no sortModList in " + loader + " and will stay inactive");
            return bytes;
        }
        return writer.toByteArray();
    }
}

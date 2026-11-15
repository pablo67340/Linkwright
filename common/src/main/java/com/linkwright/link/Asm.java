package com.linkwright.link;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridges the ASM versions Linkwright runs against: from the ASM 5 that legacy Forge ships to
 * the ASM 9 of modern loaders. The API level is read from whatever ASM is present at runtime,
 * since compile-time constants would be inlined from the version Linkwright was built against.
 */
public final class Asm {
    public static final int API = detectApi();

    private Asm() {
    }

    /** A snapshot of a method's instructions, safe to iterate while the list is changed. */
    public static List<AbstractInsnNode> instructions(InsnList list) {
        List<AbstractInsnNode> result = new ArrayList<>(list.size());
        for (AbstractInsnNode insn = list.getFirst(); insn != null; insn = insn.getNext()) {
            result.add(insn);
        }
        return result;
    }

    private static int detectApi() {
        for (int v = 10; v >= 5; v--) {
            try {
                return Opcodes.class.getField("ASM" + v).getInt(null);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return 5 << 16;
    }
}

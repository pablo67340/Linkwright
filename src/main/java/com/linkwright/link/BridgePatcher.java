package com.linkwright.link;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies {@link Bridge}s to a class. Extra arguments are pushed right before the call, so no
 * branch targets, locals or stack map frames change.
 */
public final class BridgePatcher {
    private BridgePatcher() {
    }

    /** Returns how many call sites were rewritten. */
    public static int apply(ClassNode node, List<Bridge> bridges) {
        int patched = 0;
        for (Bridge bridge : bridges) {
            if (!bridge.callerClass().equals(node.name)) {
                continue;
            }
            for (MethodNode method : node.methods) {
                if (method.name.equals(bridge.callerName()) && method.desc.equals(bridge.callerDesc())) {
                    patched += apply(method, bridge);
                }
            }
        }
        return patched;
    }

    static int apply(MethodNode method, Bridge bridge) {
        List<MethodInsnNode> sites = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() == bridge.opcode()
                    && call.owner.equals(bridge.owner()) && call.name.equals(bridge.name())
                    && call.desc.equals(bridge.oldDesc())) {
                sites.add(call);
            }
        }
        for (MethodInsnNode call : sites) {
            InsnList before = new InsnList();
            for (Bridge.ValueSource source : bridge.sources()) {
                before.add(new VarInsnNode(Opcodes.ALOAD, source.slot()));
                if (source.hasGetter()) {
                    before.add(new MethodInsnNode(source.getterItf() ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL,
                            source.getterOwner(), source.getterName(), source.getterDesc(), source.getterItf()));
                }
            }
            if (bridge.strategy() == Bridge.Strategy.SWAP_BEFORE_LAST) {
                before.add(new InsnNode(Opcodes.SWAP));
            }
            method.instructions.insertBefore(call, before);
            call.desc = bridge.newDesc();
            if (bridge.popSize() > 0) {
                method.instructions.insert(call, new InsnNode(bridge.popSize() == 2 ? Opcodes.POP2 : Opcodes.POP));
            }
        }
        if (!sites.isEmpty()) {
            method.maxStack += bridge.sources().size() + 1;
        }
        return sites.size();
    }
}

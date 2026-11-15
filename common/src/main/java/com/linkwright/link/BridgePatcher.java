package com.linkwright.link;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies {@link Patch}es to a class. Rewritten call sites move their arguments into fresh local
 * slots past every existing one and reload them in the new order, all in straight-line code, so
 * existing branch targets and stack map frames stay valid.
 */
public final class BridgePatcher {
    private BridgePatcher() {
    }

    /** Returns how many call sites, field accesses and methods were rewritten or added. */
    public static int apply(ClassNode node, List<? extends Patch> patches) {
        int patched = 0;
        for (Patch patch : patches) {
            if (!patch.targetClass().equals(node.name)) {
                continue;
            }
            if (patch instanceof Bridge) {
                Bridge bridge = (Bridge) patch;
                for (MethodNode method : node.methods) {
                    if (method.name.equals(bridge.callerName) && method.desc.equals(bridge.callerDesc)) {
                        patched += applyCall(method, bridge);
                    }
                }
            } else if (patch instanceof FieldBridge) {
                FieldBridge field = (FieldBridge) patch;
                for (MethodNode method : node.methods) {
                    if (method.name.equals(field.callerName) && method.desc.equals(field.callerDesc)) {
                        patched += applyField(method, field);
                    }
                }
            } else if (patch instanceof ImplBridge) {
                patched += addImplementation(node, (ImplBridge) patch);
            }
        }
        return patched;
    }

    /**
     * Patches raw class bytes, for hooks that see bytes rather than a tree (LaunchWrapper, Java
     * agents). Returns {@code null} when nothing changed. Frames are kept as they are, which the
     * straight-line patches allow; maxima are recomputed.
     */
    public static byte[] apply(byte[] bytes, List<? extends Patch> patches) {
        ClassNode node = new ClassNode();
        ClassReader reader = new ClassReader(bytes);
        reader.accept(node, 0);
        if (apply(node, patches) == 0) {
            return null;
        }
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    static int applyCall(MethodNode method, Bridge bridge) {
        List<MethodInsnNode> sites = new ArrayList<>();
        for (AbstractInsnNode insn : Asm.instructions(method.instructions)) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) insn;
                if (call.getOpcode() == bridge.opcode && call.owner.equals(bridge.owner) && call.name.equals(bridge.name)
                        && call.desc.equals(bridge.oldDesc)) {
                    sites.add(call);
                }
            }
        }
        if (sites.isEmpty()) {
            return 0;
        }
        Type[] oldArgs = Type.getArgumentTypes(bridge.oldDesc);
        Type newRet = Type.getReturnType(bridge.newDesc);
        int base = method.maxLocals;
        int[] slots = new int[oldArgs.length];
        int next = base;
        for (int i = 0; i < oldArgs.length; i++) {
            slots[i] = next;
            next += oldArgs[i].getSize();
        }
        int resultSlot = next;
        next += Math.max(newRet.getSize(), 0);

        for (MethodInsnNode call : sites) {
            InsnList before = new InsnList();
            for (int i = oldArgs.length - 1; i >= 0; i--) {
                before.add(new VarInsnNode(oldArgs[i].getOpcode(Opcodes.ISTORE), slots[i]));
            }
            for (Bridge.Arg arg : bridge.args) {
                if (arg instanceof Bridge.FromOld) {
                    Bridge.FromOld old = (Bridge.FromOld) arg;
                    old.conversion.emit(before, load(oldArgs[old.index], slots[old.index]));
                } else if (arg instanceof Bridge.FromScope) {
                    Bridge.ValueSource source = ((Bridge.FromScope) arg).source;
                    before.add(new VarInsnNode(Opcodes.ALOAD, source.slot));
                    if (source.hasGetter()) {
                        before.add(new MethodInsnNode(source.getterItf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL,
                                source.getterOwner, source.getterName, source.getterDesc, source.getterItf));
                    }
                }
            }
            method.instructions.insertBefore(call, before);
            call.desc = bridge.newDesc;

            InsnList after = new InsnList();
            if (bridge.popSize > 0) {
                after.add(new InsnNode(bridge.popSize == 2 ? Opcodes.POP2 : Opcodes.POP));
            } else if (!bridge.returnConversion.isIdentity()) {
                after.add(new VarInsnNode(newRet.getOpcode(Opcodes.ISTORE), resultSlot));
                bridge.returnConversion.emit(after, load(newRet, resultSlot));
            }
            if (after.size() > 0) {
                method.instructions.insert(call, after);
            }
        }
        method.maxLocals = Math.max(method.maxLocals, next);
        method.maxStack += 4 + 2 * Type.getArgumentTypes(bridge.newDesc).length;
        return sites.size();
    }

    static int applyField(MethodNode method, FieldBridge bridge) {
        List<FieldInsnNode> sites = new ArrayList<>();
        for (AbstractInsnNode insn : Asm.instructions(method.instructions)) {
            if (insn instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) insn;
                if (field.getOpcode() == bridge.opcode && field.owner.equals(bridge.owner) && field.name.equals(bridge.name)
                        && field.desc.equals(bridge.oldDesc)) {
                    sites.add(field);
                }
            }
        }
        boolean read = bridge.opcode == Opcodes.GETFIELD || bridge.opcode == Opcodes.GETSTATIC;
        Type stored = Type.getType(read ? bridge.newDesc : bridge.oldDesc);
        int slot = method.maxLocals;
        for (FieldInsnNode field : sites) {
            field.desc = bridge.newDesc;
            if (bridge.conversion.isIdentity()) {
                continue;
            }
            InsnList convert = new InsnList();
            convert.add(new VarInsnNode(stored.getOpcode(Opcodes.ISTORE), slot));
            bridge.conversion.emit(convert, load(stored, slot));
            if (read) {
                method.instructions.insert(field, convert);
            } else {
                method.instructions.insertBefore(field, convert);
            }
        }
        if (!sites.isEmpty()) {
            method.maxLocals = slot + 2;
            method.maxStack += 4;
        }
        return sites.size();
    }

    static int addImplementation(ClassNode node, ImplBridge impl) {
        for (MethodNode existing : node.methods) {
            if (existing.name.equals(impl.name) && existing.desc.equals(impl.desc)) {
                return 0;
            }
        }
        Type[] reqArgs = Type.getArgumentTypes(impl.desc);
        Type reqRet = Type.getReturnType(impl.desc);
        Type haveRet = Type.getReturnType(impl.delegateDesc);
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, impl.name, impl.desc, null, null);
        InsnList code = method.instructions;

        int[] slots = new int[reqArgs.length];
        int next = 1;
        for (int i = 0; i < reqArgs.length; i++) {
            slots[i] = next;
            next += reqArgs[i].getSize();
        }
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        int stack = 1;
        for (int i = 0; i < impl.paramConversions.size(); i++) {
            impl.paramConversions.get(i).emit(code, load(reqArgs[i], slots[i]));
            stack += 2;
        }
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, impl.delegateName, impl.delegateDesc, false));

        if (reqRet.getSort() == Type.VOID) {
            if (haveRet.getSize() > 0) {
                code.add(new InsnNode(haveRet.getSize() == 2 ? Opcodes.POP2 : Opcodes.POP));
            }
            code.add(new InsnNode(Opcodes.RETURN));
        } else {
            if (!impl.returnConversion.isIdentity()) {
                code.add(new VarInsnNode(haveRet.getOpcode(Opcodes.ISTORE), next));
                impl.returnConversion.emit(code, load(haveRet, next));
                next += haveRet.getSize();
            }
            code.add(new InsnNode(reqRet.getOpcode(Opcodes.IRETURN)));
        }
        method.maxLocals = next;
        method.maxStack = stack + 4;
        node.methods.add(method);
        return 1;
    }

    private static InsnList load(Type type, int slot) {
        InsnList list = new InsnList();
        list.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), slot));
        return list;
    }
}

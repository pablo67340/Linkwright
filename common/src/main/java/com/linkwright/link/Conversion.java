package com.linkwright.link;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a value of one type into another without losing information. Only conversions that
 * round-trip exactly are listed: a mod that changed a parameter from {@code long} to
 * {@code BlockPos} still means the same position either way.
 */
public abstract class Conversion {
    public abstract String label();

    public boolean isIdentity() {
        return false;
    }

    /** Emits code leaving the converted value on the stack; {@code load} pushes the original. */
    public abstract void emit(InsnList out, InsnList load);

    public static final Conversion IDENTITY = new Conversion() {
        @Override
        public String label() {
            return "as is";
        }

        @Override
        public boolean isIdentity() {
            return true;
        }

        @Override
        public void emit(InsnList out, InsnList load) {
            out.add(load);
        }
    };

    /** Returns a conversion from {@code from} to {@code to}, or {@code null} if none is safe. */
    public static Conversion find(Type from, Type to, Hierarchy hierarchy) {
        if (from.equals(to) || Boolean.TRUE.equals(hierarchy.isAssignable(from, to))) {
            return IDENTITY;
        }
        Conversion widening = widening(from, to);
        if (widening != null) {
            return widening;
        }
        Conversion boxing = boxing(from, to, hierarchy);
        if (boxing != null) {
            return boxing;
        }
        for (Call known : hierarchy.knownConversions()) {
            boolean fromFits = known.from.equals(from) || (known.from.getSort() == Type.OBJECT && from.getSort() == Type.OBJECT
                    && Boolean.TRUE.equals(hierarchy.isAssignable(from, known.from)));
            boolean toFits = known.to.equals(to) || (known.to.getSort() == Type.OBJECT && to.getSort() == Type.OBJECT
                    && Boolean.TRUE.equals(hierarchy.isAssignable(known.to, to)));
            if (fromFits && toFits) {
                return known;
            }
        }
        return null;
    }

    /** A conversion that is one call or one constructor. */
    static final class Call extends Conversion {
        final String label;
        final Type from;
        final Type to;
        final int opcode;
        final String owner;
        final String name;
        final String desc;

        Call(String label, Type from, Type to, int opcode, String owner, String name, String desc) {
            this.label = label;
            this.from = from;
            this.to = to;
            this.opcode = opcode;
            this.owner = owner;
            this.name = name;
            this.desc = desc;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public void emit(InsnList out, InsnList load) {
            if (opcode == Opcodes.INVOKESPECIAL) {
                out.add(new TypeInsnNode(Opcodes.NEW, owner));
                out.add(new InsnNode(Opcodes.DUP));
                out.add(load);
            } else {
                out.add(load);
            }
            out.add(new MethodInsnNode(opcode, owner, name, desc, false));
        }
    }

    /** The Minecraft conversions, resolved against the runtime names of the classes involved. */
    static List<Call> minecraftConversions(McNames names, Hierarchy hierarchy) {
        List<Call> calls = new ArrayList<>();
        Type string = Type.getType(String.class);
        if (names.blockPos != null) {
            Type pos = Type.getObjectType(names.blockPos);
            add(calls, hierarchy, names, "BlockPos.asLong()", pos, Type.LONG_TYPE, false, names.blockPos, "asLong", "()J");
            add(calls, hierarchy, names, "BlockPos.of(long)", Type.LONG_TYPE, pos, true, names.blockPos, "of",
                    "(J)" + pos.getDescriptor());
            if (names.vec3i != null) {
                Type vec = Type.getObjectType(names.vec3i);
                add(calls, hierarchy, names, "new BlockPos(Vec3i)", vec, pos, false, names.blockPos, "<init>",
                        "(" + vec.getDescriptor() + ")V");
            }
        }
        if (names.chunkPos != null) {
            Type chunk = Type.getObjectType(names.chunkPos);
            add(calls, hierarchy, names, "ChunkPos.toLong()", chunk, Type.LONG_TYPE, false, names.chunkPos, "toLong", "()J");
            add(calls, hierarchy, names, "new ChunkPos(long)", Type.LONG_TYPE, chunk, false, names.chunkPos, "<init>", "(J)V");
        }
        if (names.resourceLocation != null) {
            Type id = Type.getObjectType(names.resourceLocation);
            add(calls, hierarchy, names, "ResourceLocation.toString()", id, string, false, names.resourceLocation,
                    "toString", "()Ljava/lang/String;");
            // Public until 1.21, where ResourceLocation.parse took its place
            if (!add(calls, hierarchy, names, "new ResourceLocation(String)", string, id, false, names.resourceLocation,
                    "<init>", "(Ljava/lang/String;)V") && names.mojangMembers) {
                add(calls, hierarchy, names, "ResourceLocation.parse(String)", string, id, true, names.resourceLocation,
                        "parse", "(Ljava/lang/String;)" + id.getDescriptor());
            }
        }
        return Collections.unmodifiableList(calls);
    }

    /**
     * Adds the call if the method exists and is public. Constructors and {@code toString} have
     * the same name everywhere; other methods use their Mojang name where that is the runtime
     * name, and otherwise the one public method of that shape on the class.
     */
    private static boolean add(List<Call> calls, Hierarchy hierarchy, McNames names, String label, Type from, Type to,
                               boolean isStatic, String owner, String mojangName, String desc) {
        ClassInfo info = hierarchy.index().info(owner);
        if (info == null) {
            return false;
        }
        String name = null;
        boolean fixedName = mojangName.equals("<init>") || mojangName.equals("toString") || names.mojangMembers;
        for (ClassInfo.Member m : info.methods.values()) {
            if (!m.desc().equals(desc) || m.isStatic() != isStatic || (m.access() & Opcodes.ACC_PUBLIC) == 0) {
                continue;
            }
            if (fixedName) {
                if (m.name().equals(mojangName)) {
                    name = m.name();
                    break;
                }
            } else if (!m.name().startsWith("<")) {
                if (name != null) {
                    return false;
                }
                name = m.name();
            }
        }
        if (name == null) {
            return false;
        }
        int opcode = name.equals("<init>") ? Opcodes.INVOKESPECIAL : isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL;
        calls.add(new Call(label, from, to, opcode, owner, name, desc));
        return true;
    }

    /** One JVM instruction (or none) that widens a primitive without losing precision. */
    static final class Insn extends Conversion {
        final String label;
        final int opcode;

        Insn(String label, int opcode) {
            this.label = label;
            this.opcode = opcode;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public void emit(InsnList out, InsnList load) {
            out.add(load);
            if (opcode != Opcodes.NOP) {
                out.add(new InsnNode(opcode));
            }
        }
    }

    static Conversion widening(Type from, Type to) {
        int f = from.getSort();
        int t = to.getSort();
        boolean intLike = f == Type.BYTE || f == Type.SHORT || f == Type.CHAR || f == Type.INT;
        if (intLike) {
            if (t == Type.INT && f != Type.INT) {
                return new Insn(from.getClassName() + " to int", Opcodes.NOP);
            }
            if (t == Type.LONG) {
                return new Insn(from.getClassName() + " to long", Opcodes.I2L);
            }
            if (t == Type.DOUBLE) {
                return new Insn(from.getClassName() + " to double", Opcodes.I2D);
            }
            if (t == Type.SHORT && f == Type.BYTE) {
                return new Insn("byte to short", Opcodes.NOP);
            }
        }
        if (f == Type.FLOAT && t == Type.DOUBLE) {
            return new Insn("float to double", Opcodes.F2D);
        }
        return null;
    }

    private static final Map<Integer, String[]> WRAPPERS = new HashMap<>();

    static {
        WRAPPERS.put(Type.BOOLEAN, new String[]{"java/lang/Boolean", "booleanValue"});
        WRAPPERS.put(Type.BYTE, new String[]{"java/lang/Byte", "byteValue"});
        WRAPPERS.put(Type.CHAR, new String[]{"java/lang/Character", "charValue"});
        WRAPPERS.put(Type.SHORT, new String[]{"java/lang/Short", "shortValue"});
        WRAPPERS.put(Type.INT, new String[]{"java/lang/Integer", "intValue"});
        WRAPPERS.put(Type.LONG, new String[]{"java/lang/Long", "longValue"});
        WRAPPERS.put(Type.FLOAT, new String[]{"java/lang/Float", "floatValue"});
        WRAPPERS.put(Type.DOUBLE, new String[]{"java/lang/Double", "doubleValue"});
    }

    static Conversion boxing(Type from, Type to, Hierarchy hierarchy) {
        String[] wrapFrom = WRAPPERS.get(from.getSort());
        if (wrapFrom != null && to.getSort() == Type.OBJECT) {
            Type wrapper = Type.getObjectType(wrapFrom[0]);
            if (Boolean.TRUE.equals(hierarchy.isAssignable(wrapper, to))) {
                return new Call("boxed as " + simple(wrapFrom[0]), from, wrapper, Opcodes.INVOKESTATIC, wrapFrom[0],
                        "valueOf", "(" + from.getDescriptor() + ")" + wrapper.getDescriptor());
            }
        }
        String[] wrapTo = WRAPPERS.get(to.getSort());
        if (wrapTo != null && from.getSort() == Type.OBJECT && from.getInternalName().equals(wrapTo[0])) {
            return new Call("unboxed from " + simple(wrapTo[0]), from, to, Opcodes.INVOKEVIRTUAL, wrapTo[0],
                    wrapTo[1], "()" + to.getDescriptor());
        }
        return null;
    }

    private static String simple(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }
}

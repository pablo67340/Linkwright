package com.linkwright.link;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.List;
import java.util.Map;

/**
 * Turns a value of one type into another without losing information. Only conversions that
 * round-trip exactly are listed: a mod that changed a parameter from {@code long} to
 * {@code BlockPos} still means the same position either way.
 */
public interface Conversion {
    String label();

    default boolean isIdentity() {
        return false;
    }

    /** Emits code leaving the converted value on the stack; {@code load} pushes the original. */
    void emit(InsnList out, InsnList load);

    Conversion IDENTITY = new Conversion() {
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
    static Conversion find(Type from, Type to, Hierarchy hierarchy) {
        if (from.equals(to) || Boolean.TRUE.equals(hierarchy.isAssignable(from, to))) {
            return IDENTITY;
        }
        Conversion widening = Catalog.widening(from, to);
        if (widening != null) {
            return widening;
        }
        Conversion boxing = Catalog.boxing(from, to, hierarchy);
        if (boxing != null) {
            return boxing;
        }
        for (Catalog.Known known : Catalog.KNOWN) {
            boolean fromFits = known.from.equals(from) || (known.from.getSort() == Type.OBJECT && from.getSort() == Type.OBJECT
                    && Boolean.TRUE.equals(hierarchy.isAssignable(from, known.from)));
            boolean toFits = known.to.equals(to) || (known.to.getSort() == Type.OBJECT && to.getSort() == Type.OBJECT
                    && Boolean.TRUE.equals(hierarchy.isAssignable(known.to, to)));
            if (fromFits && toFits && known.available(hierarchy)) {
                return known;
            }
        }
        return null;
    }

    final class Catalog {
        private Catalog() {
        }

        private static final Type BLOCK_POS = Type.getObjectType("net/minecraft/core/BlockPos");
        private static final Type CHUNK_POS = Type.getObjectType("net/minecraft/world/level/ChunkPos");
        private static final Type RESOURCE_LOCATION = Type.getObjectType("net/minecraft/resources/ResourceLocation");
        private static final Type VEC3I = Type.getObjectType("net/minecraft/core/Vec3i");
        private static final Type STRING = Type.getType(String.class);

        /** A conversion that is one call or one constructor. */
        record Known(String label, Type from, Type to, int opcode, String owner, String name, String desc) implements Conversion {
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

            boolean available(Hierarchy hierarchy) {
                return hierarchy.resolveMethod(owner, name, desc) != Hierarchy.Resolution.MISSING;
            }
        }

        static final List<Known> KNOWN = List.of(
                new Known("BlockPos.asLong()", BLOCK_POS, Type.LONG_TYPE, Opcodes.INVOKEVIRTUAL,
                        BLOCK_POS.getInternalName(), "asLong", "()J"),
                new Known("BlockPos.of(long)", Type.LONG_TYPE, BLOCK_POS, Opcodes.INVOKESTATIC,
                        BLOCK_POS.getInternalName(), "of", "(J)Lnet/minecraft/core/BlockPos;"),
                new Known("ChunkPos.toLong()", CHUNK_POS, Type.LONG_TYPE, Opcodes.INVOKEVIRTUAL,
                        CHUNK_POS.getInternalName(), "toLong", "()J"),
                new Known("new ChunkPos(long)", Type.LONG_TYPE, CHUNK_POS, Opcodes.INVOKESPECIAL,
                        CHUNK_POS.getInternalName(), "<init>", "(J)V"),
                new Known("ResourceLocation.toString()", RESOURCE_LOCATION, STRING, Opcodes.INVOKEVIRTUAL,
                        RESOURCE_LOCATION.getInternalName(), "toString", "()Ljava/lang/String;"),
                new Known("ResourceLocation.parse(String)", STRING, RESOURCE_LOCATION, Opcodes.INVOKESTATIC,
                        RESOURCE_LOCATION.getInternalName(), "parse", "(Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;"),
                new Known("new BlockPos(Vec3i)", VEC3I, BLOCK_POS, Opcodes.INVOKESPECIAL,
                        BLOCK_POS.getInternalName(), "<init>", "(Lnet/minecraft/core/Vec3i;)V"));

        /** One JVM instruction (or none) that widens a primitive without losing precision. */
        record Insn(String label, int opcode) implements Conversion {
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

        private static final Map<Integer, String[]> WRAPPERS = Map.of(
                Type.BOOLEAN, new String[]{"java/lang/Boolean", "booleanValue"},
                Type.BYTE, new String[]{"java/lang/Byte", "byteValue"},
                Type.CHAR, new String[]{"java/lang/Character", "charValue"},
                Type.SHORT, new String[]{"java/lang/Short", "shortValue"},
                Type.INT, new String[]{"java/lang/Integer", "intValue"},
                Type.LONG, new String[]{"java/lang/Long", "longValue"},
                Type.FLOAT, new String[]{"java/lang/Float", "floatValue"},
                Type.DOUBLE, new String[]{"java/lang/Double", "doubleValue"});

        static Conversion boxing(Type from, Type to, Hierarchy hierarchy) {
            String[] wrapFrom = WRAPPERS.get(from.getSort());
            if (wrapFrom != null && to.getSort() == Type.OBJECT) {
                Type wrapper = Type.getObjectType(wrapFrom[0]);
                if (Boolean.TRUE.equals(hierarchy.isAssignable(wrapper, to))) {
                    return new Known("boxed as " + simple(wrapFrom[0]), from, wrapper, Opcodes.INVOKESTATIC, wrapFrom[0],
                            "valueOf", "(" + from.getDescriptor() + ")" + wrapper.getDescriptor());
                }
            }
            String[] wrapTo = WRAPPERS.get(to.getSort());
            if (wrapTo != null && from.getSort() == Type.OBJECT && from.getInternalName().equals(wrapTo[0])) {
                return new Known("unboxed from " + simple(wrapTo[0]), from, to, Opcodes.INVOKEVIRTUAL, wrapTo[0],
                        wrapTo[1], "()" + to.getDescriptor());
            }
            return null;
        }

        private static String simple(String internalName) {
            return internalName.substring(internalName.lastIndexOf('/') + 1);
        }
    }
}

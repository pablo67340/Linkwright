package com.linkwright.link;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SimpleVerifier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Proves, before a pair is approved, that its patches leave valid bytecode. Each patched class is
 * patched in memory exactly as it will be at load time, written out and read back. Every method
 * then goes through ASM's data-flow verifier, with types resolved against the pack's own classes,
 * and every stack map frame is checked against the types the verifier computed, as the JVM does.
 *
 * <p>The verifier is stricter than the JVM in a few places it cannot see into (classes nobody
 * indexed, for one), so the rule is relative: a patch may not make any method fail that passed
 * before it.
 */
final class PatchVerifier {
    static final class Failure extends Exception {
        Failure(String message) {
            super(message);
        }
    }

    private static final Type OBJECT = Type.getObjectType("java/lang/Object");

    private final Hierarchy hierarchy;

    PatchVerifier(Hierarchy hierarchy) {
        this.hierarchy = hierarchy;
    }

    /** Patches {@code original} and returns the result as the JVM would load it, or says why it would not verify. */
    ClassNode patch(byte[] original, List<Patch> patches) throws Failure {
        Map<Integer, String> before = analyze(read(original));

        ClassNode node = new ClassNode();
        new ClassReader(original).accept(node, 0);
        int applied;
        try {
            applied = BridgePatcher.apply(node, patches);
        } catch (RuntimeException e) {
            throw new Failure("patching it failed: " + e);
        }
        if (applied == 0) {
            throw new Failure("its patches found nothing to change");
        }
        byte[] written;
        try {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            written = writer.toByteArray();
        } catch (RuntimeException e) {
            throw new Failure("the patched class could not be written: " + e);
        }
        ClassNode after = read(written);
        for (Map.Entry<Integer, String> failure : analyze(after).entrySet()) {
            if (!before.containsKey(failure.getKey())) {
                throw new Failure(failure.getValue());
            }
        }
        return after;
    }

    private static ClassNode read(byte[] bytes) throws Failure {
        ClassNode node = new ClassNode();
        try {
            new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        } catch (RuntimeException e) {
            throw new Failure("the class could not be read: " + e);
        }
        return node;
    }

    /** Verification failures by method position; patches only ever append methods. */
    private Map<Integer, String> analyze(ClassNode node) {
        Map<Integer, String> failures = new LinkedHashMap<>();
        Verifier verifier = new Verifier(node);
        for (int i = 0; i < node.methods.size(); i++) {
            MethodNode method = node.methods.get(i);
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || method.instructions.size() == 0) {
                continue;
            }
            String where = node.name.replace('/', '.') + "." + method.name + method.desc;
            Frame<BasicValue>[] frames;
            try {
                frames = new Analyzer<>(verifier).analyze(node.name, method);
            } catch (Exception | LinkageError e) {
                failures.put(i, where + " does not verify: " + e.getMessage());
                continue;
            }
            String frameProblem = checkFrames(method, frames, verifier);
            if (frameProblem != null) {
                failures.put(i, where + " has a stack map frame that no longer matches: " + frameProblem);
            }
        }
        return failures;
    }

    private static String checkFrames(MethodNode method, Frame<BasicValue>[] frames, Verifier verifier) {
        for (int i = 0; i < method.instructions.size(); i++) {
            AbstractInsnNode insn = method.instructions.get(i);
            if (!(insn instanceof FrameNode) || frames[i] == null) {
                continue;
            }
            FrameNode declared = (FrameNode) insn;
            Frame<BasicValue> computed = frames[i];
            int slot = 0;
            if (declared.local != null) {
                for (Object type : declared.local) {
                    if (slot >= computed.getLocals()) {
                        return "it declares more locals than the method has";
                    }
                    if (!fits(type, computed.getLocal(slot), verifier)) {
                        return "local " + slot + " is " + describe(computed.getLocal(slot)) + ", the frame says " + describe(type);
                    }
                    slot += Opcodes.LONG.equals(type) || Opcodes.DOUBLE.equals(type) ? 2 : 1;
                }
            }
            int height = declared.stack == null ? 0 : declared.stack.size();
            if (height != computed.getStackSize()) {
                return "the stack holds " + computed.getStackSize() + " values, the frame says " + height;
            }
            for (int j = 0; j < height; j++) {
                if (!fits(declared.stack.get(j), computed.getStack(j), verifier)) {
                    return "stack entry " + j + " is " + describe(computed.getStack(j)) + ", the frame says "
                            + describe(declared.stack.get(j));
                }
            }
        }
        return null;
    }

    private static boolean fits(Object declared, BasicValue value, Verifier verifier) {
        Type type = value == null ? null : value.getType();
        if (Opcodes.TOP.equals(declared) || Opcodes.UNINITIALIZED_THIS.equals(declared) || declared instanceof LabelNode) {
            return true;
        }
        if (Opcodes.INTEGER.equals(declared)) {
            return Type.INT_TYPE.equals(type);
        }
        if (Opcodes.FLOAT.equals(declared)) {
            return Type.FLOAT_TYPE.equals(type);
        }
        if (Opcodes.LONG.equals(declared)) {
            return Type.LONG_TYPE.equals(type);
        }
        if (Opcodes.DOUBLE.equals(declared)) {
            return Type.DOUBLE_TYPE.equals(type);
        }
        if (type == null || (type.getSort() != Type.OBJECT && type.getSort() != Type.ARRAY)) {
            return false;
        }
        if (Opcodes.NULL.equals(declared)) {
            return isNull(type);
        }
        if (declared instanceof String) {
            String name = (String) declared;
            return verifier.assignable(name.startsWith("[") ? Type.getType(name) : Type.getObjectType(name), type);
        }
        return false;
    }

    private static boolean isNull(Type type) {
        return type.getSort() == Type.OBJECT && "null".equals(type.getInternalName());
    }

    private static String describe(Object frameType) {
        if (frameType instanceof BasicValue) {
            Type t = ((BasicValue) frameType).getType();
            return t == null ? "unset" : t.getClassName();
        }
        if (frameType instanceof String) {
            return ((String) frameType).replace('/', '.');
        }
        if (frameType instanceof Integer) {
            switch ((Integer) frameType) {
                case 1:
                    return "int";
                case 2:
                    return "float";
                case 3:
                    return "double";
                case 4:
                    return "long";
                case 5:
                    return "null";
                default:
                    return "uninitialized";
            }
        }
        return String.valueOf(frameType);
    }

    /**
     * ASM's verifier with class relationships taken from the class index rather than loaded
     * classes, so nothing is initialized or even loaded by the check. Like the JVM's own
     * verifier, any object may stand in for an interface; types nobody indexed are given the
     * benefit of the doubt.
     */
    private final class Verifier extends SimpleVerifier {
        private final ClassNode current;

        Verifier(ClassNode current) {
            super(Asm.API, Type.getObjectType(current.name),
                    current.superName == null ? null : Type.getObjectType(current.superName),
                    interfaces(current), (current.access & Opcodes.ACC_INTERFACE) != 0);
            this.current = current;
        }

        boolean assignable(Type expected, Type actual) {
            return isAssignableFrom(expected, actual);
        }

        @Override
        protected boolean isInterface(Type type) {
            if (type.getSort() != Type.OBJECT) {
                return false;
            }
            String name = type.getInternalName();
            if (name.equals(current.name)) {
                return (current.access & Opcodes.ACC_INTERFACE) != 0;
            }
            ClassInfo info = hierarchy.index().info(name);
            if (info != null) {
                return info.isInterface();
            }
            Class<?> jdk = Hierarchy.jdkClass(name);
            return jdk != null && jdk.isInterface();
        }

        @Override
        protected Type getSuperClass(Type type) {
            if (type.getSort() != Type.OBJECT || type.equals(OBJECT)) {
                return null;
            }
            String name = type.getInternalName();
            String sup;
            if (name.equals(current.name)) {
                sup = current.superName;
            } else {
                ClassInfo info = hierarchy.index().info(name);
                if (info != null) {
                    sup = info.superName;
                } else {
                    Class<?> jdk = Hierarchy.jdkClass(name);
                    sup = jdk == null ? OBJECT.getInternalName()
                            : jdk.getSuperclass() == null ? null : Type.getInternalName(jdk.getSuperclass());
                }
            }
            return sup == null ? null : Type.getObjectType(sup);
        }

        @Override
        protected boolean isAssignableFrom(Type expected, Type actual) {
            if (expected.equals(actual)) {
                return true;
            }
            boolean expectedRef = expected.getSort() == Type.OBJECT || expected.getSort() == Type.ARRAY;
            boolean actualRef = actual.getSort() == Type.OBJECT || actual.getSort() == Type.ARRAY;
            if (!expectedRef || !actualRef) {
                return false;
            }
            if (isNull(actual) || expected.equals(OBJECT)) {
                return true;
            }
            if (expected.getSort() == Type.ARRAY) {
                if (actual.getSort() != Type.ARRAY) {
                    return false;
                }
                Type e = Type.getType(expected.getDescriptor().substring(1));
                Type a = Type.getType(actual.getDescriptor().substring(1));
                boolean eRef = e.getSort() == Type.OBJECT || e.getSort() == Type.ARRAY;
                boolean aRef = a.getSort() == Type.OBJECT || a.getSort() == Type.ARRAY;
                return eRef && aRef ? isAssignableFrom(e, a) : e.equals(a);
            }
            if (actual.getSort() == Type.ARRAY) {
                String name = expected.getInternalName();
                return name.equals("java/lang/Cloneable") || name.equals("java/io/Serializable");
            }
            if (isInterface(expected)) {
                return true;
            }
            return isSubclass(actual.getInternalName(), expected.getInternalName());
        }

        @Override
        protected Class<?> getClass(Type type) {
            // Only reached from ASM's defaults, which are all overridden; never load anything
            return Object.class;
        }

        private boolean isSubclass(String sub, String sup) {
            Deque<String> queue = new ArrayDeque<>();
            Set<String> seen = new HashSet<>();
            queue.add(sub);
            boolean unknown = false;
            while (!queue.isEmpty()) {
                String cls = queue.poll();
                if (cls.equals(sup)) {
                    return true;
                }
                if (!seen.add(cls)) {
                    continue;
                }
                if (cls.equals(current.name)) {
                    if (current.superName != null) {
                        queue.add(current.superName);
                    }
                    queue.addAll(current.interfaces);
                    continue;
                }
                ClassInfo info = hierarchy.index().info(cls);
                if (info != null) {
                    queue.addAll(info.supertypes());
                    continue;
                }
                Class<?> jdk = Hierarchy.jdkClass(cls);
                Class<?> jdkSup = Hierarchy.jdkClass(sup);
                if (jdk != null) {
                    if (jdkSup != null && jdkSup.isAssignableFrom(jdk)) {
                        return true;
                    }
                } else {
                    unknown = true;
                }
            }
            return unknown;
        }
    }

    private static List<Type> interfaces(ClassNode node) {
        List<Type> types = new ArrayList<>();
        for (String name : node.interfaces) {
            types.add(Type.getObjectType(name));
        }
        return types;
    }
}

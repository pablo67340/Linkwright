package com.linkwright.link;

import com.linkwright.scan.ModEntry;
import com.linkwright.scan.ModJar;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks that every reference from one mod's bytecode into another mod still resolves, and
 * plans patches for method calls whose target changed signature.
 */
public final class LinkChecker {
    /** Package roots that belong to the game, the loader or shared libraries, never to one mod. */
    private static final List<String> SHARED_ROOTS = List.of("net/minecraft/", "com/mojang/", "net/neoforged/",
            "net/minecraftforge/", "org/spongepowered/", "com/llamalad7/", "java/", "javax/", "kotlin/", "org/",
            "com/google/", "io/netty/", "it/unimi/");

    private final ClassIndex index;
    private final Hierarchy hierarchy;
    private final BridgeFinder bridgeFinder;

    public LinkChecker(Hierarchy hierarchy) {
        this.hierarchy = hierarchy;
        this.index = hierarchy.index();
        this.bridgeFinder = new BridgeFinder(hierarchy);
    }

    /** Mutable state for checking one pair. */
    final class Pass {
        final Set<String> dependentClasses;
        final Set<String> dependencyClasses;
        final Set<String> dependencyRoots;
        final Map<String, Problem> problems = new LinkedHashMap<>();
        final Map<String, Patch> patches = new LinkedHashMap<>();
        int checked;
        int unverifiable;

        Pass(Set<String> dependentClasses, Set<String> dependencyClasses) {
            this.dependentClasses = dependentClasses;
            this.dependencyClasses = dependencyClasses;
            this.dependencyRoots = roots(dependencyClasses);
            this.dependencyRoots.removeAll(roots(dependentClasses));
        }

        /** Whether this class belongs to the dependency, including classes it used to have. */
        boolean inDependency(String internalName) {
            if (internalName == null || internalName.startsWith("[")) {
                return false;
            }
            if (dependencyClasses.contains(internalName)) {
                return true;
            }
            if (index.contains(internalName) || dependentClasses.contains(internalName)) {
                return false;
            }
            for (String root : dependencyRoots) {
                if (internalName.startsWith(root)) {
                    return true;
                }
            }
            return false;
        }

        void problem(Problem.Kind kind, String subject, String where, String detail) {
            Problem fresh = new Problem(kind, subject, where, detail);
            Problem existing = problems.putIfAbsent(fresh.key(), fresh);
            if (existing != null) {
                existing.seenAgain();
            }
        }

        void resolution(Hierarchy.Resolution resolution) {
            checked++;
            if (resolution == Hierarchy.Resolution.UNKNOWN) {
                unverifiable++;
            }
        }
    }

    public PairReport check(ModEntry dependent, ModEntry dependency, String requiredRange, boolean alreadyOverridden)
            throws IOException {
        Set<String> dependencyClasses = classesIn(dependency.jar());
        Set<String> dependentClasses = classesIn(dependent.jar());
        dependentClasses.removeAll(dependencyClasses);
        Pass pass = new Pass(dependentClasses, dependencyClasses);

        for (String name : dependentClasses) {
            byte[] bytes = dependent.jar().read(name + ".class");
            if (bytes == null) {
                continue;
            }
            ClassNode node = new ClassNode();
            try {
                new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
            } catch (RuntimeException e) {
                continue;
            }
            scanClass(node, pass);
        }
        return new PairReport(dependent, dependency, requiredRange, alreadyOverridden,
                new ArrayList<>(pass.problems.values()), new ArrayList<>(pass.patches.values()),
                pass.checked, pass.unverifiable);
    }

    private void scanClass(ClassNode node, Pass pass) {
        boolean mixin = MixinChecker.isMixin(node);
        String where = node.name.replace('/', '.');
        classRef(node.superName, where, pass);
        for (String iface : node.interfaces) {
            classRef(iface, where, pass);
        }
        for (FieldNode field : node.fields) {
            typeRefs(Type.getType(field.desc), where, pass);
        }
        for (MethodNode method : node.methods) {
            String methodWhere = where + "." + method.name;
            for (Type t : Type.getArgumentTypes(method.desc)) {
                typeRefs(t, methodWhere, pass);
            }
            typeRefs(Type.getReturnType(method.desc), methodWhere, pass);
            for (AbstractInsnNode insn : method.instructions) {
                scanInsn(node, method, insn, methodWhere, mixin, pass);
            }
        }
        if (mixin) {
            new MixinChecker(hierarchy).check(node, pass);
        } else {
            checkAbstracts(node, pass);
        }
    }

    private void scanInsn(ClassNode node, MethodNode method, AbstractInsnNode insn, String where, boolean mixin, Pass pass) {
        if (insn instanceof MethodInsnNode call) {
            methodRef(call.owner, call.name, call.desc, where, pass, mixin ? null : node, method, call);
        } else if (insn instanceof FieldInsnNode field) {
            fieldRef(field.owner, field.name, field.desc, where, pass, mixin ? null : node, method, field);
        } else if (insn instanceof TypeInsnNode type) {
            typeRefs(Type.getObjectType(type.desc), where, pass);
        } else if (insn instanceof MultiANewArrayInsnNode array) {
            typeRefs(Type.getType(array.desc), where, pass);
        } else if (insn instanceof LdcInsnNode ldc) {
            constantRef(ldc.cst, where, pass);
        } else if (insn instanceof InvokeDynamicInsnNode indy) {
            constantRef(indy.bsm, where, pass);
            for (Object arg : indy.bsmArgs) {
                constantRef(arg, where, pass);
            }
        }
    }

    private void constantRef(Object constant, String where, Pass pass) {
        if (constant instanceof Type type) {
            if (type.getSort() == Type.METHOD) {
                for (Type t : type.getArgumentTypes()) {
                    typeRefs(t, where, pass);
                }
                typeRefs(type.getReturnType(), where, pass);
            } else {
                typeRefs(type, where, pass);
            }
        } else if (constant instanceof Handle handle) {
            if (handle.getTag() <= Opcodes.H_PUTSTATIC) {
                fieldRef(handle.getOwner(), handle.getName(), handle.getDesc(), where, pass, null, null, null);
            } else {
                methodRef(handle.getOwner(), handle.getName(), handle.getDesc(), where, pass, null, null, null);
            }
        }
    }

    void typeRefs(Type type, String where, Pass pass) {
        if (type.getSort() == Type.ARRAY) {
            type = type.getElementType();
        }
        if (type.getSort() == Type.OBJECT) {
            classRef(type.getInternalName(), where, pass);
        }
    }

    void classRef(String internalName, String where, Pass pass) {
        if (internalName == null || !pass.inDependency(internalName)) {
            return;
        }
        pass.checked++;
        if (!index.contains(internalName)) {
            pass.problem(Problem.Kind.MISSING_CLASS, internalName.replace('/', '.'), where, null);
        }
    }

    void methodRef(String owner, String name, String desc, String where, Pass pass,
                   ClassNode caller, MethodNode method, MethodInsnNode call) {
        for (Type t : Type.getArgumentTypes(desc)) {
            typeRefs(t, where, pass);
        }
        typeRefs(Type.getReturnType(desc), where, pass);
        if (!pass.inDependency(owner)) {
            return;
        }
        if (!index.contains(owner)) {
            classRef(owner, where, pass);
            return;
        }
        Hierarchy.Resolution resolution = hierarchy.resolveMethod(owner, name, desc);
        pass.resolution(resolution);
        if (resolution != Hierarchy.Resolution.MISSING) {
            return;
        }
        String subject = owner.replace('/', '.') + "." + name + desc;
        if (caller == null) {
            pass.problem(Problem.Kind.MISSING_METHOD, subject, where,
                    call == null ? "referenced as a method handle, which cannot be bridged" : "called from a mixin, which cannot be bridged");
            return;
        }
        BridgeFinder.Outcome outcome = bridgeFinder.findCall(caller, method, call);
        if (outcome instanceof BridgeFinder.Found found) {
            pass.patches.putIfAbsent(caller.name + "." + method.name + method.desc + "|" + owner + "." + name + desc,
                    found.patch());
        } else {
            pass.problem(Problem.Kind.MISSING_METHOD, subject, where, ((BridgeFinder.NotFound) outcome).reason());
        }
    }

    void fieldRef(String owner, String name, String desc, String where, Pass pass,
                  ClassNode caller, MethodNode method, FieldInsnNode access) {
        typeRefs(Type.getType(desc), where, pass);
        if (!pass.inDependency(owner)) {
            return;
        }
        if (!index.contains(owner)) {
            classRef(owner, where, pass);
            return;
        }
        Hierarchy.Resolution resolution = hierarchy.resolveField(owner, name, desc);
        pass.resolution(resolution);
        if (resolution != Hierarchy.Resolution.MISSING) {
            return;
        }
        String subject = owner.replace('/', '.') + "." + name + ":" + desc;
        if (caller == null) {
            pass.problem(Problem.Kind.MISSING_FIELD, subject, where,
                    access == null ? "referenced as a method handle, which cannot be bridged" : "used from a mixin, which cannot be bridged");
            return;
        }
        BridgeFinder.Outcome outcome = bridgeFinder.findField(caller, method, access);
        if (outcome instanceof BridgeFinder.Found found) {
            pass.patches.putIfAbsent(caller.name + "." + method.name + method.desc + "|" + access.getOpcode() + "|" + subject,
                    found.patch());
        } else {
            pass.problem(Problem.Kind.MISSING_FIELD, subject, where, ((BridgeFinder.NotFound) outcome).reason());
        }
    }

    /** A concrete class that extends the dependency must still implement everything it declares abstract. */
    private void checkAbstracts(ClassNode node, Pass pass) {
        if ((node.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0) {
            return;
        }
        List<ClassInfo> ancestors = hierarchy.ancestors(node.name);
        boolean extendsDependency = false;
        for (ClassInfo ancestor : ancestors) {
            if (pass.dependencyClasses.contains(ancestor.name)) {
                extendsDependency = true;
                break;
            }
        }
        if (!extendsDependency || hasUnindexedAncestor(ancestors)) {
            return;
        }
        Set<String> concrete = new HashSet<>();
        for (ClassInfo ancestor : ancestors) {
            for (ClassInfo.Member m : ancestor.methods.values()) {
                if (!m.isAbstract()) {
                    concrete.add(m.name() + m.desc());
                }
            }
        }
        for (ClassInfo ancestor : ancestors) {
            if (!pass.dependencyClasses.contains(ancestor.name)) {
                continue;
            }
            for (ClassInfo.Member m : ancestor.methods.values()) {
                if (m.isAbstract() && !m.isStatic() && !concrete.contains(m.name() + m.desc())) {
                    BridgeFinder.Outcome outcome = bridgeFinder.findImplementation(node, m);
                    if (outcome instanceof BridgeFinder.Found found) {
                        pass.patches.putIfAbsent(node.name + "|impl|" + m.name() + m.desc(), found.patch());
                        concrete.add(m.name() + m.desc());
                    } else {
                        pass.problem(Problem.Kind.UNIMPLEMENTED, ancestor.name.replace('/', '.') + "." + m.name() + m.desc(),
                                node.name.replace('/', '.'), ((BridgeFinder.NotFound) outcome).reason());
                    }
                }
            }
        }
    }

    /** Minecraft or another unindexed supertype could be supplying the implementation. */
    private boolean hasUnindexedAncestor(List<ClassInfo> ancestors) {
        for (ClassInfo ancestor : ancestors) {
            for (String sup : ancestor.supertypes()) {
                if (!index.contains(sup) && !Hierarchy.isJdkName(sup)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<String> classesIn(ModJar jar) throws IOException {
        Set<String> classes = new HashSet<>();
        for (String entry : jar.entryNames()) {
            if (entry.endsWith(".class") && !entry.startsWith("META-INF/") && !entry.endsWith("module-info.class")) {
                classes.add(entry.substring(0, entry.length() - 6));
            }
        }
        return classes;
    }

    /** The first three package segments of each class, minus anything shared by the whole ecosystem. */
    static Set<String> roots(Set<String> classes) {
        Set<String> roots = new HashSet<>();
        outer:
        for (String cls : classes) {
            String[] parts = cls.split("/");
            if (parts.length < 4) {
                continue;
            }
            String root = parts[0] + "/" + parts[1] + "/" + parts[2] + "/";
            for (String shared : SHARED_ROOTS) {
                if (root.startsWith(shared)) {
                    continue outer;
                }
            }
            roots.add(root);
        }
        return roots;
    }
}

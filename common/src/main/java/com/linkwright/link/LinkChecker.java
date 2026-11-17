package com.linkwright.link;

import com.linkwright.LwLog;
import com.linkwright.scan.ModEntry;
import com.linkwright.scan.ModJar;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.SimpleRemapper;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
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
    private static final List<String> SHARED_ROOTS = Arrays.asList("net/minecraft/", "com/mojang/", "net/neoforged/",
            "net/minecraftforge/", "cpw/mods/", "net/fabricmc/", "org/spongepowered/", "com/llamalad7/", "java/",
            "javax/", "kotlin/", "org/", "com/google/", "io/netty/", "it/unimi/");

    /** Patched pairs are only ever approved once {@link PatchVerifier} has run, which needs ASM's analysis package. */
    private static final boolean VERIFIER_AVAILABLE = verifierAvailable();

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
        /** For the loader, its exact packages; other jars of the loader share its package roots. */
        final Set<String> dependencyPackages;
        final Map<String, Problem> problems = new LinkedHashMap<>();
        final Map<String, Patch> patches = new LinkedHashMap<>();
        int checked;
        int unverifiable;

        Pass(Set<String> dependentClasses, Set<String> dependencyClasses, boolean loader) {
            this.dependentClasses = dependentClasses;
            this.dependencyClasses = dependencyClasses;
            if (loader) {
                this.dependencyRoots = new HashSet<>();
                this.dependencyPackages = new HashSet<>();
                for (String cls : dependencyClasses) {
                    dependencyPackages.add(packageOf(cls));
                }
            } else {
                this.dependencyRoots = roots(dependencyClasses);
                this.dependencyRoots.removeAll(roots(dependentClasses));
                this.dependencyPackages = null;
            }
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
            if (dependencyPackages != null) {
                return dependencyPackages.contains(packageOf(internalName));
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
            Problem existing = problems.get(fresh.key());
            if (existing != null) {
                existing.seenAgain();
            } else {
                problems.put(fresh.key(), fresh);
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
        if (dependency.isLoader()) {
            dependencyClasses.removeIf(cls -> !startsWithAny(cls, dependency.packages()));
        }
        Set<String> dependentClasses = classesIn(dependent.jar());
        dependentClasses.removeAll(dependencyClasses);
        Pass pass = scan(dependent, dependentClasses, dependencyClasses, dependency.isLoader(), Collections.<String, String>emptyMap());
        Map<String, String> moves = findMoves(pass, dependent, dependentClasses, dependencyClasses);
        if (!moves.isEmpty()) {
            pass = scan(dependent, dependentClasses, dependencyClasses, dependency.isLoader(), moves);
        }
        if (pass.problems.isEmpty() && !pass.patches.isEmpty()) {
            verify(pass, dependent, dependency.isLoader());
        }
        return new PairReport(dependent, dependency, requiredRange, alreadyOverridden,
                new ArrayList<>(pass.problems.values()), new ArrayList<>(pass.patches.values()),
                pass.checked, pass.unverifiable);
    }

    /** Checks every class of the dependent, as it will look once {@code moves} are applied. */
    private Pass scan(ModEntry dependent, Set<String> dependentClasses, Set<String> dependencyClasses, boolean loader,
                      Map<String, String> moves) throws IOException {
        Pass pass = new Pass(dependentClasses, dependencyClasses, loader);
        for (String name : dependentClasses) {
            ClassNode node = read(dependent.jar(), name, ClassReader.SKIP_FRAMES);
            if (node == null) {
                continue;
            }
            if (!moves.isEmpty() && !MixinChecker.isMixin(node)) {
                MoveRemapper remapper = new MoveRemapper(moves);
                ClassNode remapped = new ClassNode();
                node.accept(Asm.remapping(remapped, remapper));
                if (!remapper.hits.isEmpty()) {
                    Map<String, String> used = new LinkedHashMap<>();
                    for (String old : remapper.hits) {
                        used.put(old, moves.get(old));
                    }
                    addPatch(pass, name + "|move", new ClassMove(name, used));
                    node = remapped;
                }
            }
            scanClass(node, pass);
        }
        return pass;
    }

    private static ClassNode read(ModJar jar, String name, int flags) throws IOException {
        byte[] bytes = jar.read(name + ".class");
        if (bytes == null) {
            return null;
        }
        ClassNode node = new ClassNode();
        try {
            new ClassReader(bytes).accept(node, flags);
        } catch (RuntimeException e) {
            return null;
        }
        return node;
    }

    /** Records which of its names it renamed. */
    private static final class MoveRemapper extends SimpleRemapper {
        final Set<String> hits = new HashSet<>();

        MoveRemapper(Map<String, String> moves) {
            super(moves);
        }

        @Override
        public String map(String key) {
            String mapped = super.map(key);
            if (mapped != null) {
                hits.add(key);
            }
            return mapped;
        }
    }

    /**
     * Missing dependency classes that now live elsewhere in the dependency: exactly one public
     * class there has the same simple name. A move is dropped if anything refers to the old name
     * in a way that cannot be rewritten: a mixin (applied from the jar as written), or a string
     * or resource that reflection could look the name up from.
     */
    private Map<String, String> findMoves(Pass pass, ModEntry dependent, Set<String> dependentClasses,
                                          Set<String> dependencyClasses) throws IOException {
        Map<String, String> moves = new LinkedHashMap<>();
        Map<String, List<String>> bySimpleName = null;
        for (Problem problem : pass.problems.values()) {
            if (problem.kind != Problem.Kind.MISSING_CLASS) {
                continue;
            }
            String old = problem.subject.replace('.', '/');
            if (bySimpleName == null) {
                bySimpleName = new HashMap<>();
                for (String cls : dependencyClasses) {
                    String simple = simpleName(cls);
                    List<String> list = bySimpleName.get(simple);
                    if (list == null) {
                        list = new ArrayList<>();
                        bySimpleName.put(simple, list);
                    }
                    list.add(cls);
                }
            }
            List<String> candidates = bySimpleName.get(simpleName(old));
            if (candidates == null || candidates.size() != 1 || index.contains(old)) {
                continue;
            }
            ClassInfo moved = index.info(candidates.get(0));
            if (moved != null && (moved.access & Opcodes.ACC_PUBLIC) != 0) {
                moves.put(old, moved.name);
            }
        }
        if (moves.isEmpty()) {
            return moves;
        }
        for (String name : dependentClasses) {
            ClassNode node = read(dependent.jar(), name, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
            if (node == null) {
                continue;
            }
            if (MixinChecker.isMixin(node)) {
                MoveRemapper remapper = new MoveRemapper(moves);
                node.accept(Asm.remapping(new ClassNode(), remapper));
                moves.keySet().removeAll(remapper.hits);
            }
            for (String text : strings(node)) {
                dropMentioned(moves, text);
            }
            if (moves.isEmpty()) {
                return moves;
            }
        }
        for (String entry : dependent.jar().entryNames()) {
            if (entry.endsWith(".class") || entry.endsWith("/")) {
                continue;
            }
            dropMentioned(moves, entry);
            if (isText(entry)) {
                byte[] data = dependent.jar().read(entry);
                if (data != null && data.length < 8 * 1024 * 1024) {
                    dropMentioned(moves, new String(data, StandardCharsets.UTF_8));
                }
            }
        }
        return moves;
    }

    private static boolean verifierAvailable() {
        try {
            Class.forName("com.linkwright.link.PatchVerifier$Verifier", true, LinkChecker.class.getClassLoader());
            return true;
        } catch (Throwable missing) {
            LwLog.debug("No bytecode verifier available, so no pair that needs patches will be approved: " + missing);
            return false;
        }
    }

    private static List<String> strings(ClassNode node) {
        List<String> found = new ArrayList<>();
        for (FieldNode field : node.fields) {
            if (field.value instanceof String) {
                found.add((String) field.value);
            }
        }
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : Asm.instructions(method.instructions)) {
                if (insn instanceof LdcInsnNode && ((LdcInsnNode) insn).cst instanceof String) {
                    found.add((String) ((LdcInsnNode) insn).cst);
                } else if (insn instanceof InvokeDynamicInsnNode) {
                    for (Object arg : ((InvokeDynamicInsnNode) insn).bsmArgs) {
                        if (arg instanceof String) {
                            found.add((String) arg);
                        }
                    }
                }
            }
        }
        return found;
    }

    private static void dropMentioned(Map<String, String> moves, String text) {
        for (java.util.Iterator<String> it = moves.keySet().iterator(); it.hasNext(); ) {
            String old = it.next();
            if (text.contains(old) || text.contains(old.replace('/', '.'))) {
                it.remove();
            }
        }
    }

    private static boolean isText(String entry) {
        String lower = entry.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("meta-inf/services/") || lower.endsWith(".json") || lower.endsWith(".toml")
                || lower.endsWith(".txt") || lower.endsWith(".properties") || lower.endsWith(".cfg")
                || lower.endsWith(".mcmeta") || lower.endsWith(".yml") || lower.endsWith(".yaml");
    }

    private static String simpleName(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }

    /**
     * Patches each adapted class in memory and proves the result verifies, then checks it again
     * from scratch: every reference into the dependency must now resolve as it stands.
     */
    private void verify(Pass pass, ModEntry dependent, boolean loader) throws IOException {
        if (!VERIFIER_AVAILABLE) {
            for (Patch patch : pass.patches.values()) {
                String where = patch.targetClass().replace('/', '.');
                pass.problem(Problem.Kind.UNSAFE_PATCH, where, where,
                        "this game's ASM has no bytecode verifier, so adapted code cannot be proven safe");
            }
            return;
        }
        Map<String, List<Patch>> byClass = new LinkedHashMap<>();
        for (Patch patch : pass.patches.values()) {
            List<Patch> list = byClass.get(patch.targetClass());
            if (list == null) {
                list = new ArrayList<>();
                byClass.put(patch.targetClass(), list);
            }
            list.add(patch);
        }
        PatchVerifier verifier = new PatchVerifier(hierarchy);
        Pass recheck = new Pass(pass.dependentClasses, pass.dependencyClasses, loader);
        for (Map.Entry<String, List<Patch>> entry : byClass.entrySet()) {
            String where = entry.getKey().replace('/', '.');
            byte[] bytes = dependent.jar().read(entry.getKey() + ".class");
            if (bytes == null) {
                pass.problem(Problem.Kind.UNSAFE_PATCH, where, where, "the class could not be read back");
                continue;
            }
            try {
                scanClass(verifier.patch(bytes, entry.getValue()), recheck);
            } catch (PatchVerifier.Failure failure) {
                pass.problem(Problem.Kind.UNSAFE_PATCH, where, where, failure.getMessage());
            }
        }
        for (Problem problem : recheck.problems.values()) {
            pass.problem(Problem.Kind.UNSAFE_PATCH, problem.subject, problem.firstSeenIn,
                    "still a " + problem.kind.label + " once adapted");
        }
        for (Patch patch : recheck.patches.values()) {
            String where = patch.targetClass().replace('/', '.');
            pass.problem(Problem.Kind.UNSAFE_PATCH, where, where, "would still need adapting once adapted: " + patch.describe());
        }
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
            for (AbstractInsnNode insn : Asm.instructions(method.instructions)) {
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
        if (insn instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) insn;
            methodRef(call.owner, call.name, call.desc, where, pass, mixin ? null : node, method, call);
        } else if (insn instanceof FieldInsnNode) {
            FieldInsnNode field = (FieldInsnNode) insn;
            fieldRef(field.owner, field.name, field.desc, where, pass, mixin ? null : node, method, field);
        } else if (insn instanceof TypeInsnNode) {
            typeRefs(Type.getObjectType(((TypeInsnNode) insn).desc), where, pass);
        } else if (insn instanceof MultiANewArrayInsnNode) {
            typeRefs(Type.getType(((MultiANewArrayInsnNode) insn).desc), where, pass);
        } else if (insn instanceof LdcInsnNode) {
            constantRef(((LdcInsnNode) insn).cst, where, pass);
        } else if (insn instanceof InvokeDynamicInsnNode) {
            InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) insn;
            constantRef(indy.bsm, where, pass);
            for (Object arg : indy.bsmArgs) {
                constantRef(arg, where, pass);
            }
        }
    }

    private void constantRef(Object constant, String where, Pass pass) {
        if (constant instanceof Type) {
            Type type = (Type) constant;
            if (type.getSort() == Type.METHOD) {
                for (Type t : type.getArgumentTypes()) {
                    typeRefs(t, where, pass);
                }
                typeRefs(type.getReturnType(), where, pass);
            } else {
                typeRefs(type, where, pass);
            }
        } else if (constant instanceof Handle) {
            Handle handle = (Handle) constant;
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
        String subject = owner.replace('/', '.') + "." + name + desc;
        String mismatch = null;
        if (resolution == Hierarchy.Resolution.FOUND && call != null) {
            mismatch = mismatch(hierarchy.findMethod(owner, name, desc), call.getOpcode() == Opcodes.INVOKESTATIC, caller);
            if (mismatch == null) {
                checkKind(owner, subject, where, pass, caller, method, call);
            }
        }
        if (resolution != Hierarchy.Resolution.MISSING && mismatch == null) {
            return;
        }
        String prefix = mismatch == null ? "" : mismatch + "; ";
        if (caller == null) {
            pass.problem(Problem.Kind.MISSING_METHOD, subject, where, prefix
                    + (call == null ? "referenced as a method handle, which cannot be bridged" : "called from a mixin, which cannot be bridged"));
            return;
        }
        BridgeFinder.Outcome outcome = bridgeFinder.findCall(caller, method, call);
        if (outcome instanceof BridgeFinder.Found) {
            addPatch(pass, caller.name + "." + method.name + method.desc + "|" + owner + "." + name + desc,
                    ((BridgeFinder.Found) outcome).patch);
        } else {
            pass.problem(Problem.Kind.MISSING_METHOD, subject, where, prefix + ((BridgeFinder.NotFound) outcome).reason);
        }
    }

    /**
     * Why a member that is present still cannot be used as compiled, or {@code null} if it can.
     * One that switched between static and instance fails with IncompatibleClassChangeError, and
     * one that turned private, or package-private in another package, with IllegalAccessError,
     * just as surely as a missing one. Protected members are given the benefit of the doubt.
     */
    private static String mismatch(ClassInfo.Member member, boolean staticAccess, ClassNode caller) {
        if (member == null) {
            return null;
        }
        if (member.isStatic() != staticAccess) {
            return member.isStatic() ? "it is static now" : "it is no longer static";
        }
        if (caller == null || (member.access() & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0) {
            return null;
        }
        boolean visible = (member.access() & Opcodes.ACC_PRIVATE) != 0 ? member.owner().equals(caller.name)
                : packageOf(member.owner()).equals(packageOf(caller.name));
        return visible ? null : "it is no longer accessible";
    }

    /**
     * A call compiled against a class that is an interface now, or the reverse, fails with
     * IncompatibleClassChangeError even though the method is there. Flipping the call to match
     * is all it takes, except for super calls and calls from mixins.
     */
    private void checkKind(String owner, String subject, String where, Pass pass, ClassNode caller, MethodNode method,
                           MethodInsnNode call) {
        ClassInfo info = index.info(owner);
        if (info == null || info.isInterface() == call.itf) {
            return;
        }
        String what = owner.replace('/', '.') + (info.isInterface() ? " is an interface now" : " is no longer an interface");
        int opcode = call.getOpcode();
        if (caller == null) {
            pass.problem(Problem.Kind.MISSING_METHOD, subject, where, what + ", and it is called from a mixin, which cannot be adapted");
            return;
        }
        if (opcode == Opcodes.INVOKESPECIAL) {
            pass.problem(Problem.Kind.MISSING_METHOD, subject, where, what + ", and super calls cannot be adapted");
            return;
        }
        if (opcode == Opcodes.INVOKESTATIC && info.isInterface() && (caller.version & 0xFFFF) < Opcodes.V1_8) {
            pass.problem(Problem.Kind.MISSING_METHOD, subject, where, what + ", and calling its static methods needs Java 8 class files");
            return;
        }
        int newOpcode = opcode == Opcodes.INVOKESTATIC ? opcode : info.isInterface() ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
        addPatch(pass, caller.name + "." + method.name + method.desc + "|kind|" + owner + "." + call.name + call.desc,
                new Bridge(caller.name, method.name, method.desc, opcode, owner, call.name, call.desc, call.desc, call.itf,
                        BridgeFinder.fromOld(0, Type.getArgumentTypes(call.desc).length), Conversion.IDENTITY, 0,
                        newOpcode, owner, call.name, info.isInterface(), Bridge.Receiver.SAME, null));
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
        String mismatch = null;
        if (resolution == Hierarchy.Resolution.FOUND && access != null) {
            boolean staticAccess = access.getOpcode() == Opcodes.GETSTATIC || access.getOpcode() == Opcodes.PUTSTATIC;
            mismatch = mismatch(hierarchy.findField(owner, name, desc), staticAccess, caller);
        }
        if (resolution != Hierarchy.Resolution.MISSING && mismatch == null) {
            return;
        }
        String prefix = mismatch == null ? "" : mismatch + "; ";
        String subject = owner.replace('/', '.') + "." + name + ":" + desc;
        if (caller == null) {
            pass.problem(Problem.Kind.MISSING_FIELD, subject, where, prefix
                    + (access == null ? "referenced as a method handle, which cannot be bridged" : "used from a mixin, which cannot be bridged"));
            return;
        }
        BridgeFinder.Outcome outcome = bridgeFinder.findField(caller, method, access);
        if (outcome instanceof BridgeFinder.Found) {
            addPatch(pass, caller.name + "." + method.name + method.desc + "|" + access.getOpcode() + "|" + subject,
                    ((BridgeFinder.Found) outcome).patch);
        } else {
            pass.problem(Problem.Kind.MISSING_FIELD, subject, where, prefix + ((BridgeFinder.NotFound) outcome).reason);
        }
    }

    private static void addPatch(Pass pass, String key, Patch patch) {
        if (!pass.patches.containsKey(key)) {
            pass.patches.put(key, patch);
        }
    }

    /** A concrete class that extends the dependency must still implement everything it declares abstract. */
    private void checkAbstracts(ClassNode node, Pass pass) {
        if ((node.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0) {
            return;
        }
        // The node rather than the index, so an adapted class is judged as adapted
        List<String> direct = new ArrayList<>();
        if (node.superName != null) {
            direct.add(node.superName);
        }
        direct.addAll(node.interfaces);
        List<ClassInfo> ancestors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String sup : direct) {
            for (ClassInfo info : hierarchy.ancestors(sup)) {
                if (seen.add(info.name)) {
                    ancestors.add(info);
                }
            }
        }
        boolean extendsDependency = false;
        for (ClassInfo ancestor : ancestors) {
            if (pass.dependencyClasses.contains(ancestor.name)) {
                extendsDependency = true;
                break;
            }
        }
        if (!extendsDependency || hasUnindexedAncestor(direct, ancestors)) {
            return;
        }
        Set<String> concrete = new HashSet<>();
        for (MethodNode m : node.methods) {
            if ((m.access & Opcodes.ACC_ABSTRACT) == 0) {
                concrete.add(m.name + m.desc);
            }
        }
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
                    if (outcome instanceof BridgeFinder.Found) {
                        addPatch(pass, node.name + "|impl|" + m.name() + m.desc(), ((BridgeFinder.Found) outcome).patch);
                        concrete.add(m.name() + m.desc());
                    } else {
                        pass.problem(Problem.Kind.UNIMPLEMENTED, ancestor.name.replace('/', '.') + "." + m.name() + m.desc(),
                                node.name.replace('/', '.'), ((BridgeFinder.NotFound) outcome).reason);
                    }
                }
            }
        }
    }

    /** Minecraft or another unindexed supertype could be supplying the implementation. */
    private boolean hasUnindexedAncestor(List<String> direct, List<ClassInfo> ancestors) {
        for (String sup : direct) {
            if (!index.contains(sup) && !Hierarchy.isJdkName(sup)) {
                return true;
            }
        }
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

    private static boolean startsWithAny(String name, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String packageOf(String internalName) {
        return internalName.substring(0, internalName.lastIndexOf('/') + 1);
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

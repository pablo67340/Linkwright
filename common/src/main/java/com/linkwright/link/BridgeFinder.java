package com.linkwright.link;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * Works out how dependent code can keep working against a changed dependency.
 *
 * <p>For a missing method, a replacement must have the same name and static-ness, return
 * something convertible to what the caller expects, and take the old arguments in their old
 * order, each the same type, a supertype, or a {@link Conversion} away. Parameters it gained must
 * be objects that exactly one value in the calling method can supply: a parameter, or one public
 * no-argument getter on a parameter. Parameters it lost must leave exactly one way to line the
 * remaining ones up.
 *
 * <p>When no such method exists, three narrower moves are tried, and used only when exactly one
 * of them fits: a getter renamed between {@code getX()} and the record-style {@code x()}, a getter
 * replaced by a public final field, and a method that switched between static and instance in a
 * way that keeps every argument (the object becoming the first parameter, or the reverse, or a
 * static method moving onto the class's singleton).
 *
 * <p>A missing field may have changed type to something convertible, or have been replaced by a
 * getter and setter. A missing implementation of a changed interface or abstract method may be
 * delegated to the same-named, or record-style renamed, method the class already has.
 *
 * <p>When more than one replacement fits equally well, nothing is guessed.
 */
public final class BridgeFinder {
    /** Either {@link Found} or {@link NotFound}. */
    public abstract static class Outcome {
    }

    public static final class Found extends Outcome {
        public final Patch patch;

        Found(Patch patch) {
            this.patch = patch;
        }
    }

    public static final class NotFound extends Outcome {
        public final String reason;

        NotFound(String reason) {
            this.reason = reason;
        }
    }

    /** A planned call: each argument is a {@link Bridge.FromOld} or a {@link Type} still to be found in scope. */
    private static final class Plan {
        final String desc;
        final List<Object> args;
        final Conversion returnConversion;
        final int popSize;

        Plan(String desc, List<Object> args, Conversion returnConversion, int popSize) {
            this.desc = desc;
            this.args = args;
            this.returnConversion = returnConversion;
            this.popSize = popSize;
        }
    }

    /** How the result of a replacement maps back to what the caller expects. */
    private static final class Result {
        final Conversion conversion;
        final int popSize;

        Result(Conversion conversion, int popSize) {
            this.conversion = conversion;
            this.popSize = popSize;
        }
    }

    private static final String JAVA_8 = "static interface methods need the calling class to be built for Java 8 or newer";

    private final Hierarchy hierarchy;

    public BridgeFinder(Hierarchy hierarchy) {
        this.hierarchy = hierarchy;
    }

    public Outcome findCall(ClassNode caller, MethodNode method, MethodInsnNode insn) {
        boolean isStatic = insn.getOpcode() == Opcodes.INVOKESTATIC;
        String callerPackage = packageOf(caller.name);
        List<Plan> plans = new ArrayList<>();
        List<String> rejections = new ArrayList<>();

        for (ClassInfo.Member candidate : hierarchy.methodsNamed(insn.owner, insn.name)) {
            if (candidate.desc().equals(insn.desc) || candidate.isStatic() != isStatic) {
                continue;
            }
            if (!accessible(candidate, callerPackage)) {
                rejections.add(candidate.desc() + " is not accessible");
                continue;
            }
            String why = planCall(insn.desc, candidate, plans);
            if (why != null) {
                rejections.add(candidate.desc() + " " + why);
            }
        }

        List<Bridge> bridges = new ArrayList<>();
        List<String> sourceProblems = new ArrayList<>();
        for (Plan plan : plans) {
            List<Bridge.Arg> args = new ArrayList<>();
            String missing = null;
            for (Object arg : plan.args) {
                if (arg instanceof Bridge.FromOld) {
                    args.add((Bridge.FromOld) arg);
                    continue;
                }
                Type need = (Type) arg;
                Object found = findSource(caller, method, need);
                if (found instanceof Bridge.ValueSource) {
                    args.add(new Bridge.FromScope((Bridge.ValueSource) found));
                } else {
                    missing = need.getClassName() + ": " + found;
                    break;
                }
            }
            if (missing != null) {
                sourceProblems.add(plan.desc + " needs " + missing);
                continue;
            }
            bridges.add(new Bridge(caller.name, method.name, method.desc, insn.getOpcode(), insn.owner, insn.name,
                    insn.desc, plan.desc, insn.itf, args, plan.returnConversion, plan.popSize));
        }
        if (bridges.isEmpty()) {
            rejections.addAll(sourceProblems);
            List<Patch> alternatives = alternatives(caller, method, insn, rejections);
            if (alternatives.size() == 1) {
                return new Found(alternatives.get(0));
            }
            if (alternatives.size() > 1) {
                return new NotFound("ambiguous between " + alternatives.get(0).describe() + " and " + alternatives.get(1).describe());
            }
            return new NotFound(rejections.isEmpty() ? "no method named " + insn.name + " remains"
                    : "no usable replacement (" + Bridge.join(rejections) + ")");
        }
        Collections.sort(bridges, new Comparator<Bridge>() {
            @Override
            public int compare(Bridge a, Bridge b) {
                int c = Long.compare(changed(a), changed(b));
                return c != 0 ? c : Long.compare(a.conversions(), b.conversions());
            }
        });
        Bridge best = bridges.get(0);
        if (bridges.size() > 1 && changed(bridges.get(1)) == changed(best) && bridges.get(1).conversions() == best.conversions()) {
            return new NotFound("ambiguous between " + bridges.get(0).newDesc + " and " + bridges.get(1).newDesc);
        }
        return new Found(best);
    }

    /** Parameters added or removed. */
    private static long changed(Bridge bridge) {
        return bridge.scopeArgs() + bridge.dropped();
    }

    /** Adds a plan for calling {@code candidate} in place of {@code oldDesc}, or says why not. */
    private String planCall(String oldDesc, ClassInfo.Member candidate, List<Plan> plans) {
        String newDesc = candidate.desc();
        Type[] oldArgs = Type.getArgumentTypes(oldDesc);
        Type[] newArgs = Type.getArgumentTypes(newDesc);
        Result result = result(Type.getReturnType(oldDesc), candidate);
        if (result == null) {
            return Type.getReturnType(newDesc).getSort() == Type.VOID ? "returns nothing"
                    : "returns " + Type.getReturnType(newDesc).getClassName();
        }
        if (newArgs.length < oldArgs.length) {
            return planDrop(oldArgs, newArgs, newDesc, result, plans);
        }
        long embeddings = countEmbeddings(oldArgs, newArgs);
        if (embeddings == 0) {
            return "does not take the old arguments in order";
        }
        if (embeddings > 1) {
            return "could take the old arguments in more than one way";
        }

        List<Object> args = new ArrayList<>();
        int i = 0;
        for (Type next : newArgs) {
            Conversion conversion = i < oldArgs.length ? Conversion.find(oldArgs[i], next, hierarchy) : null;
            // With exactly one embedding, the leftmost fit is the right one unless the rest no longer fit
            if (conversion != null && countEmbeddings(tail(oldArgs, i + 1), tail(newArgs, args.size() + 1)) > 0) {
                args.add(new Bridge.FromOld(i++, conversion));
            } else {
                if (next.getSort() != Type.OBJECT && next.getSort() != Type.ARRAY) {
                    return "adds a " + next.getClassName() + " parameter, which cannot be filled in safely";
                }
                args.add(next);
            }
        }
        if (i != oldArgs.length) {
            return "does not take the old arguments in order";
        }
        plans.add(new Plan(newDesc, args, result.conversion, result.popSize));
        return null;
    }

    /** A replacement that takes a subset of the old arguments, in the same order. */
    private String planDrop(Type[] oldArgs, Type[] newArgs, String newDesc, Result result, List<Plan> plans) {
        long ways = countDrops(oldArgs, newArgs);
        if (ways == 0) {
            return "takes fewer parameters, and not a subset of the old ones in order";
        }
        if (ways > 1) {
            return "takes fewer parameters, and which old ones it kept is ambiguous";
        }
        List<Object> args = new ArrayList<>();
        int i = 0;
        for (int j = 0; j < newArgs.length; j++) {
            while (i < oldArgs.length) {
                Conversion conversion = Conversion.find(oldArgs[i], newArgs[j], hierarchy);
                if (conversion != null && countDrops(tail(oldArgs, i + 1), tail(newArgs, j + 1)) > 0) {
                    args.add(new Bridge.FromOld(i++, conversion));
                    break;
                }
                i++;
            }
        }
        if (args.size() != newArgs.length) {
            return "takes fewer parameters, and not a subset of the old ones in order";
        }
        plans.add(new Plan(newDesc, args, result.conversion, result.popSize));
        return null;
    }

    /** How many ways the new arguments can each be filled from a distinct old one, in order. */
    private long countDrops(Type[] oldArgs, Type[] newArgs) {
        long[] ways = new long[newArgs.length + 1];
        ways[0] = 1;
        for (Type old : oldArgs) {
            for (int j = newArgs.length; j >= 1; j--) {
                if (Conversion.find(old, newArgs[j - 1], hierarchy) != null) {
                    ways[j] += ways[j - 1];
                }
            }
        }
        return ways[newArgs.length];
    }

    /** Maps a replacement's result to the old return type, or {@code null} if it cannot be. */
    private Result result(Type oldRet, ClassInfo.Member candidate) {
        Type newRet = Type.getReturnType(candidate.desc());
        if (oldRet.getSort() == Type.VOID) {
            return new Result(Conversion.IDENTITY, newRet.getSize());
        }
        if (newRet.getSort() == Type.VOID) {
            return null;
        }
        Conversion conversion = Conversion.find(newRet, oldRet, hierarchy);
        if (conversion == null) {
            conversion = Conversion.unwrap(Signatures.returnType(candidate), oldRet, hierarchy);
        }
        return conversion == null ? null : new Result(conversion, 0);
    }

    private static Type[] tail(Type[] types, int from) {
        return Arrays.copyOfRange(types, Math.min(from, types.length), types.length);
    }

    private long countEmbeddings(Type[] oldArgs, Type[] newArgs) {
        long[] ways = new long[oldArgs.length + 1];
        ways[0] = 1;
        for (Type next : newArgs) {
            for (int i = oldArgs.length; i >= 1; i--) {
                if (Conversion.find(oldArgs[i - 1], next, hierarchy) != null) {
                    ways[i] += ways[i - 1];
                }
            }
        }
        return ways[oldArgs.length];
    }

    /** Renamed getters, getters that became fields, and static/instance switches that fit the call. */
    private List<Patch> alternatives(ClassNode caller, MethodNode method, MethodInsnNode insn, List<String> rejections) {
        List<Patch> found = new ArrayList<>();
        if ("<init>".equals(insn.name) || "<clinit>".equals(insn.name)) {
            return found;
        }
        boolean isStatic = insn.getOpcode() == Opcodes.INVOKESTATIC;
        String callerPackage = packageOf(caller.name);
        Type[] oldArgs = Type.getArgumentTypes(insn.desc);
        Type oldRet = Type.getReturnType(insn.desc);

        if (oldArgs.length == 0 && oldRet.getSort() != Type.VOID) {
            for (String alt : renamedGetters(insn.name, oldRet)) {
                for (ClassInfo.Member candidate : hierarchy.methodsNamed(insn.owner, alt)) {
                    if (candidate.isStatic() != isStatic || !candidate.desc().startsWith("()") || !accessible(candidate, callerPackage)) {
                        continue;
                    }
                    Result result = result(oldRet, candidate);
                    if (result == null) {
                        continue;
                    }
                    String owner = isStatic ? candidate.owner() : insn.owner;
                    boolean itf = isStatic ? isInterface(owner) : insn.itf;
                    if (isStatic && itf && (caller.version & 0xFFFF) < Opcodes.V1_8) {
                        rejections.add(alt + candidate.desc() + ": " + JAVA_8);
                        continue;
                    }
                    found.add(new Bridge(caller.name, method.name, method.desc, insn.getOpcode(), insn.owner, insn.name,
                            insn.desc, candidate.desc(), insn.itf, Collections.<Bridge.Arg>emptyList(), result.conversion,
                            0, insn.getOpcode(), owner, alt, itf, Bridge.Receiver.SAME, null));
                }
            }
            if (!(insn.itf && !isStatic)) {
                for (String fieldName : getterFields(insn.name)) {
                    for (ClassInfo.Member field : hierarchy.fieldsNamed(insn.owner, fieldName)) {
                        if (field.isStatic() != isStatic || (field.access() & Opcodes.ACC_FINAL) == 0
                                || !accessible(field, callerPackage)) {
                            continue;
                        }
                        Conversion conversion = Conversion.find(Type.getType(field.desc()), oldRet, hierarchy);
                        if (conversion == null) {
                            conversion = Conversion.unwrap(Signatures.fieldType(field), oldRet, hierarchy);
                        }
                        if (conversion != null) {
                            found.add(new AccessorBridge(caller.name, method.name, method.desc, insn.getOpcode(), insn.owner,
                                    insn.name, insn.desc, insn.itf, isStatic ? Opcodes.GETSTATIC : Opcodes.GETFIELD,
                                    isStatic ? field.owner() : insn.owner, field.name(), field.desc(), false, conversion, 0));
                        }
                    }
                }
            }
        }
        staticSwitches(caller, method, insn, oldArgs, oldRet, found, rejections);
        return found;
    }

    private void staticSwitches(ClassNode caller, MethodNode method, MethodInsnNode insn, Type[] oldArgs, Type oldRet,
                                List<Patch> found, List<String> rejections) {
        boolean isStatic = insn.getOpcode() == Opcodes.INVOKESTATIC;
        String callerPackage = packageOf(caller.name);
        if (isStatic) {
            // The first argument is now the object the method is called on
            if (oldArgs.length > 0 && oldArgs[0].getSort() == Type.OBJECT && hierarchy.index().info(oldArgs[0].getInternalName()) != null) {
                String receiver = oldArgs[0].getInternalName();
                Type[] rest = tail(oldArgs, 1);
                for (ClassInfo.Member candidate : hierarchy.methodsNamed(receiver, insn.name)) {
                    if (candidate.isStatic() || !accessible(candidate, callerPackage)
                            || !Arrays.equals(Type.getArgumentTypes(candidate.desc()), rest)) {
                        continue;
                    }
                    Result result = result(oldRet, candidate);
                    if (result == null) {
                        continue;
                    }
                    boolean itf = isInterface(receiver);
                    found.add(new Bridge(caller.name, method.name, method.desc, insn.getOpcode(), insn.owner, insn.name,
                            insn.desc, candidate.desc(), insn.itf, fromOld(1, oldArgs.length), result.conversion,
                            result.popSize, itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, receiver, insn.name,
                            itf, Bridge.Receiver.FROM_ARG0, null));
                }
            }
            // An instance method on the class's singleton
            List<ClassInfo.Member> instance = new ArrayList<>();
            for (ClassInfo.Member candidate : hierarchy.methodsNamed(insn.owner, insn.name)) {
                if (!candidate.isStatic() && accessible(candidate, callerPackage)
                        && Arrays.equals(Type.getArgumentTypes(candidate.desc()), oldArgs)) {
                    instance.add(candidate);
                }
            }
            if (!instance.isEmpty()) {
                Object singleton = singleton(insn.owner, caller);
                if (singleton instanceof String) {
                    rejections.add(insn.name + " is an instance method now, and " + singleton);
                } else {
                    for (ClassInfo.Member candidate : instance) {
                        Result result = result(oldRet, candidate);
                        if (result == null) {
                            continue;
                        }
                        boolean itf = isInterface(insn.owner);
                        found.add(new Bridge(caller.name, method.name, method.desc, insn.getOpcode(), insn.owner, insn.name,
                                insn.desc, candidate.desc(), insn.itf, fromOld(0, oldArgs.length), result.conversion,
                                result.popSize, itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, insn.owner, insn.name,
                                itf, Bridge.Receiver.SINGLETON, (Bridge.Singleton) singleton));
                    }
                }
            }
            return;
        }
        Type ownerType = Type.getObjectType(insn.owner);
        for (ClassInfo.Member candidate : hierarchy.methodsNamed(insn.owner, insn.name)) {
            if (!candidate.isStatic() || !accessible(candidate, callerPackage)) {
                continue;
            }
            Type[] newArgs = Type.getArgumentTypes(candidate.desc());
            Bridge.Receiver receiver;
            if (newArgs.length == oldArgs.length + 1 && Boolean.TRUE.equals(hierarchy.isAssignable(ownerType, newArgs[0]))
                    && Arrays.equals(tail(newArgs, 1), oldArgs)) {
                receiver = Bridge.Receiver.AS_ARG0;
            } else if (Arrays.equals(newArgs, oldArgs)) {
                receiver = Bridge.Receiver.DROP;
            } else {
                continue;
            }
            Result result = result(oldRet, candidate);
            if (result == null) {
                continue;
            }
            boolean itf = isInterface(candidate.owner());
            if (itf && (caller.version & 0xFFFF) < Opcodes.V1_8) {
                rejections.add(candidate.desc() + ": " + JAVA_8);
                continue;
            }
            found.add(new Bridge(caller.name, method.name, method.desc, insn.getOpcode(), insn.owner, insn.name, insn.desc,
                    candidate.desc(), insn.itf, fromOld(0, oldArgs.length), result.conversion, result.popSize,
                    Opcodes.INVOKESTATIC, candidate.owner(), insn.name, itf, receiver, null));
        }
    }

    static List<Bridge.Arg> fromOld(int from, int to) {
        List<Bridge.Arg> args = new ArrayList<>();
        for (int i = from; i < to; i++) {
            args.add(new Bridge.FromOld(i, Conversion.IDENTITY));
        }
        return args;
    }

    /**
     * The one way to get {@code owner}'s singleton: a public static final field of its own type,
     * or a public static {@code getInstance()}/{@code instance()}/{@code get()}. Returns a
     * {@link Bridge.Singleton}, or a String explaining why there is none.
     */
    private Object singleton(String owner, ClassNode caller) {
        ClassInfo info = hierarchy.index().info(owner);
        if (info == null) {
            return "its class could not be read";
        }
        String type = "L" + owner + ";";
        List<Bridge.Singleton> found = new ArrayList<>();
        for (ClassInfo.Member f : info.fields.values()) {
            int mask = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL;
            if ((f.access() & mask) == mask && f.desc().equals(type)) {
                found.add(new Bridge.Singleton(true, owner, f.name(), f.desc(), false));
            }
        }
        for (ClassInfo.Member m : info.methods.values()) {
            if (m.isStatic() && (m.access() & Opcodes.ACC_PUBLIC) != 0 && m.desc().equals("()" + type)
                    && (m.name().equals("getInstance") || m.name().equals("instance") || m.name().equals("get"))) {
                if (info.isInterface() && (caller.version & 0xFFFF) < Opcodes.V1_8) {
                    continue;
                }
                found.add(new Bridge.Singleton(false, owner, m.name(), m.desc(), info.isInterface()));
            }
        }
        if (found.size() == 1) {
            return found.get(0);
        }
        return found.isEmpty() ? "the class has no singleton to call it on" : "the class has more than one singleton";
    }

    /** A field that changed type to something the access can be converted through, or was replaced by a getter or setter. */
    public Outcome findField(ClassNode caller, MethodNode method, FieldInsnNode insn) {
        boolean isStatic = insn.getOpcode() == Opcodes.GETSTATIC || insn.getOpcode() == Opcodes.PUTSTATIC;
        boolean read = insn.getOpcode() == Opcodes.GETSTATIC || insn.getOpcode() == Opcodes.GETFIELD;
        List<ClassInfo.Member> fields = hierarchy.fieldsNamed(insn.owner, insn.name);
        Type oldType = Type.getType(insn.desc);
        List<String> rejections = new ArrayList<>();
        for (ClassInfo.Member field : fields) {
            if (field.isStatic() != isStatic) {
                continue;
            }
            if (!accessible(field, packageOf(caller.name))) {
                rejections.add(field.desc() + " is not accessible");
                continue;
            }
            if (!read && (field.access() & Opcodes.ACC_FINAL) != 0) {
                rejections.add(field.desc() + " is final");
                continue;
            }
            Type newType = Type.getType(field.desc());
            Conversion conversion = read ? Conversion.find(newType, oldType, hierarchy) : Conversion.find(oldType, newType, hierarchy);
            if (conversion == null && read) {
                conversion = Conversion.unwrap(Signatures.fieldType(field), oldType, hierarchy);
            }
            if (conversion == null) {
                rejections.add("is now " + newType.getClassName() + ", with no safe conversion");
                continue;
            }
            return new Found(new FieldBridge(caller.name, method.name, method.desc, insn.getOpcode(), insn.owner,
                    insn.name, insn.desc, field.desc(), conversion));
        }
        List<Patch> accessors = accessors(caller, method, insn, isStatic, read, oldType, rejections);
        if (accessors.size() == 1) {
            return new Found(accessors.get(0));
        }
        if (accessors.size() > 1) {
            return new NotFound("ambiguous between " + accessors.get(0).describe() + " and " + accessors.get(1).describe());
        }
        return new NotFound(rejections.isEmpty() ? "no field named " + insn.name + " remains" : Bridge.join(rejections));
    }

    /** Getters ({@code getX}, {@code isX}, {@code x}) or setters ({@code setX}) standing in for a removed field. */
    private List<Patch> accessors(ClassNode caller, MethodNode method, FieldInsnNode insn, boolean isStatic, boolean read,
                                  Type oldType, List<String> rejections) {
        List<Patch> found = new ArrayList<>();
        String callerPackage = packageOf(caller.name);
        String cap = capitalize(insn.name);
        List<String> names = new ArrayList<>();
        if (read) {
            names.add("get" + cap);
            if (isBoolean(oldType)) {
                names.add("is" + cap);
            }
            names.add(insn.name);
        } else {
            names.add("set" + cap);
        }
        boolean ownerItf = isInterface(insn.owner);
        for (String name : names) {
            for (ClassInfo.Member candidate : hierarchy.methodsNamed(insn.owner, name)) {
                Type[] args = Type.getArgumentTypes(candidate.desc());
                if (candidate.isStatic() != isStatic || !accessible(candidate, callerPackage) || args.length != (read ? 0 : 1)) {
                    continue;
                }
                Conversion conversion;
                int popSize = 0;
                if (read) {
                    Result result = result(oldType, candidate);
                    conversion = result == null ? null : result.conversion;
                } else {
                    conversion = Conversion.find(oldType, args[0], hierarchy);
                    popSize = Type.getReturnType(candidate.desc()).getSize();
                }
                if (conversion == null) {
                    continue;
                }
                String owner = isStatic ? candidate.owner() : insn.owner;
                boolean itf = isStatic ? isInterface(candidate.owner()) : ownerItf;
                if (isStatic && itf && (caller.version & 0xFFFF) < Opcodes.V1_8) {
                    rejections.add(name + candidate.desc() + ": " + JAVA_8);
                    continue;
                }
                int opcode = isStatic ? Opcodes.INVOKESTATIC : itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
                found.add(new AccessorBridge(caller.name, method.name, method.desc, insn.getOpcode(), insn.owner, insn.name,
                        insn.desc, false, opcode, owner, name, candidate.desc(), itf, conversion, popSize));
            }
        }
        return found;
    }

    private static final class Candidate {
        final ImplBridge bridge;
        final int dropped;
        final long conversions;

        Candidate(ImplBridge bridge, int dropped, long conversions) {
            this.bridge = bridge;
            this.dropped = dropped;
            this.conversions = conversions;
        }
    }

    /**
     * A concrete class no longer implements {@code required}, but has a method of the same name
     * (or, for a getter, its record-style counterpart) whose arguments and result can be
     * converted to and from the required ones.
     */
    public Outcome findImplementation(ClassNode node, ClassInfo.Member required) {
        List<Candidate> candidates = implementations(node, required, Collections.singletonList(required.name()));
        Type reqRet = Type.getReturnType(required.desc());
        if (candidates.isEmpty() && Type.getArgumentTypes(required.desc()).length == 0 && reqRet.getSort() != Type.VOID) {
            candidates = implementations(node, required, renamedGetters(required.name(), reqRet));
        }
        if (candidates.isEmpty()) {
            return new NotFound("no method named " + required.name() + " to delegate to");
        }
        Collections.sort(candidates, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate a, Candidate b) {
                int c = Integer.compare(a.dropped, b.dropped);
                return c != 0 ? c : Long.compare(a.conversions, b.conversions);
            }
        });
        Candidate best = candidates.get(0);
        if (candidates.size() > 1 && candidates.get(1).dropped == best.dropped && candidates.get(1).conversions == best.conversions) {
            return new NotFound("several methods named " + required.name() + " could stand in for it");
        }
        return new Found(best.bridge);
    }

    private List<Candidate> implementations(ClassNode node, ClassInfo.Member required, List<String> names) {
        Type[] reqArgs = Type.getArgumentTypes(required.desc());
        Type reqRet = Type.getReturnType(required.desc());
        List<Candidate> candidates = new ArrayList<>();
        for (String name : names) {
            for (ClassInfo.Member have : hierarchy.methodsNamed(node.name, name)) {
                if ((have.name().equals(required.name()) && have.desc().equals(required.desc())) || have.isStatic() || have.isAbstract()) {
                    continue;
                }
                Type[] haveArgs = Type.getArgumentTypes(have.desc());
                Type haveRet = Type.getReturnType(have.desc());
                if (haveArgs.length > reqArgs.length) {
                    continue;
                }
                List<Conversion> params = new ArrayList<>();
                for (int i = 0; i < haveArgs.length && params != null; i++) {
                    Conversion c = Conversion.find(reqArgs[i], haveArgs[i], hierarchy);
                    if (c == null) {
                        params = null;
                    } else {
                        params.add(c);
                    }
                }
                if (params == null) {
                    continue;
                }
                Conversion ret = null;
                if (reqRet.getSort() != Type.VOID) {
                    if (haveRet.getSort() == Type.VOID || (ret = Conversion.find(haveRet, reqRet, hierarchy)) == null) {
                        continue;
                    }
                }
                long conversions = ret == null || ret.isIdentity() ? 0 : 1;
                for (Conversion c : params) {
                    if (!c.isIdentity()) {
                        conversions++;
                    }
                }
                candidates.add(new Candidate(new ImplBridge(node.name, required.name(), required.desc(), have.name(), have.desc(),
                        params, ret, required.owner()), reqArgs.length - haveArgs.length, conversions));
            }
        }
        return candidates;
    }

    /** {@code getX()} and {@code isX()} for a record-style {@code x()}, and the reverse. */
    static List<String> renamedGetters(String name, Type returnType) {
        List<String> names = new ArrayList<>();
        if (name.length() > 3 && name.startsWith("get") && Character.isUpperCase(name.charAt(3))) {
            names.add(decapitalize(name.substring(3)));
        } else if (name.length() > 2 && name.startsWith("is") && Character.isUpperCase(name.charAt(2))) {
            names.add(decapitalize(name.substring(2)));
        } else if (!name.isEmpty() && Character.isLowerCase(name.charAt(0))) {
            names.add("get" + capitalize(name));
            if (isBoolean(returnType)) {
                names.add("is" + capitalize(name));
            }
        }
        return names;
    }

    /** The field a getter would read: {@code x} for {@code getX()}, {@code isX()} or {@code x()}. */
    private static List<String> getterFields(String name) {
        if (name.length() > 3 && name.startsWith("get") && Character.isUpperCase(name.charAt(3))) {
            return Collections.singletonList(decapitalize(name.substring(3)));
        }
        if (name.length() > 2 && name.startsWith("is") && Character.isUpperCase(name.charAt(2))) {
            return Collections.singletonList(decapitalize(name.substring(2)));
        }
        return Collections.singletonList(name);
    }

    private static boolean isBoolean(Type t) {
        return t.getSort() == Type.BOOLEAN || t.getDescriptor().equals("Ljava/lang/Boolean;");
    }

    static String capitalize(String name) {
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** As JavaBeans does it: {@code URL} stays {@code URL}, {@code Name} becomes {@code name}. */
    static String decapitalize(String name) {
        if (name.length() > 1 && Character.isUpperCase(name.charAt(1)) && Character.isUpperCase(name.charAt(0))) {
            return name;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private boolean isInterface(String owner) {
        ClassInfo info = hierarchy.index().info(owner);
        return info != null && info.isInterface();
    }

    private static final class Slot {
        final int index;
        final Type type;
        final String label;

        Slot(int index, Type type, String label) {
            this.index = index;
            this.type = type;
            this.label = label;
        }
    }

    /** Returns a {@link Bridge.ValueSource}, or a String explaining why there is none. */
    private Object findSource(ClassNode caller, MethodNode method, Type need) {
        List<Slot> slots = new ArrayList<>();
        int index = 0;
        if ((method.access & Opcodes.ACC_STATIC) == 0) {
            slots.add(new Slot(0, Type.getObjectType(caller.name), "this"));
            index = 1;
        }
        Type[] params = Type.getArgumentTypes(method.desc);
        for (int p = 0; p < params.length; p++) {
            if (params[p].getSort() == Type.OBJECT || params[p].getSort() == Type.ARRAY) {
                slots.add(new Slot(index, params[p], "parameter " + (p + 1) + " (" + Bridge.simple(params[p]) + ")"));
            }
            index += params[p].getSize();
        }
        for (Iterator<Slot> it = slots.iterator(); it.hasNext(); ) {
            if (isReassigned(method, it.next().index)) {
                it.remove();
            }
        }

        List<Bridge.ValueSource> direct = new ArrayList<>();
        for (Slot slot : slots) {
            if (Boolean.TRUE.equals(hierarchy.isAssignable(slot.type, need))) {
                direct.add(new Bridge.ValueSource(slot.index, slot.type.getInternalName(), null, null, null, false, slot.label));
            }
        }
        if (direct.size() == 1) {
            return direct.get(0);
        }
        if (direct.size() > 1) {
            return "several parameters fit";
        }

        List<Bridge.ValueSource> viaGetter = new ArrayList<>();
        for (Slot slot : slots) {
            if (slot.type.getSort() != Type.OBJECT) {
                continue;
            }
            ClassInfo info = hierarchy.index().info(slot.type.getInternalName());
            if (info == null) {
                continue;
            }
            for (ClassInfo.Member getter : hierarchy.zeroArgGetters(slot.type.getInternalName())) {
                if (Boolean.TRUE.equals(hierarchy.isAssignable(Type.getReturnType(getter.desc()), need))) {
                    viaGetter.add(new Bridge.ValueSource(slot.index, slot.type.getInternalName(),
                            slot.type.getInternalName(), getter.name(), getter.desc(), info.isInterface(),
                            slot.label + "." + getter.name() + "()"));
                }
            }
        }
        if (viaGetter.size() == 1) {
            return viaGetter.get(0);
        }
        return viaGetter.isEmpty() ? "nothing in scope provides it" : "several getters fit";
    }

    private static boolean accessible(ClassInfo.Member member, String callerPackage) {
        return (member.access() & Opcodes.ACC_PUBLIC) != 0
                || ((member.access() & Opcodes.ACC_PRIVATE) == 0 && packageOf(member.owner()).equals(callerPackage));
    }

    private static boolean isReassigned(MethodNode method, int slot) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof VarInsnNode && ((VarInsnNode) insn).var == slot
                    && insn.getOpcode() >= Opcodes.ISTORE && insn.getOpcode() <= Opcodes.ASTORE) {
                return true;
            }
        }
        return false;
    }

    private static String packageOf(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? "" : internalName.substring(0, slash);
    }
}

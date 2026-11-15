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
import java.util.Comparator;
import java.util.List;

/**
 * Works out how dependent code can keep working against a changed dependency.
 *
 * <p>For a missing method, a replacement must have the same name and static-ness, return
 * something convertible to what the caller expects, and take the old arguments in their old
 * order, each the same type, a supertype, or a {@link Conversion} away. Parameters it gained must
 * be objects that exactly one value in the calling method can supply: a parameter, or one public
 * no-argument getter on a parameter. A missing field may have changed type to something
 * convertible. A missing implementation of a changed interface or abstract method may be
 * delegated to the same-named method the class already has.
 *
 * <p>When more than one replacement fits equally well, nothing is guessed.
 */
public final class BridgeFinder {
    public sealed interface Outcome permits Found, NotFound {
    }

    public record Found(Patch patch) implements Outcome {
    }

    public record NotFound(String reason) implements Outcome {
    }

    private record Plan(String desc, List<Object> args, Conversion returnConversion, int popSize) {
    }

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
            String why = planCall(insn.desc, candidate.desc(), plans);
            if (why != null) {
                rejections.add(candidate.desc() + " " + why);
            }
        }
        if (plans.isEmpty()) {
            return new NotFound(rejections.isEmpty() ? "no method named " + insn.name + " remains"
                    : "no usable replacement (" + String.join("; ", rejections) + ")");
        }

        List<Bridge> bridges = new ArrayList<>();
        List<String> sourceProblems = new ArrayList<>();
        for (Plan plan : plans) {
            List<Bridge.Arg> args = new ArrayList<>();
            String missing = null;
            for (Object arg : plan.args) {
                if (arg instanceof Bridge.FromOld old) {
                    args.add(old);
                    continue;
                }
                Type need = (Type) arg;
                Object found = findSource(caller, method, need);
                if (found instanceof Bridge.ValueSource source) {
                    args.add(new Bridge.FromScope(source));
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
                    insn.desc, plan.desc, insn.itf, List.copyOf(args), plan.returnConversion, plan.popSize));
        }
        if (bridges.isEmpty()) {
            return new NotFound(String.join("; ", sourceProblems));
        }
        bridges.sort(Comparator.comparingLong(Bridge::scopeArgs).thenComparingLong(Bridge::conversions));
        Bridge best = bridges.get(0);
        if (bridges.size() > 1 && bridges.get(1).scopeArgs() == best.scopeArgs()
                && bridges.get(1).conversions() == best.conversions()) {
            return new NotFound("ambiguous between " + bridges.get(0).newDesc() + " and " + bridges.get(1).newDesc());
        }
        return new Found(best);
    }

    /** Adds a plan for calling {@code newDesc} in place of {@code oldDesc}, or says why not. */
    private String planCall(String oldDesc, String newDesc, List<Plan> plans) {
        Type[] oldArgs = Type.getArgumentTypes(oldDesc);
        Type[] newArgs = Type.getArgumentTypes(newDesc);
        Type oldRet = Type.getReturnType(oldDesc);
        Type newRet = Type.getReturnType(newDesc);

        int popSize = 0;
        Conversion returnConversion = Conversion.IDENTITY;
        if (oldRet.getSort() == Type.VOID) {
            popSize = newRet.getSize();
        } else if (newRet.getSort() == Type.VOID) {
            return "returns nothing";
        } else {
            returnConversion = Conversion.find(newRet, oldRet, hierarchy);
            if (returnConversion == null) {
                return "returns " + newRet.getClassName();
            }
        }
        if (newArgs.length < oldArgs.length) {
            return "takes fewer parameters";
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
        plans.add(new Plan(newDesc, args, returnConversion, popSize));
        return null;
    }

    private static Type[] tail(Type[] types, int from) {
        return java.util.Arrays.copyOfRange(types, Math.min(from, types.length), types.length);
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

    /** A field that kept its name but changed to a type the access can be converted through. */
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
            if (conversion == null) {
                rejections.add("is now " + newType.getClassName() + ", with no safe conversion");
                continue;
            }
            return new Found(new FieldBridge(caller.name, method.name, method.desc, insn.getOpcode(), insn.owner,
                    insn.name, insn.desc, field.desc(), conversion));
        }
        return new NotFound(rejections.isEmpty() ? "no field named " + insn.name + " remains" : String.join("; ", rejections));
    }

    /**
     * A concrete class no longer implements {@code required}, but has a method of the same name
     * whose arguments and result can be converted to and from the required ones.
     */
    public Outcome findImplementation(ClassNode node, ClassInfo.Member required) {
        Type[] reqArgs = Type.getArgumentTypes(required.desc());
        Type reqRet = Type.getReturnType(required.desc());
        record Candidate(ImplBridge bridge, int dropped, long conversions) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (ClassInfo.Member have : hierarchy.methodsNamed(node.name, required.name())) {
            if (have.desc().equals(required.desc()) || have.isStatic() || have.isAbstract()) {
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
            long conversions = params.stream().filter(c -> !c.isIdentity()).count() + (ret == null || ret.isIdentity() ? 0 : 1);
            candidates.add(new Candidate(new ImplBridge(node.name, required.name(), required.desc(), have.name(), have.desc(),
                    List.copyOf(params), ret, required.owner()), reqArgs.length - haveArgs.length, conversions));
        }
        if (candidates.isEmpty()) {
            return new NotFound("no method named " + required.name() + " to delegate to");
        }
        candidates.sort(Comparator.comparingInt(Candidate::dropped).thenComparingLong(Candidate::conversions));
        Candidate best = candidates.get(0);
        if (candidates.size() > 1 && candidates.get(1).dropped == best.dropped && candidates.get(1).conversions == best.conversions) {
            return new NotFound("several methods named " + required.name() + " could stand in for it");
        }
        return new Found(best.bridge);
    }

    /** Returns a {@link Bridge.ValueSource}, or a String explaining why there is none. */
    private Object findSource(ClassNode caller, MethodNode method, Type need) {
        record Slot(int index, Type type, String label) {
        }
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
        slots.removeIf(slot -> isReassigned(method, slot.index));

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
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof VarInsnNode var && var.var == slot
                    && var.getOpcode() >= Opcodes.ISTORE && var.getOpcode() <= Opcodes.ASTORE) {
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

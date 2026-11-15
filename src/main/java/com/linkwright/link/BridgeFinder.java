package com.linkwright.link;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Looks for the method a missing call most likely became, and for values in the calling method
 * that can fill any parameters it gained.
 *
 * <p>A candidate has the same name, the same static-ness, a compatible return type, and keeps the
 * old parameters in their old order (each one the same type or a supertype of it). Every added
 * parameter must be an object type that exactly one parameter of the caller can supply, either
 * directly or through one public no-argument getter on it. Anything less certain is left as a
 * problem rather than guessed at.
 */
public final class BridgeFinder {
    public sealed interface Outcome permits Found, NotFound {
    }

    public record Found(Bridge bridge) implements Outcome {
    }

    public record NotFound(String reason) implements Outcome {
    }

    private record Plan(String desc, Bridge.Strategy strategy, List<Type> added, int popSize) {
    }

    private final Hierarchy hierarchy;

    public BridgeFinder(Hierarchy hierarchy) {
        this.hierarchy = hierarchy;
    }

    public Outcome find(ClassNode caller, MethodNode method, MethodInsnNode insn) {
        boolean isStatic = insn.getOpcode() == Opcodes.INVOKESTATIC;
        String callerPackage = packageOf(caller.name);
        List<Plan> plans = new ArrayList<>();
        List<String> rejections = new ArrayList<>();

        for (ClassInfo.Member candidate : hierarchy.methodsNamed(insn.owner, insn.name)) {
            if (candidate.desc().equals(insn.desc) || candidate.isStatic() != isStatic) {
                continue;
            }
            boolean accessible = (candidate.access() & Opcodes.ACC_PUBLIC) != 0
                    || ((candidate.access() & Opcodes.ACC_PRIVATE) == 0 && packageOf(candidate.owner()).equals(callerPackage));
            if (!accessible) {
                rejections.add(candidate.desc() + " is not accessible");
                continue;
            }
            String why = planFor(insn.desc, candidate.desc(), plans);
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
            List<Bridge.ValueSource> sources = new ArrayList<>();
            String missing = null;
            for (Type need : plan.added) {
                Object found = findSource(caller, method, need);
                if (found instanceof Bridge.ValueSource source) {
                    sources.add(source);
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
                    insn.desc, plan.desc, insn.itf, plan.strategy, List.copyOf(sources), plan.popSize));
        }

        if (bridges.isEmpty()) {
            return new NotFound(String.join("; ", sourceProblems));
        }
        int fewest = bridges.stream().mapToInt(b -> b.sources().size()).min().orElse(0);
        List<Bridge> best = bridges.stream().filter(b -> b.sources().size() == fewest).toList();
        if (best.size() > 1) {
            return new NotFound("ambiguous: " + best.stream().map(Bridge::newDesc).toList());
        }
        return new Found(best.get(0));
    }

    /** Adds a plan for calling {@code newDesc} in place of {@code oldDesc}, or says why not. */
    private String planFor(String oldDesc, String newDesc, List<Plan> plans) {
        Type[] oldArgs = Type.getArgumentTypes(oldDesc);
        Type[] newArgs = Type.getArgumentTypes(newDesc);
        Type oldRet = Type.getReturnType(oldDesc);
        Type newRet = Type.getReturnType(newDesc);

        int popSize = 0;
        if (oldRet.getSort() == Type.VOID && newRet.getSort() != Type.VOID) {
            popSize = newRet.getSize();
        } else if (!Boolean.TRUE.equals(hierarchy.isAssignable(newRet, oldRet))) {
            return "returns " + newRet.getClassName();
        }
        if (newArgs.length < oldArgs.length) {
            return "takes fewer parameters";
        }
        long embeddings = countEmbeddings(oldArgs, newArgs);
        if (embeddings == 0) {
            return "does not keep the old parameters in order";
        }
        if (embeddings > 1) {
            return "could take the old parameters in more than one way";
        }

        int[] map = new int[oldArgs.length];
        boolean[] mapped = new boolean[newArgs.length];
        int j = 0;
        for (int i = 0; i < oldArgs.length; i++) {
            while (!compatible(oldArgs[i], newArgs[j])) {
                j++;
            }
            map[i] = j;
            mapped[j++] = true;
        }
        List<Integer> addedAt = new ArrayList<>();
        List<Type> added = new ArrayList<>();
        for (int k = 0; k < newArgs.length; k++) {
            if (!mapped[k]) {
                addedAt.add(k);
                added.add(newArgs[k]);
            }
        }
        for (Type t : added) {
            if (t.getSort() != Type.OBJECT && t.getSort() != Type.ARRAY) {
                return "adds a primitive " + t.getClassName() + " parameter";
            }
        }

        int lastMapped = oldArgs.length == 0 ? -1 : map[oldArgs.length - 1];
        Bridge.Strategy strategy;
        if (addedAt.stream().allMatch(k -> k > lastMapped)) {
            strategy = Bridge.Strategy.APPEND;
        } else if (addedAt.size() == 1 && oldArgs.length >= 1 && oldArgs[oldArgs.length - 1].getSize() == 1
                && addedAt.get(0) == lastMapped - 1) {
            strategy = Bridge.Strategy.SWAP_BEFORE_LAST;
        } else {
            return "inserts parameters in the middle";
        }
        plans.add(new Plan(newDesc, strategy, added, popSize));
        return null;
    }

    private long countEmbeddings(Type[] oldArgs, Type[] newArgs) {
        long[] ways = new long[oldArgs.length + 1];
        ways[0] = 1;
        for (Type next : newArgs) {
            for (int i = oldArgs.length; i >= 1; i--) {
                if (compatible(oldArgs[i - 1], next)) {
                    ways[i] += ways[i - 1];
                }
            }
        }
        return ways[oldArgs.length];
    }

    private boolean compatible(Type oldArg, Type newArg) {
        return oldArg.equals(newArg) || Boolean.TRUE.equals(hierarchy.isAssignable(oldArg, newArg));
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
                slots.add(new Slot(index, params[p], "parameter " + (p + 1) + " (" + simpleName(params[p]) + ")"));
            }
            index += params[p].getSize();
        }
        slots.removeIf(slot -> isReassigned(method, slot.index));

        List<Bridge.ValueSource> direct = new ArrayList<>();
        for (Slot slot : slots) {
            if (Boolean.TRUE.equals(hierarchy.isAssignable(slot.type, need))) {
                direct.add(new Bridge.ValueSource(slot.index, slot.type.getInternalName(), null, null, null, false,
                        slot.label));
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

    private static boolean isReassigned(MethodNode method, int slot) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof VarInsnNode var && var.var == slot
                    && var.getOpcode() >= Opcodes.ISTORE && var.getOpcode() <= Opcodes.ASTORE) {
                return true;
            }
        }
        return false;
    }

    private static String simpleName(Type t) {
        String name = t.getClassName();
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static String packageOf(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? "" : internalName.substring(0, slash);
    }
}

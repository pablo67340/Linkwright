package com.linkwright.link;

import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.List;

/**
 * A rewrite of every call to {@code owner.name oldDesc} inside one method, so it calls the
 * signature the dependency has now. Each parameter of the new signature is filled either from
 * one of the old arguments, converted if its type changed, or from a value already in scope.
 *
 * @param returnConversion converts the new return value back to what the caller expects
 * @param popSize          stack slots to discard when the new method returns a value and the old
 *                         one was {@code void}
 */
public record Bridge(String callerClass, String callerName, String callerDesc, int opcode, String owner,
                     String name, String oldDesc, String newDesc, boolean itf, List<Arg> args,
                     Conversion returnConversion, int popSize) implements Patch {

    public sealed interface Arg permits FromOld, FromScope {
    }

    /** Old argument {@code index}, passed through {@code conversion}. */
    public record FromOld(int index, Conversion conversion) implements Arg {
    }

    /** A value the old signature never passed, found in the calling method. */
    public record FromScope(ValueSource source) implements Arg {
    }

    /**
     * A parameter (or {@code this}) of the calling method, optionally followed by one
     * no-argument getter on it.
     */
    public record ValueSource(int slot, String slotType, String getterOwner, String getterName,
                              String getterDesc, boolean getterItf, String description) {
        public boolean hasGetter() {
            return getterName != null;
        }
    }

    @Override
    public String targetClass() {
        return callerClass;
    }

    public long scopeArgs() {
        return args.stream().filter(a -> a instanceof FromScope).count();
    }

    public long conversions() {
        long count = args.stream().filter(a -> a instanceof FromOld f && !f.conversion().isIdentity()).count();
        return count + (returnConversion.isIdentity() ? 0 : 1);
    }

    @Override
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(Patch.dotted(callerClass)).append('.').append(callerName).append(": calls ")
                .append(Patch.dotted(owner)).append('.').append(name).append(oldDesc)
                .append(" as ").append(newDesc);
        List<String> notes = new ArrayList<>();
        Type[] newArgs = Type.getArgumentTypes(newDesc);
        for (int j = 0; j < args.size(); j++) {
            Arg arg = args.get(j);
            if (arg instanceof FromScope scope) {
                notes.add("new parameter " + (j + 1) + " (" + simple(newArgs[j]) + ") from " + scope.source().description());
            } else if (arg instanceof FromOld old && !old.conversion().isIdentity()) {
                notes.add("argument " + (old.index() + 1) + " via " + old.conversion().label());
            } else if (arg instanceof FromOld old && old.index() != j) {
                notes.add("argument " + (old.index() + 1) + " moved to position " + (j + 1));
            }
        }
        if (!returnConversion.isIdentity()) {
            notes.add("result via " + returnConversion.label());
        }
        if (popSize > 0) {
            notes.add("new return value discarded");
        }
        if (!notes.isEmpty()) {
            sb.append(" (").append(String.join("; ", notes)).append(')');
        }
        return sb.toString();
    }

    static String simple(Type t) {
        String name = t.getClassName();
        return name.substring(name.lastIndexOf('.') + 1);
    }
}

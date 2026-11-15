package com.linkwright.link;

import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A rewrite of every call to {@code owner.name oldDesc} inside one method, so it calls the
 * signature the dependency has now. Each parameter of the new signature is filled either from
 * one of the old arguments, converted if its type changed, or from a value already in scope.
 */
public final class Bridge implements Patch {
    /** Where one argument of the new call comes from. */
    public interface Arg {
    }

    /** Old argument {@code index}, passed through {@code conversion}. */
    public static final class FromOld implements Arg {
        public final int index;
        public final Conversion conversion;

        public FromOld(int index, Conversion conversion) {
            this.index = index;
            this.conversion = conversion;
        }
    }

    /** A value the old signature never passed, found in the calling method. */
    public static final class FromScope implements Arg {
        public final ValueSource source;

        public FromScope(ValueSource source) {
            this.source = source;
        }
    }

    /**
     * A parameter (or {@code this}) of the calling method, optionally followed by one
     * no-argument getter on it.
     */
    public static final class ValueSource {
        public final int slot;
        public final String slotType;
        public final String getterOwner;
        public final String getterName;
        public final String getterDesc;
        public final boolean getterItf;
        public final String description;

        public ValueSource(int slot, String slotType, String getterOwner, String getterName, String getterDesc,
                           boolean getterItf, String description) {
            this.slot = slot;
            this.slotType = slotType;
            this.getterOwner = getterOwner;
            this.getterName = getterName;
            this.getterDesc = getterDesc;
            this.getterItf = getterItf;
            this.description = description;
        }

        public boolean hasGetter() {
            return getterName != null;
        }
    }

    public final String callerClass;
    public final String callerName;
    public final String callerDesc;
    public final int opcode;
    public final String owner;
    public final String name;
    public final String oldDesc;
    public final String newDesc;
    public final boolean itf;
    public final List<Arg> args;
    /** Converts the new return value back to what the caller expects. */
    public final Conversion returnConversion;
    /** Stack slots to discard when the new method returns a value and the old one was {@code void}. */
    public final int popSize;

    public Bridge(String callerClass, String callerName, String callerDesc, int opcode, String owner, String name,
                  String oldDesc, String newDesc, boolean itf, List<Arg> args, Conversion returnConversion, int popSize) {
        this.callerClass = callerClass;
        this.callerName = callerName;
        this.callerDesc = callerDesc;
        this.opcode = opcode;
        this.owner = owner;
        this.name = name;
        this.oldDesc = oldDesc;
        this.newDesc = newDesc;
        this.itf = itf;
        this.args = Collections.unmodifiableList(new ArrayList<>(args));
        this.returnConversion = returnConversion;
        this.popSize = popSize;
    }

    @Override
    public String targetClass() {
        return callerClass;
    }

    public long scopeArgs() {
        long count = 0;
        for (Arg a : args) {
            if (a instanceof FromScope) {
                count++;
            }
        }
        return count;
    }

    public long conversions() {
        long count = 0;
        for (Arg a : args) {
            if (a instanceof FromOld && !((FromOld) a).conversion.isIdentity()) {
                count++;
            }
        }
        return count + (returnConversion.isIdentity() ? 0 : 1);
    }

    @Override
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(dotted(callerClass)).append('.').append(callerName).append(": calls ")
                .append(dotted(owner)).append('.').append(name).append(oldDesc)
                .append(" as ").append(newDesc);
        List<String> notes = new ArrayList<>();
        Type[] newArgs = Type.getArgumentTypes(newDesc);
        for (int j = 0; j < args.size(); j++) {
            Arg arg = args.get(j);
            if (arg instanceof FromScope) {
                notes.add("new parameter " + (j + 1) + " (" + simple(newArgs[j]) + ") from "
                        + ((FromScope) arg).source.description);
            } else if (arg instanceof FromOld) {
                FromOld old = (FromOld) arg;
                if (!old.conversion.isIdentity()) {
                    notes.add("argument " + (old.index + 1) + " via " + old.conversion.label());
                } else if (old.index != j) {
                    notes.add("argument " + (old.index + 1) + " moved to position " + (j + 1));
                }
            }
        }
        if (!returnConversion.isIdentity()) {
            notes.add("result via " + returnConversion.label());
        }
        if (popSize > 0) {
            notes.add("new return value discarded");
        }
        if (!notes.isEmpty()) {
            sb.append(" (").append(join(notes)).append(')');
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return describe();
    }

    static String simple(Type t) {
        String name = t.getClassName();
        return name.substring(name.lastIndexOf('.') + 1);
    }

    static String dotted(String internalName) {
        return internalName.replace('/', '.');
    }

    static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(p);
        }
        return sb.toString();
    }
}

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

    /** What happens to the object the old call was made on, when the new call is static where the old was not, or the reverse. */
    public enum Receiver {
        /** Unchanged: there is none, or it stays where it is. */
        SAME,
        /** The old instance method is static now and needs no object; it is discarded. */
        DROP,
        /** The old static method's first argument is now the object the method is called on. */
        FROM_ARG0,
        /** The old instance method is static now and takes the object as its first parameter. */
        AS_ARG0,
        /** The old static method is an instance method now, called on the class's one singleton. */
        SINGLETON
    }

    /** A public static final field or public static method that hands out a class's singleton. */
    public static final class Singleton {
        public final boolean field;
        public final String owner;
        public final String name;
        public final String desc;
        public final boolean itf;

        public Singleton(boolean field, String owner, String name, String desc, boolean itf) {
            this.field = field;
            this.owner = owner;
            this.name = name;
            this.desc = desc;
            this.itf = itf;
        }

        String describe() {
            return simple(Type.getObjectType(owner)) + "." + name + (field ? "" : "()");
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
    public final int newOpcode;
    public final String newOwner;
    public final String newName;
    public final boolean newItf;
    public final Receiver receiver;
    /** For {@link Receiver#SINGLETON}. */
    public final Singleton singleton;

    public Bridge(String callerClass, String callerName, String callerDesc, int opcode, String owner, String name,
                  String oldDesc, String newDesc, boolean itf, List<Arg> args, Conversion returnConversion, int popSize) {
        this(callerClass, callerName, callerDesc, opcode, owner, name, oldDesc, newDesc, itf, args, returnConversion, popSize,
                opcode, owner, name, itf, Receiver.SAME, null);
    }

    public Bridge(String callerClass, String callerName, String callerDesc, int opcode, String owner, String name,
                  String oldDesc, String newDesc, boolean itf, List<Arg> args, Conversion returnConversion, int popSize,
                  int newOpcode, String newOwner, String newName, boolean newItf, Receiver receiver, Singleton singleton) {
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
        this.newOpcode = newOpcode;
        this.newOwner = newOwner;
        this.newName = newName;
        this.newItf = newItf;
        this.receiver = receiver;
        this.singleton = singleton;
    }

    /** Old arguments the new signature no longer takes. */
    public int dropped() {
        int used = receiver == Receiver.FROM_ARG0 ? 1 : 0;
        for (Arg a : args) {
            if (a instanceof FromOld) {
                used++;
            }
        }
        return Type.getArgumentTypes(oldDesc).length - used;
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
                .append(" as ");
        if (!newOwner.equals(owner)) {
            sb.append(dotted(newOwner)).append('.');
        }
        if (!newName.equals(name) || !newOwner.equals(owner)) {
            sb.append(newName);
        }
        sb.append(newDesc);
        List<String> notes = new ArrayList<>();
        switch (receiver) {
            case DROP:
                notes.add("now static, the object it was called on is not needed");
                break;
            case FROM_ARG0:
                notes.add("now called on argument 1");
                break;
            case AS_ARG0:
                notes.add("now static, the object it was called on is passed as parameter 1");
                break;
            case SINGLETON:
                notes.add("now called on " + singleton.describe());
                break;
            default:
                if (newItf != itf && newOwner.equals(owner)) {
                    notes.add(newItf ? "now an interface" : "no longer an interface");
                }
                break;
        }
        Type[] newArgs = Type.getArgumentTypes(newDesc);
        int offset = receiver == Receiver.AS_ARG0 ? 1 : 0;
        for (int j = 0; j < args.size(); j++) {
            Arg arg = args.get(j);
            int position = j + offset;
            if (arg instanceof FromScope) {
                notes.add("new parameter " + (position + 1) + " (" + simple(newArgs[position]) + ") from "
                        + ((FromScope) arg).source.description);
            } else if (arg instanceof FromOld) {
                FromOld old = (FromOld) arg;
                if (!old.conversion.isIdentity()) {
                    notes.add("argument " + (old.index + 1) + " via " + old.conversion.label());
                } else if (old.index != position && receiver == Receiver.SAME) {
                    notes.add("argument " + (old.index + 1) + " moved to position " + (position + 1));
                }
            }
        }
        if (receiver == Receiver.SAME && dropped() > 0) {
            notes.add(dropped() + " old argument" + (dropped() > 1 ? "s" : "") + " no longer needed");
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

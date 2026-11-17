package com.linkwright.link;

import org.objectweb.asm.Opcodes;

/**
 * A rewrite, inside one method, of a field access into a call to the getter or setter that
 * replaced the field, or of a getter call into a read of the final field that replaced it.
 */
public final class AccessorBridge implements Patch {
    public final String callerClass;
    public final String callerName;
    public final String callerDesc;
    /** The old instruction: a field opcode or an invoke opcode. */
    public final int opcode;
    public final String owner;
    public final String name;
    public final String desc;
    public final boolean itf;
    /** The replacement instruction. */
    public final int newOpcode;
    public final String newOwner;
    public final String newName;
    public final String newDesc;
    public final boolean newItf;
    /** For reads, from the new value to the old type; for writes, from the old value to the new type. */
    public final Conversion conversion;
    /** Stack slots to discard when a setter returns a value. */
    public final int popSize;

    public AccessorBridge(String callerClass, String callerName, String callerDesc, int opcode, String owner, String name,
                          String desc, boolean itf, int newOpcode, String newOwner, String newName, String newDesc,
                          boolean newItf, Conversion conversion, int popSize) {
        this.callerClass = callerClass;
        this.callerName = callerName;
        this.callerDesc = callerDesc;
        this.opcode = opcode;
        this.owner = owner;
        this.name = name;
        this.desc = desc;
        this.itf = itf;
        this.newOpcode = newOpcode;
        this.newOwner = newOwner;
        this.newName = newName;
        this.newDesc = newDesc;
        this.newItf = newItf;
        this.conversion = conversion;
        this.popSize = popSize;
    }

    boolean isFieldSite() {
        return opcode >= Opcodes.GETSTATIC && opcode <= Opcodes.PUTFIELD;
    }

    /** Whether the value flows from the dependency to the caller. */
    boolean isRead() {
        return opcode == Opcodes.GETSTATIC || opcode == Opcodes.GETFIELD || !isFieldSite();
    }

    @Override
    public String targetClass() {
        return callerClass;
    }

    @Override
    public String describe() {
        StringBuilder sb = new StringBuilder(Bridge.dotted(callerClass)).append('.').append(callerName).append(": ");
        if (isFieldSite()) {
            sb.append(isRead() ? "reads field " : "sets field ").append(Bridge.dotted(owner)).append('.').append(name)
                    .append(" through ").append(newName).append("()");
        } else {
            sb.append("calls ").append(Bridge.dotted(owner)).append('.').append(name).append(desc)
                    .append(" as a read of field ").append(newName);
        }
        if (!conversion.isIdentity()) {
            sb.append(" (").append(conversion.label()).append(')');
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return describe();
    }
}

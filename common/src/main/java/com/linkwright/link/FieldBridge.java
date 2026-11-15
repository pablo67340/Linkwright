package com.linkwright.link;

/** A rewrite of every access to a field whose type changed, inside one method. */
public final class FieldBridge implements Patch {
    public final String callerClass;
    public final String callerName;
    public final String callerDesc;
    public final int opcode;
    public final String owner;
    public final String name;
    public final String oldDesc;
    public final String newDesc;
    /** For reads, from the new type to the old; for writes, from the old to the new. */
    public final Conversion conversion;

    public FieldBridge(String callerClass, String callerName, String callerDesc, int opcode, String owner, String name,
                       String oldDesc, String newDesc, Conversion conversion) {
        this.callerClass = callerClass;
        this.callerName = callerName;
        this.callerDesc = callerDesc;
        this.opcode = opcode;
        this.owner = owner;
        this.name = name;
        this.oldDesc = oldDesc;
        this.newDesc = newDesc;
        this.conversion = conversion;
    }

    @Override
    public String targetClass() {
        return callerClass;
    }

    @Override
    public String describe() {
        return Bridge.dotted(callerClass) + "." + callerName + ": field " + Bridge.dotted(owner) + "." + name
                + " is now " + newDesc + " instead of " + oldDesc + " (" + conversion.label() + ")";
    }

    @Override
    public String toString() {
        return describe();
    }
}

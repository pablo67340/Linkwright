package com.linkwright.link;

/**
 * A rewrite of every access to a field whose type changed, inside one method.
 *
 * @param conversion for reads, from the new type to the old; for writes, from the old to the new
 */
public record FieldBridge(String callerClass, String callerName, String callerDesc, int opcode, String owner,
                          String name, String oldDesc, String newDesc, Conversion conversion) implements Patch {
    @Override
    public String targetClass() {
        return callerClass;
    }

    @Override
    public String describe() {
        return Patch.dotted(callerClass) + "." + callerName + ": field " + Patch.dotted(owner) + "." + name
                + " is now " + newDesc + " instead of " + oldDesc + " (" + conversion.label() + ")";
    }
}

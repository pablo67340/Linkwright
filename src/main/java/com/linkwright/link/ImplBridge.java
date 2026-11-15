package com.linkwright.link;

import java.util.List;

/**
 * Adds a method the dependency now requires (an interface or abstract method whose signature
 * changed) to a class of the dependent mod, delegating to the version the class already has.
 *
 * @param paramConversions one per delegate parameter, from the required parameter at the same
 *                         position; required parameters past the delegate's count are dropped
 * @param returnConversion from the delegate's return type to the required one; unused when the
 *                         required method returns {@code void}
 */
public record ImplBridge(String targetClass, String name, String desc, String delegateName, String delegateDesc,
                         List<Conversion> paramConversions, Conversion returnConversion, String requiredBy) implements Patch {
    @Override
    public String describe() {
        StringBuilder sb = new StringBuilder(Patch.dotted(targetClass)).append(": adds ").append(name).append(desc)
                .append(" for ").append(Patch.dotted(requiredBy)).append(", delegating to ")
                .append(delegateName).append(delegateDesc);
        StringBuilder notes = new StringBuilder();
        for (int i = 0; i < paramConversions.size(); i++) {
            if (!paramConversions.get(i).isIdentity()) {
                notes.append(notes.isEmpty() ? "" : "; ").append("argument ").append(i + 1).append(" via ")
                        .append(paramConversions.get(i).label());
            }
        }
        if (returnConversion != null && !returnConversion.isIdentity()) {
            notes.append(notes.isEmpty() ? "" : "; ").append("result via ").append(returnConversion.label());
        }
        if (!notes.isEmpty()) {
            sb.append(" (").append(notes).append(')');
        }
        return sb.toString();
    }
}

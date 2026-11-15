package com.linkwright.link;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Adds a method the dependency now requires (an interface or abstract method whose signature
 * changed) to a class of the dependent mod, delegating to the version the class already has.
 */
public final class ImplBridge implements Patch {
    public final String targetClass;
    public final String name;
    public final String desc;
    public final String delegateName;
    public final String delegateDesc;
    /**
     * One per delegate parameter, from the required parameter at the same position; required
     * parameters past the delegate's count are dropped.
     */
    public final List<Conversion> paramConversions;
    /** From the delegate's return type to the required one; unused when the required method returns void. */
    public final Conversion returnConversion;
    public final String requiredBy;

    public ImplBridge(String targetClass, String name, String desc, String delegateName, String delegateDesc,
                      List<Conversion> paramConversions, Conversion returnConversion, String requiredBy) {
        this.targetClass = targetClass;
        this.name = name;
        this.desc = desc;
        this.delegateName = delegateName;
        this.delegateDesc = delegateDesc;
        this.paramConversions = Collections.unmodifiableList(new ArrayList<>(paramConversions));
        this.returnConversion = returnConversion;
        this.requiredBy = requiredBy;
    }

    @Override
    public String targetClass() {
        return targetClass;
    }

    @Override
    public String describe() {
        StringBuilder sb = new StringBuilder(Bridge.dotted(targetClass)).append(": adds ").append(name).append(desc)
                .append(" for ").append(Bridge.dotted(requiredBy)).append(", delegating to ")
                .append(delegateName).append(delegateDesc);
        List<String> notes = new ArrayList<>();
        for (int i = 0; i < paramConversions.size(); i++) {
            if (!paramConversions.get(i).isIdentity()) {
                notes.add("argument " + (i + 1) + " via " + paramConversions.get(i).label());
            }
        }
        if (returnConversion != null && !returnConversion.isIdentity()) {
            notes.add("result via " + returnConversion.label());
        }
        if (!notes.isEmpty()) {
            sb.append(" (").append(Bridge.join(notes)).append(')');
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return describe();
    }
}

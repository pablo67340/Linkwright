package com.linkwright.link;

/**
 * A change Linkwright makes to one class of the dependent mod as it loads: a {@link Bridge},
 * {@link FieldBridge} or {@link ImplBridge}.
 */
public interface Patch {
    /** Internal name of the class this patch applies to. */
    String targetClass();

    String describe();
}

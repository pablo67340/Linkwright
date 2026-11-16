package com.linkwright.link;

import com.linkwright.LwLog;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Linkwright's patches grouped by the class they apply to, for whichever hook the loader offers. */
public final class PatchSet {
    private final Map<String, List<Patch>> byClass = new HashMap<String, List<Patch>>();
    private final Set<String> logged = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    public PatchSet(List<? extends Patch> patches) {
        for (Patch patch : patches) {
            List<Patch> list = byClass.get(patch.targetClass());
            if (list == null) {
                list = new ArrayList<Patch>();
                byClass.put(patch.targetClass(), list);
            }
            list.add(patch);
        }
    }

    public boolean isEmpty() {
        return byClass.isEmpty();
    }

    /** Internal names, such as {@code com/example/Foo}. */
    public Set<String> targetClasses() {
        return Collections.unmodifiableSet(byClass.keySet());
    }

    public boolean targets(String internalName) {
        return byClass.containsKey(internalName);
    }

    /** Returns how many spots were adapted; 0 when the class has no patches. */
    public int apply(ClassNode node) {
        List<Patch> patches = byClass.get(node.name);
        if (patches == null) {
            return 0;
        }
        int patched = BridgePatcher.apply(node, patches);
        // Mixin and some loaders read a class through the transformers more than once
        if (logged.add(node.name)) {
            LwLog.info("Linkwright adapted " + patched + " spot(s) in " + node.name.replace('/', '.'));
        }
        return patched;
    }
}

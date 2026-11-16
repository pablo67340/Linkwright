package com.linkwright.link;

import com.linkwright.LwLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
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

    /**
     * Patches raw class bytes, for hooks that see bytes rather than a tree (LaunchWrapper, Java
     * agents). Returns {@code null} when nothing changed. Frames are kept as they are, which the
     * straight-line patches allow; maxima are recomputed.
     */
    public byte[] apply(String internalName, byte[] bytes) {
        if (!byClass.containsKey(internalName)) {
            return null;
        }
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        if (apply(node) == 0) {
            return null;
        }
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
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

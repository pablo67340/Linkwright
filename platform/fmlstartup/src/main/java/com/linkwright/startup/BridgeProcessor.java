package com.linkwright.startup;

import com.linkwright.LwLog;
import com.linkwright.link.BridgePatcher;
import com.linkwright.link.Patch;
import net.neoforged.neoforgespi.transformation.ClassProcessor;
import net.neoforged.neoforgespi.transformation.ClassProcessorIds;
import net.neoforged.neoforgespi.transformation.ProcessorName;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies Linkwright's patches on FML 10 and later. FML creates class processors after mod
 * discovery, by which time {@link LinkwrightLocator} has its result.
 */
public final class BridgeProcessor implements ClassProcessor {
    private static final ProcessorName NAME = new ProcessorName("linkwright", "bridge");

    private final Map<String, List<Patch>> byClass = new HashMap<>();
    private final Set<String> logged = ConcurrentHashMap.newKeySet();

    public BridgeProcessor() {
        for (Patch patch : LinkwrightLocator.result.patches) {
            byClass.computeIfAbsent(patch.targetClass(), k -> new ArrayList<>()).add(patch);
        }
    }

    @Override
    public ProcessorName name() {
        return NAME;
    }

    /** Before Mixin, as on ModLauncher, so mixins into a patched class see the adapted code. */
    @Override
    public Set<ProcessorName> runsBefore() {
        return Collections.singleton(ClassProcessorIds.MIXIN);
    }

    @Override
    public boolean handlesClass(SelectionContext context) {
        return !context.empty() && byClass.containsKey(context.type().getInternalName());
    }

    @Override
    public ComputeFlags processClass(TransformationContext context) {
        List<Patch> patches = byClass.get(context.type().getInternalName());
        if (patches == null) {
            return ComputeFlags.NO_REWRITE;
        }
        int patched = BridgePatcher.apply(context.node(), patches);
        String name = context.type().getClassName();
        if (logged.add(name)) {
            LwLog.info("Linkwright adapted " + patched + " spot(s) in " + name);
        }
        return patched > 0 ? ComputeFlags.COMPUTE_FRAMES : ComputeFlags.NO_REWRITE;
    }
}

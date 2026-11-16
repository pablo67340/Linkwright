package com.linkwright.startup;

import com.linkwright.link.PatchSet;
import net.neoforged.neoforgespi.transformation.ClassProcessor;
import net.neoforged.neoforgespi.transformation.ClassProcessorIds;
import net.neoforged.neoforgespi.transformation.ProcessorName;

import java.util.Collections;
import java.util.Set;

/**
 * Applies Linkwright's patches on FML 10 and later. FML creates class processors after mod
 * discovery, by which time {@link LinkwrightLocator} has its result.
 */
public final class BridgeProcessor implements ClassProcessor {
    private static final ProcessorName NAME = new ProcessorName("linkwright", "bridge");

    private final PatchSet patches = new PatchSet(LinkwrightLocator.result.patches);

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
        return !context.empty() && patches.targets(context.type().getInternalName());
    }

    @Override
    public ComputeFlags processClass(TransformationContext context) {
        return patches.apply(context.node()) > 0 ? ComputeFlags.COMPUTE_FRAMES : ComputeFlags.NO_REWRITE;
    }
}

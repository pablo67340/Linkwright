package com.linkwright.launch;

import com.linkwright.link.Patch;
import com.linkwright.link.PatchSet;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TargetType;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import org.objectweb.asm.tree.ClassNode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Applies Linkwright's patches to the dependent mods' classes as they load.
 *
 * <p>ModLauncher 9 and 10 tell what a transformer targets from the type argument of its
 * {@code ITransformer} interface, so this must be a real {@code ITransformer<ClassNode>}.
 * ModLauncher 11 asks {@link #getTargetType()} instead; older versions never call it, so its
 * reference to a class they lack is never resolved.
 */
final class BridgeTransformer implements ITransformer<ClassNode> {
    private final PatchSet patches;

    BridgeTransformer(List<Patch> patches) {
        this.patches = new PatchSet(patches);
    }

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        patches.apply(input);
        return input;
    }

    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
        return TransformerVoteResult.YES;
    }

    @Override
    public Set<Target<ClassNode>> targets() {
        Set<Target<ClassNode>> targets = new HashSet<>();
        for (String name : patches.targetClasses()) {
            targets.add(Target.targetClass(name.replace('/', '.')));
        }
        return targets;
    }

    @Override
    public TargetType<ClassNode> getTargetType() {
        return TargetType.CLASS;
    }

    @Override
    public String[] labels() {
        return new String[]{"linkwright"};
    }
}

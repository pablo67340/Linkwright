package com.linkwright.oldml;

import com.linkwright.link.Patch;
import com.linkwright.link.PatchSet;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import org.objectweb.asm.tree.ClassNode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Applies Linkwright's patches as classes load. ModLauncher 2 to 8 tell what a transformer
 * targets from the type argument of the {@code ITransformer} interface its class implements
 * directly.
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
    public Set<Target> targets() {
        Set<Target> targets = new HashSet<Target>();
        for (String name : patches.targetClasses()) {
            targets.add(Target.targetClass(name.replace('/', '.')));
        }
        return targets;
    }

    @Override
    public String[] labels() {
        return new String[]{"linkwright"};
    }
}

package com.linkwright.launch;

import com.linkwright.LwLog;
import com.linkwright.link.Bridge;
import com.linkwright.link.BridgePatcher;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TargetType;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Rewrites bridged call sites as their classes load. */
final class BridgeTransformer implements ITransformer<ClassNode> {
    private final Map<String, List<Bridge>> byClass = new HashMap<>();
    private final Set<String> logged = ConcurrentHashMap.newKeySet();

    BridgeTransformer(List<Bridge> bridges) {
        for (Bridge bridge : bridges) {
            byClass.computeIfAbsent(bridge.callerClass(), k -> new ArrayList<>()).add(bridge);
        }
    }

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        List<Bridge> bridges = byClass.get(input.name);
        if (bridges != null) {
            int patched = BridgePatcher.apply(input, bridges);
            // Mixin reads target classes through the transformers before they really load
            if (logged.add(input.name)) {
                LwLog.info("Linkwright bridged " + patched + " call site(s) in " + input.name.replace('/', '.'));
            }
        }
        return input;
    }

    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
        return TransformerVoteResult.YES;
    }

    @Override
    public Set<Target<ClassNode>> targets() {
        return byClass.keySet().stream()
                .map(name -> Target.targetClass(name.replace('/', '.')))
                .collect(Collectors.toSet());
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

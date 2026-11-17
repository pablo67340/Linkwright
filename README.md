<p align="center">
  <img src="branding/banner.png" alt="Linkwright" width="820">
</p>

<p align="center">
  <b>Dependency reducer and link patcher for NeoForge 1.21.1.</b><br>
  Lets your mods load when a version rule is stricter than the code it protects, and patches the small
  changes in between until the authors catch up.
</p>

<p align="center">
  <img alt="Minecraft 1.21.1" src="https://img.shields.io/badge/Minecraft-1.21.1-62B47A">
  <img alt="NeoForge 21.1" src="https://img.shields.io/badge/NeoForge-21.1-E68C37">
  <img alt="Side: client or server" src="https://img.shields.io/badge/side-client%20%7C%20server-2E8B9A">
  <img alt="License: MIT" src="https://img.shields.io/badge/license-MIT-blue">
</p>

---

Mods often pin the exact version range of another mod they integrate with. When that other mod
updates, NeoForge refuses to start ("Mod X requires Y 1.3.0 or above, and below 1.4.0"), even when
the code the first mod actually uses is unchanged, or changed in a way that is trivial to bridge.
Linkwright checks the real code links between the two mods, lets the game launch when they line
up, and patches the small differences in memory as the classes load.

Nothing is written to any mod's jar. The patches exist only while the game runs, so once the
dependent mod updates, Linkwright simply has nothing left to do for it.

## What it does

On every launch, before NeoForge checks dependency versions, Linkwright:

1. Reads every mod's `neoforge.mods.toml` (including mods nested in other jars) and finds the
   version rules the installed mods break: on each other, and on a loader newer than they allow
   (see [Loader version rules](#loader-version-rules)). Rules about Minecraft itself are never
   touched, and neither are `incompatible` or `discouraged` rules.
2. For each broken rule, walks the dependent mod's bytecode and resolves every class, method and
   field it uses in the dependency, through inheritance into Minecraft and NeoForge. It also
   checks the dependent mod's mixins into the dependency (target classes, injector selectors,
   `@At` targets, shadows, overwrites, accessors and invokers), that classes extending the
   dependency still implement its abstract methods, and that every member is still accessible
   and still static (or not) the way the dependent mod calls it.
3. When something the dependent mod uses changed shape, looks for exactly one safe way to adapt
   it, and patches the dependent mod's class as it loads (see [Adapters](#adapters)). If more than
   one adaptation fits, or none does, it is left as a problem rather than guessed at.
4. Patches every adapted class in memory and verifies the result before approving anything (see
   [Verification](#verification)).
5. If every link resolves or is adapted, it adds the rule to NeoForge's dependency overrides in
   memory, so the game keeps launching. Otherwise NeoForge's error stays, and the report says
   exactly what is missing.

A full pack of 300+ mods is analyzed in about a second. Everything is written to
`logs/linkwright-report.txt`, including a ready-made `dependencyOverrides` line for packs that
want to ship the approval without Linkwright.

### Examples

**CBC Aeronautics Missiles 0.2.0** pins Create: Radars to `[0.4.9.4, 0.5.0)`. Radars 5 changed
`AutoTargetingHelper.isInSafeZone(Vec3, List)` to `isInSafeZone(Vec3, List, Level)`. Linkwright
verifies the 124 other links, then adapts both calls: one passes the `ServerLevel` the calling
method already has, the other passes `NetworkView.controllerLevel()`.

**Power Grid 0.6.2** pins TFMG to `[1.3.0, 1.4.0)`, and TFMG 1.2.0 stores block positions as packed
`long`s where 1.3 uses `BlockPos`. Linkwright converts the `BlockPos` Power Grid passes to
`new ElectricBlockValues(...)` with `asLong()`, and gives Power Grid's connector the
`long getPos()` that TFMG 1.2's `IElectric` interface requires, by delegating to its
`BlockPos getPos()`.

From the report:

```
powergrid 0.6.2  ->  tfmg 1.2.0
  Rule:     powergrid asks for tfmg [1.3.0,1.4.0), installed is 1.2.0
  Decision: approved: every link checks out
  Checked:  15 links
  Adapted:  2 change(s)
    - ...TFMGCompatDeviceConnectorBlockEntity.<init>: calls ...ElectricBlockValues.<init>(BlockPos) as (J)V (argument 1 via BlockPos.asLong())
    - ...TFMGCompatDeviceConnectorBlockEntity: adds getPos()J for ...IElectric, delegating to getPos()BlockPos (result via BlockPos.asLong())
  Problems: none
```

### Loader version rules

Some mods pin the loader itself, often by accident: `versionRange="[21.1.243]"` on `neoforge`
accepts exactly one NeoForge build, so the mod refuses every newer one. When the installed
NeoForge, Forge or Fabric Loader is newer than such a rule allows, Linkwright treats the loader
like any other dependency. It checks every class, method, field and mixin target the mod uses in
the loader's own jar, and approves the rule only when all of them still resolve.

A mod that needs a newer loader than the one installed is left alone, since updating the loader
is the fix.

### Warnings

Two problems Linkwright cannot fix, but points out in the log and at the top of the report before
they stop the game:

- A jar built for a newer Java than the game runs on, which would otherwise crash with an
  `UnsupportedClassVersionError` once it loads.
- The same mod in the mods folder more than once, usually an old version left behind by an update.

## Adapters

| Change in the dependency | What Linkwright does |
| --- | --- |
| A method or constructor gained parameters, anywhere in the list | Keeps the old arguments in order and fills each new object parameter from the calling method: one of its parameters, or one public getter on a parameter |
| A parameter, return or field type changed | Converts the value at the call site, if the conversion is lossless (below) |
| A method now returns something where it returned `void` | Discards the result |
| An interface or abstract class requires a method with different types | Adds that method to the dependent mod's class, delegating to its existing method of the same name |
| A value is now wrapped in a `Supplier`, `Optional` or Minecraft `Holder` | Unwraps it where it is used (`get()`, `orElse(null)` or the holder's value), only when the generic types prove the wrapped value is the old type |
| A public field was replaced by a getter or setter, or the other way around | Reads through the getter (`getX()`, `isX()` or `x()`) and writes through the setter, or uses the field directly |
| A getter was renamed record-style, such as `getName()` to `name()`, or back | Calls the new name, and renames the dependent mod's own implementations that the dependency calls |
| A method or constructor lost parameters | Drops the arguments that are no longer needed, when only one choice of which ones fits |
| A class moved to another package in the same mod | Points every reference at the new package, unless a mixin, a string or a resource file mentions the old name |
| A method became static, or stopped being static | Drops the unused receiver, passes the first argument as the receiver or the receiver as the first argument, or calls the class's single shared instance |
| A class became an interface, or the other way around | Switches the call instruction to match |

Lossless conversions: subtype to supertype, primitive widening (`int` to `long`, `float` to
`double` and so on), boxing and unboxing, `BlockPos` and `long`, `ChunkPos` and `long`,
`ResourceLocation` and `String`, and `Vec3i` to `BlockPos`.

## Verification

An adapter is only used once it has been proven safe. Before a rule is approved, Linkwright
patches each affected class in memory, without loading it, and runs it through ASM's bytecode
verifier with the types taken from the installed mods. Every method that verified before must
still verify, and every stack map frame must still match. The patched class is then link checked
again from scratch. If any of this fails, the rule is refused and the report says which class
failed and why.

On a game whose ASM has no bytecode verifier, rules that need adapters are refused, while rules
that need none are still approved. The Forge 1.13 to 1.16.5 jar carries ASM's analysis package
for this reason, since Forge 1.13.2 and 1.14.4 do not put it on the classpath.

## What it will not do

- **Override intentional incompatibilities.** If a mod deliberately refuses to load next to
  another (an `incompatible` rule, or a check in its own code), that is the author's decision and
  Linkwright leaves it alone.
- **Guess.** Narrowing conversions (`long` to `int`, supertype to subtype) are never made, added
  `int`, `float` and other primitive parameters are never filled in, and a change with more than
  one possible adaptation is reported instead of patched.
- **Check behaviour.** A method that keeps its signature but changes what it does still passes.
- **Follow arbitrary renames.** Apart from the getter styles above, a removed method with no
  same-named replacement cannot be adapted. A moved class is followed only when it keeps its
  name, and only inside the dependency's own package tree.

Links that lead into libraries Linkwright cannot see (for example DataFixerUpper or fastutil) are
assumed present and counted separately in the report. Calls made from mixin classes, or through
method handles, can be verified but not adapted.

## Installing

Drop the jar into `mods/` on the client, the server, or both. Linkwright runs as a ModLauncher
service, so it does not appear in the in-game mod list and never has to match between client and
server. Each side checks its own mods.

Remember that client and server still need matching versions of the mods themselves to connect.
Linkwright fixes version rules between mods, not differences between your client and a server.

## Configuration

`config/linkwright.toml` is created on first launch:

| Key | Default | Meaning |
| --- | --- | --- |
| `mode` | `"auto"` | `auto` approves verified rules and applies adapters; `report` only writes the report; `off` does nothing |
| `bridgeCalls` | `true` | Apply adapters. When `false`, any changed link is a problem |
| `ignore` | `[]` | Rules to leave alone, as `"dependent->dependency"`, such as `"somemod->create"` or `"othermod->neoforge"` |
| `forceApprove` | `[]` | Rules to approve even when problems are found. This can crash the game |

## Building

Requires JDK 21.

```
./gradlew build
```

The jar is `build/libs/linkwright-1.21.1-neoforge-<version>.jar`. To dry-run the analysis
against an existing instance without changing it:

```
./gradlew test --tests com.linkwright.PackScanTest -Plinkwright.testGameDir=<instance folder>
```

## License

[MIT](LICENSE)

# SDD ledger — plan: docs/superpowers/plans/2026-10-23-backroom-roguelike.md

## Setup
- Native execution approved by user on 2026-10-23.
- Project has no .git directory; isolated worktree/commit-based task scripts are unavailable. Ruling: work directly in the explicitly authorized main project and preserve unrelated files — this checkout has no VCS metadata to isolate or restore.
- Higher-priority developer instruction forbids adding or running tests unless the user asks. Ruling: do not add/run tests despite the TDD subskill; use the plan's resource audits and compile/resource-processing checks only.
- No nbt-structure MCP tools are available in this session. Ruling: use a Python standard-library NBT reader/writer in the planned conversion utility; do not add dependencies.
- Latest user decisions supersede prior bridge plan: no KubeJS dimension or transition compatibility; canonical dimension is `backroomroguelike:backroom`; one bedrock boundary at minY and rooms anchored to computed surface.

## Preflight interfaces
- Task 1 → Tasks 4–6: MOD_ID, mod bus and registries; plan interface names are consistent.
- Task 2 → Tasks 4 and 6: generated NBT resources and room IDs; resource folder root is consistent.
- Task 3 ↔ Task 4: canonical dimension generator codec must match the custom registered codec; verify as a single dimension resource integration.
- Task 4 → Tasks 5–6: coordinate selector and player-region origin; locator and entry must use the same coordinate calculation.

## Tasks
- Task 1: in progress
- Task 2: pending
- Task 3: pending
- Task 4: pending
- Task 5: pending
- Task 6: pending
- Task 7: pending

- Task 2 preflight ruling: the primary project contains only the 20 large templates; all 39 locks are in the user-specified KubeJS reference data folder. Copied these inputs unchanged to structures/locks/ inside the authorized primary project; did not edit the reference project. Update the plan input file map accordingly.

- Task 2 metadata ruling: lock NBT inspection found 39 distinct sherd item IDs from Minecraft, Caverns & Chasms, Atmospheric, NetherExp, and Windswept. Added those three additional mod requirements to mods.toml so Create placard item stacks retain resolvable registry IDs; no Gradle artifacts/dependencies added.

## Completion evidence
- Tasks 1–7 implemented in the primary project; plan checkboxes marked complete.
- compileJava processResources -x test succeeded after adapting to Forge 1.20.1 ChunkGenerator/SavedData APIs; jar -x test and eobfJar succeeded.
- Final jar uild/libs/backroomroguelike-1.0.0.jar contains 58 room NBTs (18 root + 40 password), canonical dimension and type JSON, extraction assets, and mods.toml.
- NBT audit: all 58 templates are 16x16x16, no jigsaw/custom rainbow/embedded bedrock remains; 39 output Item compounds exactly match source lock Item compounds and 39 pottery IDs remain distinct; 39 Create placards and 1 extraction block found.
- JSON resources parse, processed mods.toml parses; source package has no example path; runtime/gameplay validation was not performed.
- Old source templates are read-only inputs; KubeJS reference checkout was not modified.
- Room base templates contained minecraft:bedrock across y=6..15; those temporary fill blocks are removed from runtime rooms so the requested single minY boundary remains and surface-height rooms are not sealed.



- Final jar rebuilt after the last source change and inspected: uild/libs/backroomroguelike-1.0.0.jar contains 58 room NBT files (18 root + 40 password), canonical dimension JSON, and mods.toml.
- Edge audit checked all 58 templates at y=1..4: each north/east/south/west boundary retains at least one 1-wide, 4-high open column.
- jar -x test and reobfuscation succeeded; no test task was executed.


# AGENTS.md

## Project overview

Ciacola is a Compose Desktop presentation deck built on
[Storyboard](https://github.com/bnorm/storyboard). It contains a talk-specific `deck` application,
the retained Storyboard modules and examples, and focused hardening of Storyboard's MagicText.

The repository is an experimental deck, not a general presentation framework. Keep visible talk
copy provisional unless the user explicitly asks to develop the narrative.

If these instructions and the checked-in code or documentation disagree, treat the code and
project documentation as the current source of truth.

## Source-of-truth documents

| Document | Use it for |
|---|---|
| [README.md](README.md) | Purpose, setup, project map, checks, and attribution |
| [docs/DECK-FOUNDATION.md](docs/DECK-FOUNDATION.md) | Deck architecture, design system, source notes, and verification record |
| [docs/MAGIC-TEXT-V2-SEAM.md](docs/MAGIC-TEXT-V2-SEAM.md) | Boundary and fixtures for a possible future text renderer |

## Non-negotiables

### Scope discipline

- Keep changes scoped to the user's request.
- Treat unrequested visual, narrative, or navigation changes as regressions.
- Do not implement MagicText v2 unless the user explicitly asks for it.
- Preserve the `DeckTextRenderer` boundary so scenes do not select text engines directly.

### Upstream and licensing

- Ciacola is derived from Storyboard revision
  `499cda890a7591c8fc56044726531f6f38a82e35` and retains its Git history.
- Preserve the Apache License 2.0 text in `LICENSE.txt` and the attribution in `NOTICE`.
- Keep the `upstream` Git remote pointed at `https://github.com/bnorm/storyboard.git`.
- When adapting upstream files, keep the change clear in Git history and avoid erasing attribution.

### Worktree policy

Before writing tracked files from a clean `main` checkout, check whether isolation already exists.
If already inside a worktree or on a non-main branch, continue there. Otherwise, ask whether to
create a worktree before feature work. Plans belong in `.plans/`, which should remain ignored.

Use the local `using-git-worktree` skill when setting up an isolated workspace.

### Verification

Use targeted tasks while iterating, then run the broadest relevant gate before handoff.

```bash
./gradlew :storyboard-text:jvmTest :deck:desktopTest
./gradlew check --no-parallel -Pkotlin.daemon.jvmargs=-Xmx4g -x wasmJsBrowserTest
```

The inherited browser tests require ChromeHeadless. Excluding `wasmJsBrowserTest` is the supported
non-browser gate when Chrome is unavailable. For visual or runtime changes, also launch the deck
and exercise forward and backward navigation. `:deck:run` auto-provisions JetBrains Runtime 25.

Before every push, the relevant checks must pass and `git diff --check` must be clean. After a
rebase, verify that the key changes survived before pushing.

## Actions requiring explicit user approval

Do not perform these without clear instruction in the current task:

- opening, merging, or closing a pull request;
- pushing a branch;
- destructive Git history changes or deleting branches; or
- committing unrelated changes outside the task's scope.

## Working style

- Prefer repository-local documentation and checked-out upstream source over memory.
- Use `rg` and `rg --files` for repository searches.
- Preserve user changes in a dirty worktree.
- Check `.agents/skills/` before recreating a documented workflow.

## Build and run

```bash
./gradlew :deck:run
./gradlew :storyboard-text:jvmTest :deck:desktopTest
./gradlew check --no-parallel -Pkotlin.daemon.jvmargs=-Xmx4g -x wasmJsBrowserTest
```

## Local skills

- `using-git-worktree` — create isolated worktrees without disturbing the active checkout.
- `babysit-pr` — monitor a pull request through CI and review feedback under explicit mutation
  limits.

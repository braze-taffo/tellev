# PR #11 / #12 integration

## Baselines

- Mainline: `9f2f19f553772e668cf08502f3723e46974006e2` (1.7.1.6).
- PR #11: `e6a8318490e92d1d2861723093430b77957d63fd`, based on 1.7.1.2.
- PR #12: `6b27c58a2ced7333b1e6c5a74f6399a2c53c54a2`, separate UI rebuild lineage.
- Integration runs in an isolated worktree; the local uncommitted creation changes remain outside this integration.

## PR #11

Select only the script-closing escape correction from the author's patch. `Regex.replace` interprets the replacement's backslash; `Regex.escapeReplacement` preserves the intended JavaScript escape. This does not introduce new script consent, network policy, prompt semantics or storage behavior.

- Reproduction against mainline: `ScriptSourceEscapingTest`, 3 cases, 2 failures.
- After selected correction: the same 3 cases pass, including decoded string value and untouched ordinary JavaScript.
- Merge history retains the original PR head as a parent; the resulting tree includes only this correction and its validation.

## PR #12

In progress: selectively integrate DSH presentation components with a persisted Classic / DSH setting, retaining the current mainline generation and renderer.

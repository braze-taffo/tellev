# Android channel boundaries

Before editing or building, run `git branch --show-current` and `git worktree list`.
Directory names do not identify the release channel. The two worktrees share Git history but have separate working files.

| Branch | Application ID | Signing properties | Chat image engines |
| --- | --- | --- | --- |
| `master` | `app.tellev` | `tellevStoreFile`, `tellevStorePassword`, `tellevKeyAlias`, `tellevKeyPassword` | ComfyUI, NovelAI |
| `mnn-image-gen` | `app.tellev.mnn` | `tellevMnnStoreFile`, `tellevMnnStorePassword`, `tellevMnnKeyAlias`, `tellevMnnKeyPassword` | Local Dream MNN, ComfyUI, NovelAI |

- The official channel has no on-device image inference. Do not bring Local Dream code, native libraries, conversion assets, foreground services or model management into `master` when porting shared image fixes.
- Port shared fixes selectively. Preserve the different engine lists, package IDs, signatures, app names and update channels.
- Official updates use ordinary releases; the MNN app uses `-mnn` releases and its own APK. Do not interchange their artifacts.
- Update checks are channel-locked by both the tag suffix and the APK asset name (`vX.Y.Z` + `tellev-X.Y.Z.apk` on `master`; `vX.Y.Z-mnn` + `tellev-X.Y.Z-mnn.apk` on `mnn-image-gen`), driven by one `CHANNEL` constant in `core/update/UpdateChecker.kt`. Keep those names when publishing: a tag or asset that does not follow the convention is invisible to the app, and a wrong suffix would let one channel surface the other's build.
- A release build or ADB install request does not authorize a commit, push, tag or GitHub Release.
- Before delivering an APK, verify its manifest, signature and ZIP contents. Official APKs must contain neither `libstable_diffusion_core.so` nor `assets/ldcvt/`; MNN APKs must contain the core and conversion assets.
- Never infer device image-generation acceptance from unit tests or a successful build.

# Mandatory release update guide

- Every release, including patch releases, must update `app/src/main/assets/guide/update/{zh-CN,en,ja,ko}.md` together with the version and release notes.
- Describe the actual shipped changes, their UI entry points and usage steps. Do not only change the version marker while leaving an old release's guide text in place.
- Each language must include `<!-- release-version: VERSION -->` matching the channel's `versionName`; the first page must visibly identify that version. Keep page counts and topics aligned across all four languages.
- `:app:verifyUpdateGuides` is a required release gate and a dependency of `preReleaseBuild`. Run the guide asset/parser tests and verify upgrade-first-launch display, paging, dismissal and one-time behavior before publication.
- If the release changes first-use steps, also update the onboarding guides. Keep release notes and guides consistent with the shipped UI and focused on usage.
- Do not publish a release with a missing, stale, malformed or unverified update guide. See `docs/UPDATE-GUIDE.md` for format and validation.

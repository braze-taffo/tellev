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

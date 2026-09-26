# 首次打开指引（更新指引 / 新手引导）

## 用途

`1.7.0.1` 是从 v1.6.x 一步跨到 v1.7.x 的大版本，改动很多。为此加了两份 App 内指引：

- **更新指引**：覆盖升级的用户在版本更新后首次打开时弹出，讲清「更新了什么、入口在哪、怎么用」。
- **新手引导**：全新安装的用户首次打开时弹出，四步走完「配模型服务 → 拿到角色 → 开始聊天 → 值得一试」。

两者都是「一次只弹一个」启动弹窗队列的第一位（队列实现见 `ui/TellevRoot.kt`），
并且都能从 **设置 → 关于** 里随时重看。

## 谁看到哪一份

判定是一条纯函数，见 `core/guide/GuideDecision.kt` 的 `decideStartupGuide`：

| 情况 | 结果 |
| --- | --- |
| 时间戳相等（全新安装）+ `characters/`、`chats/`、`group chats/`、`worlds/` 全空 + 没看过新手引导 | 新手引导 |
| 时间戳相等但已有用户数据（重装后还原了备份） | 更新指引 |
| 时间戳不等（覆盖升级）且上次提示的版本比当前版本旧 | 更新指引 |
| 上次提示过的版本 == 当前版本（或更新） | 不弹 |

要点：

- 「全新安装」沿用 `AppPreferences.shouldShowOnceAfterUpdate` 的时间戳口径（`lastUpdateTime <= firstInstallTime`）。
- 「有没有用户数据」只看那四个目录的枚举结果：`FileStDataStore.bootstrap()` 会预置默认预设与一个人设，
  所以**预设/人设数量不能**用来判断全新安装（`FileStDataStore.kt`）。
- 该磁盘检查是惰性的，只在「全新安装且没看过引导」那条分支上求值，并且在 IO 线程上跑。
- 看过之后写入 `tellev_prefs`：`update_guide_shown_version`（版本号）与 `onboarding_guide_shown`（布尔）。
  关掉新手引导的同时会把 guide 版本记为当前版本，免得新用户紧接着又看一次更新指引。

## 资产格式

正文是每语言一份 Markdown，放在 `app/src/main/assets/guide/` 下：

```
assets/guide/onboarding/{zh-CN,en,ja,ko}.md      # 4 页
assets/guide/update/{zh-CN,en,ja,ko}.md          # 7 页
```

格式刻意做成语言中立，译文可以自由翻译正文，解析器不去认会被翻译的标签：

```markdown
<!-- 文件头注释可以写格式说明，解析器会忽略 -->

## 页面标题

摘要段落（标题之后、第一个标记之前的所有非空行，可折多行，会拼成一句）。

<!-- where -->
- 入口写在这里（项目符号，`- ` 或 `* `）

<!-- steps -->
1. 用法步骤（`1. ` 或 `1) `）
2. 按顺序写
```

规则：

- `## ` 起一页；文件头与第一个 `## ` 之前的内容被忽略。
- `<!-- where -->`、`<!-- steps -->` 切换分节；`<!-- summary -->` 可写可不写（默认就在摘要段）。
- 「在哪找到」「怎么用」这类**界面标签来自 i18n**（`guide_label_where` / `guide_label_how`），不写在资产里。
- 每页必须有：标题、摘要、至少一条入口、至少一条步骤。缺任何一项解析就抛异常，
  `loadGuide()` 捕获后返回 null，弹窗显示「指引内容加载失败」——
  宁可是明确的失败提示，也不显示半截内容。
- 语言回退链：精确标签 → 主语言（`zh-*` 特判到 `zh-CN`）→ `en` → `zh-CN`。见 `languageFallbackCandidates`。

## 界面与文案

- 弹窗是 `feature/guide/GuideDialog.kt`：全屏 `Dialog` + `HorizontalPager` 分页，
  底部「第 n / N 页」+「上一步 / 下一步」，最后一页换成「开始使用」（新手引导）或「知道了」（更新指引）。
- 正文用原生 Compose 渲染（标题 / 摘要 / 入口 / 步骤），**没有用 WebView**：
  分页横滑会和 WebView 的横向手势抢，而且每页一个 WebView 的内存代价不值。
- 弹窗自身的文案走既有 i18n 流程（`_i18n/a12_guide.tsv` + `_i18n/translations.tsv`），
  改完照例跑 `python tools/i18n_consolidate.py`。

## 下个版本怎么加指引

1. 改 `assets/guide/update/<语言>.md`，把内容换成新版本的更新点（四个语言都要改）。
2. 什么都不用动：判定比的是「上次提示过的版本」与当前 `versionName`，
   只要版本号变了就会再弹一次。
3. 新增/删除页面时同步四个语言文件——`GuideFormatTest` 的资产守卫会检查
   四语言页数一致、每页三件套齐全，缺一个就红。
4. 如果某一版内容特别多，可以考虑给 `GuideKind` 增加一个并列的资产目录并在
   `decideStartupGuide` 之外挑选用哪份，目前只有 `Onboarding` / `Update` 两种。

## 验证

```bash
./gradlew :app:testDebugUnitTest --tests "app.tellev.core.guide.*"   # 判定矩阵 + 资产守卫
./gradlew :app:testDebugUnitTest                                     # 全量
./gradlew :app:assembleDebug                                         # 资产是否真进包
```

真机验收（弹出时机、分页手感、四语言观感）由用户亲手做。

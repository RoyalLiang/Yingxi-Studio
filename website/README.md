# 映汐官网

静态官网已通过视觉审阅，参考 Apple 产品页的版式与动效，使用映汐自己的图标、肖像和真实应用界面。源码、独立验证流程与 Cloudflare Pages 发布脚本均位于本仓库。正式地址为 https://yingxi-station.pages.dev ，实际部署状态见交付记录。

## 本地运行

```sh
node website/tools/check.mjs
PORT=48173 node website/tools/serve.mjs
```

预览无需安装依赖或构建。支持桌面、平板、手机与系统的减少动态效果偏好。主标题为「你的片库，自在放映」；免费与无广告只作为正文说明。

## Cloudflare 首次接入

以下命令在 `website/` 中执行。Wrangler 已锁定为 4.152.0，依赖由 `package-lock.json` 固定；凭据保留在 Cloudflare 官方登录与秘密管理流程中，不写入仓库。

```sh
npm ci --no-audit --no-fund
npx wrangler login --scopes account:read user:read pages:write
npx wrangler whoami
npx wrangler pages project list
```

先核对账号和已有项目。`yingxi-station` 已于 2026-10-24 在目标账号创建，生产分支为 `main`，无需重复创建。只有接入其他账号且确认没有对应项目时才创建：

```sh
npx wrangler pages project create yingxi-station --production-branch main
```

若 Wrangler 将首次创建转交 Workers 并提示缺少 Worker 入口，确认这是新建静态 Pages 项目后，按其官方提示仅在首次创建加 `--force`；项目存在后不再使用此参数。

本项目使用 Pages Direct Upload，可由本地或持续集成上传纯静态目录。它不绑定 Cloudflare 的 Git integration；[官方说明](https://developers.cloudflare.com/pages/get-started/direct-upload/)介绍了两种模式的区别。正式域名可以稍后绑定，先使用控制台实际返回的 `pages.dev` 域名。

## 元数据与发布

从 Cloudflare 返回的真实地址设置 canonical、Open Graph、robots 与 sitemap，然后提交这些文件。不要使用猜测的域名：

```sh
node tools/set-site-url.mjs --url https://实际返回的主机名
```

在已提交的隔离工作树中执行：

```sh
npm run deploy:preview
npm run deploy:production
```

默认项目名来自 `wrangler.jsonc`。需要变更时可设置 `CLOUDFLARE_PAGES_PROJECT_NAME`，或使用 `npm run deploy -- --target preview --project 实际项目名`。预览默认分支为 `design-preview`，正式发布分支为 `main`。脚本先执行静态检查，要求已提交的工作树，并把 Git SHA 绑定到部署；未设置真实站点地址时阻止正式发布。

发布后核对首页、支持、隐私、404、媒体加载、响应头与当前部署SHA。`_headers` 中的重新验证缓存策略允许稳定文件名随新部署更新，避免浏览器长期显示旧版截图。回滚通过 Cloudflare Pages 的已有成功部署办理，保留对应部署ID与源码SHA；不修改应用版本、签名或历史标签。

## 仓库工作流

`.github/workflows/website.yml` 在官网变更的 Push / PR 上核对源码、锚点、住民原文与媒体摘要，并保存静态产物。更新 `website/**` 或此工作流并推送到 `main` 后，校验通过即自动发布到 `https://yingxi-station.pages.dev`。PR 仅校验，不读取部署凭据；其他分支不会发布正式站点。

仍可从 `main` 手动运行 `workflow_dispatch`，选择 `preview` 或 `production`。发布任务串行执行且不取消正在进行的上传；正式上传前重新核对远端 `main` 的官网源码与发布配置，已有更新的官网内容时会跳过旧任务；仅 Android 等无关文件变化不阻断官网发布。

部署任务使用 GitHub 的 `website` environment；该环境只允许 `main` 分支。配置：

- Secret `CLOUDFLARE_ACCOUNT_ID`：目标账号ID。
- Secret `CLOUDFLARE_API_TOKEN`：限定目标账号、具备 Cloudflare Pages Edit / Pages Write 的专用令牌。该权限按账号覆盖 Pages 项目；工作流固定使用 `yingxi-station`。按用户确认，此专用令牌不设到期时间；需要轮换时先替换 environment secret 并验证新部署，再撤销旧令牌。令牌撤销会让后续部署任务失败，既有线上版本继续提供访问。
- 可选 Variable `CLOUDFLARE_PAGES_PROJECT_NAME`：实际项目名。

令牌在 Cloudflare 与 GitHub 的秘密管理界面配置，不提交到源码、不放在网页内，也不通过聊天传递。本地账号登录不替代 CI 专用令牌。[Cloudflare 持续集成说明](https://developers.cloudflare.com/pages/how-to/use-direct-upload-with-continuous-integration/)。

## 内容与维护

- `public/index.html`：播放器介绍、废土世界、下载与常见问题。
- `public/world/index.html`：完整世界档案，含纪元、六处地点、生命谱系、住民、故事边界与全部词条节录。由 `tools/sync-world.py` 从随包图谱生成；站内未知路线保持虚线。
- `public/assets/screenshots.json`：界面素材对应的应用版本、正式APK校验和及图片/录屏摘要。
- `public/assets/letters.json`：按稳定ID导出的中英住民短笺。
- `public/privacy/`：官网浏览隐私说明，尚非完整应用隐私政策。
- `public/support/`：安装、媒体来源、播放和反馈说明。

素材来自正式签名 APK 在 Android 16 ARM64 模拟器的实际运行；演示影片为本地原创生成，没有后期拼接应用界面。该证据不代表真机、网络提供方或全部解码格式验收。iOS 仍为开发预览，没有 App Store 下载入口。

住民短笺更新后，在仓库根目录运行：

```sh
python3 -B website/tools/sync-letters.py
python3 -B website/tools/sync-world.py
node website/tools/check.mjs --atlas app/src/main/res/raw/world_atlas.json
```

两条尚未随应用发布的日常短笺已使用独立稳定标识，保留 alpha.52 已发布的 `diary_026`。本轮官网落地不宣称新增短笺已包含在 alpha.52 APK 中。

中文字体是带 OFL 许可的 Noto Sans SC 本地变量字体子集。修改文字后，使用 `tools/subset-fonts.py --sans-source 本地字体路径` 重新裁剪；该工具需要 fontTools 与 Brotli，不向在线服务提交文案。


## 播放问题回执

Android 的「上报播放问题」直接在播放器内完成。弹窗说明公开字段，用户主动勾选同意后点一次「上报」；受限 WebView 在后台完成 Turnstile 校验与同源接收端 `functions/api/playback-report.js` 的提交。等待使用信号动画，收到真实仓库回执后显示透明对勾并自动收起；不显示原始 JSON、复制入口或人工校验控件。报告者无需 GitHub 登录或切换到外部浏览器。

`/report/submit`（源码 `public/report/submit.html`）只在原生按钮操作后接收冻结的快照，不从页面地址或 URL fragment 读取报告，也不因单独打开页面就提交。原生端不添加 JavaScript 原生对象接口，禁用文件/内容访问，只允许精确的 HTTPS 主页面；完成通知只接受当前请求 ID 和确认的正数编号。本机仅保存尚未确认尝试的 UUID，不保存诊断正文；关闭或进程中断后重开会先提示检查旧回执，用户明确开始新的上报才会重新生成请求。

`/report/` 仍是备用网页表单，也兼容旧客户端的 URL fragment 入口。fragment 不随 HTTP 请求发送，读取后立即清除；备用表单仍要求网页内明确同意和点击提交。浏览、复制、未勾选同意或通道未配置时不会自动发送报告。

在 Cloudflare **Workers & Pages → yingxi-station → Settings → Variables and Secrets → Production** 配置：

| 名称 | 类型 | 内容 |
| --- | --- | --- |
| `REPORT_GITHUB_TOKEN` | Secret | 有仓库权限的 GitHub 账号生成的细粒度 token；仅选择 RoyalLiang/Yingxi-Studio，Repository permissions 只开 Issues: Read and write（Metadata 只读为平台必需）。不用 Contents、Actions 或账号管理权限。 |
| `REPORT_TURNSTILE_SECRET` | Secret | Turnstile widget 的服务端 Secret key。 |
| `REPORT_TURNSTILE_SITE_KEY` | 普通变量 | 同一 widget 的公开 Site key；widget 域名允许 yingxi-station.pages.dev。 |

**发布前配置：** 当前原生上报使用全程后台校验，现有 Turnstile widget 必须在 Cloudflare 控制台改为 **Invisible** 模式，保留相同 widget 的 Site key、Secret 与域名限制。`appearance: interaction-only` 不能把 Managed widget 变成 Invisible；仅隐藏 WebView 也不能消除人工挑战。尚未切换时，新客户端收到人工挑战会结束为未完成，不会绕过校验提交。先切换并验证控件模式，再分发此候选。服务端仍须保留 Siteverify，不能直接删除唯一的公开写入保护。参见 [Cloudflare 模式说明](https://developers.cloudflare.com/turnstile/concepts/widget/)；本站隐私页已补充 [Turnstile Privacy Addendum](https://www.cloudflare.com/turnstile-privacy-policy/)。配置切换与生产部署不属于本地测试的完成证据。

不要把 Secret 写进源码、客户端、命令参数、日志或聊天。令牌需要设置有效期，并在到期前由维护者轮换。保存环境变量后重新部署，`GET /api/playback-report` 只返回公开 Site key 与可用状态；没有任何一个配置时提交会关闭，不假装回传成功。

接收端验证 Origin、当前版本的明确同意、单次 Turnstile（hostname/action/request ID）、实际流式 32 KiB 上限与字段白名单。只传生成的设备/编码/性能字段，剔除媒体地址、认证等额外字段；不接收文件上传或自由文本。IP 只用于 Cloudflare Siteverify，不写入 Issue。GitHub 返回确认的 Issue URL 才显示成功；网络结果不明时提醒先检查仓库，不自动重试。Turnstile 不是全局配额；若出现实际滥用，应再接入持久限额，不用进程内 Map 冒充全局限流。

检查命令：`npm run test:reports`、`npm run check`。先在本地/预览环境用合成报告验证，不将用户提供的检修记录当作发布测试数据自动发送。生产验收需维护者配置服务端授权，并主动提交一份无私人信息的合成报告。

依据：[Pages Functions 与 Direct Upload](https://developers.cloudflare.com/pages/get-started/direct-upload/)、[创建 GitHub Issue 的权限](https://docs.github.com/en/rest/issues/issues#create-an-issue)、[Turnstile 服务端验证](https://developers.cloudflare.com/turnstile/get-started/server-side-validation/)、[Pages 响应头](https://developers.cloudflare.com/pages/configuration/headers/)。


原生 WebView 实现依据：[Cloudflare 移动端集成](https://developers.cloudflare.com/turnstile/get-started/mobile-implementation/)、[Android WebView 导航与脚本](https://developer.android.com/develop/ui/views/layout/webapps/webview)、[Android 文件访问安全](https://developer.android.com/privacy-and-security/risks/webview-unsafe-file-inclusion)。JavaScript 与 DOM storage 用于实际校验；不为此开放本机文件访问。

### 应用内简洁反馈

`nativeUi: 2` 仅控制应用内显示，不进入公开报告字段。新版确认卡提交后切换为原生进度与结果提示；页面沿用同一份告知版本、白名单和单次 POST 约束。旧客户端不带此标记，仍显示原有网页状态与校验内容。

校验使用 Cloudflare 官方 [`interaction-only` appearance](https://developers.cloudflare.com/turnstile/get-started/client-side-rendering/widget-configurations/)：仅在需要交互时显示组件，未移除安全校验。页面通过受限 `yingxi-report://state` 回调告知原生层校验、发送和失败状态；原生层核对当前可信页面、主框架与请求 UUID，仅收到有效 Issue 回执才显示成功。没有回执时保留待核标记，不自动重发。

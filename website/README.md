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

`.github/workflows/website.yml` 在官网变更的 Push / PR 上核对源码、锚点、住民原文与媒体摘要，并保存静态产物。部署由 `workflow_dispatch` 明确选择 `preview` 或 `production`，不会因 PR 自动发布。

部署任务使用 GitHub 的 `website` environment。配置：

- Secret `CLOUDFLARE_ACCOUNT_ID`：目标账号ID。
- Secret `CLOUDFLARE_API_TOKEN`：限定目标账号、具备 Cloudflare Pages Edit 的令牌。
- 可选 Variable `CLOUDFLARE_PAGES_PROJECT_NAME`：实际项目名。

令牌在 Cloudflare 与 GitHub 的秘密管理界面配置，不提交到源码、不放在网页内，也不通过聊天传递。账号登录成功不等于这些CI秘密已配置。[Cloudflare 持续集成说明](https://developers.cloudflare.com/pages/how-to/use-direct-upload-with-continuous-integration/)。

## 内容与维护

- `public/index.html`：播放器介绍、废土世界、下载与常见问题。
- `public/assets/screenshots.json`：界面素材对应的应用版本、正式APK校验和及图片/录屏摘要。
- `public/assets/letters.json`：按稳定ID导出的中英住民短笺。
- `public/privacy/`：官网浏览隐私说明，尚非完整应用隐私政策。
- `public/support/`：安装、媒体来源、播放和反馈说明。

素材来自正式签名 APK 在 Android 16 ARM64 模拟器的实际运行；演示影片为本地原创生成，没有后期拼接应用界面。该证据不代表真机、网络提供方或全部解码格式验收。iOS 仍为开发预览，没有 App Store 下载入口。

住民短笺更新后，在仓库根目录运行：

```sh
python3 -B website/tools/sync-letters.py
node website/tools/check.mjs --atlas app/src/main/res/raw/world_atlas.json
```

两条尚未随应用发布的日常短笺已使用独立稳定标识，保留 alpha.52 已发布的 `diary_026`。本轮官网落地不宣称新增短笺已包含在 alpha.52 APK 中。

中文字体是带 OFL 许可的 Noto Sans SC 本地变量字体子集。修改文字后，使用 `tools/subset-fonts.py --sans-source 本地字体路径` 重新裁剪；该工具需要 fontTools 与 Brotli，不向在线服务提交文案。

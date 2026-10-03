# 五类产品设计原型与实际截图

**DESIGN-ONLY／尚未生产实施；独立交互原型 · 模拟数据。** 本轮真实 Chromium 渲染五类代表页，但不是生产 React/Ant 实现，也没有真实业务请求、File、SSE 或模型调用。原型不用 Ant 默认外观，以自有壳、层级、密度和主题语义表达新设计。

## GitHub看图与单文件下载

本页直接展示下方29张实际Chromium设计截图，包含五类页面×三皮肤、窄屏与操作状态。**GitHub可显示PNG／Markdown，但不会执行HTML原型。** [单文件HTML源码／下载入口](review-single.html)可从文件页下载后用浏览器打开；CSS／JS均内联，导航和主题控件可切五页三皮肤。也可[直接读取单HTML](https://raw.githubusercontent.com/wangyufengsky/opencode-loopper/refs/heads/feat/react-full-migration/docs/design/react-full-migration/prototype/review-single.html)并保存为.html；raw地址可能以文本显示，不是部署预览。浏览器限制file时使用下面的本地静态预览，不修改安全策略。

## 本地查看与复现

- [单文件审查版](review-single.html)：完全内联原CSS／JS，适合只加载一个HTML的查看器；五类页面／三皮肤与原互动相同，无外部资源请求。
- [原型 HTML](index.html)：配合同目录 CSS/JS；query 可选 `page=home/list/form/detail/settings&skin=spdb/tech-blue/github-white`。五类页面内可点导航、筛选、更多、弹层、高级字段及主题。
- [产品翻新与 before→after](../product-redesign.md)：旧源码结构与新设计逐项对照；没有拍摄相同夹具的旧页对比截图。
- [浏览器结构化证据](evidence.json)：原型四份源码与每张 PNG 的 SHA-256、皮肤、viewport、状态、全部检查。

浏览器若不接受 file 协议，使用正常本地静态预览，不改变策略。可在仓库根执行：

```sh
python3 -m http.server 4177 --bind 127.0.0.1 --directory docs/design/react-full-migration/prototype
```

然后打开 `http://127.0.0.1:4177/?page=home&skin=github-white`。仅本机可访问；结束用 Ctrl-C。没有公开预览或部署。

截图脚本使用已经安装的官方 Playwright，不安装候选框架。当前新工作区没有 node_modules；本轮复用同基线第一阶段 worktree 的既有工具：

```sh
node docs/design/react-full-migration/prototype/capture.mjs --tools /workspace/opencode-loopper-react/frontend
```

未来已有本 worktree frontend 依赖时可省 `--tools`。脚本只启动三文件的回环预览、阻止外部 HTTP 请求、关闭浏览器与服务器，不启动产品。普通沙箱启动 Chromium 曾受 setsockopt EPERM 阻塞，file 导航曾返回 ERR_BLOCKED_BY_ADMINISTRATOR；改用正常获准的 Chromium/回环预览，未改网络、代理或浏览器策略。

## 最终检查与证据边界

最终浏览器 Chromium 151.0.7922.173：**65 项通过、0 项失败、29 张截图**。45 组合覆盖五页面×三皮肤×1440/390/320px，检查模拟标记、唯一标题及无页面横向溢出。截图是精选子集，不声称每个组合都有截图。其余20项是原型互动检查；0外部请求、0pageerror。截图等待有限动效自然结束，不截半透明过渡帧。

- 真键盘：跳到主内容；连续24 Tab＋24 Shift-Tab越过弹层首尾、Escape回触发器；10轮开关；移动导航16入口；危险确认默认取消。
- 模拟互动：搜索/状态/项目组合筛选与选择保留；三皮肤切换保留输入；dirty明确取消/放弃、unknown阻离开与模拟查询原回执；任务解决稿409保留输入；普通Settings原PUT无新增CAS；设置跨组保存模拟草稿/高级展开/switch状态；Space切换。
- 动效：正常Drawer公开CSS为180ms，控件立即可用；实际reduced-motion下animation=none、transition=0s且键盘可用。未验证读屏软件、真手机触控、生产Ant资源清理、设备性能或全部WCAG合规。
- 原型未知回执保护比新建需求基线更严格；项目经理现已采纳该默认策略，但生产尚未改，W0红／绿证据仍未跑。模拟查询不是实际幂等/恢复验收，当前RequirementNew没有公开by-request GET，只能以原key／body显式幂等恢复POST。为保持原型相同，原JS／原图中的“建议”文案保留，此处注明设计决定更新。设置部分组与其他功能入口是明确占位，不表示生产功能已经迁移。
- 独立审查曾发现组合筛选／filter值／设置切组／dirty出口不足，已修；加强连续Tab检查实际暴露首尾焦点逸出，已在原型dialog自身修正并严格复验。新增动效检查曾因两个data-close控件而查询歧义失败，改为精确命名控件后最终重跑65/0。失败不是全站产品回归，也未靠放宽断言变绿。交叉审查还发现Settings无CAS，已把409示例移到Task有版本的local-sync解决稿，并纠正对应矩阵；最终截图没有旧的Settings409示例。
- 第一阶段204Chromium/1305unit与剩余100E2E（含原11失败）的历史边界保持。本轮没有执行生产unit/E2E/typecheck/build或干净npm ci；第二阶段最终门禁仍要求全量执行和noVue动态逐路由证明。

## 桌面：五类 × 三皮肤

相同模拟内容，1440×960 viewport，普通页面PNG为full-page，较长内容可能高于960；弹层状态PNG为viewport，避免拼接屏外固定元素。三皮肤只改变tokens，不改变布局和功能层级。

| 页面 | spdb 风 | 科技蓝 | GitHub 白 |
| --- | --- | --- | --- |
| 首页 | ![首页·spdb·模拟数据](screenshots/spdb-home-1440.png) | ![首页·tech-blue·模拟数据](screenshots/tech-blue-home-1440.png) | ![首页·github-white·模拟数据](screenshots/github-white-home-1440.png) |
| 任务列表 | ![任务列表·spdb·模拟数据](screenshots/spdb-list-1440.png) | ![任务列表·tech-blue·模拟数据](screenshots/tech-blue-list-1440.png) | ![任务列表·github-white·模拟数据](screenshots/github-white-list-1440.png) |
| 新建需求表单 | ![新建需求表单·spdb·模拟数据](screenshots/spdb-form-1440.png) | ![新建需求表单·tech-blue·模拟数据](screenshots/tech-blue-form-1440.png) | ![新建需求表单·github-white·模拟数据](screenshots/github-white-form-1440.png) |
| 任务详情 | ![任务详情·spdb·模拟数据](screenshots/spdb-detail-1440.png) | ![任务详情·tech-blue·模拟数据](screenshots/tech-blue-detail-1440.png) | ![任务详情·github-white·模拟数据](screenshots/github-white-detail-1440.png) |
| 设置 | ![设置·spdb·模拟数据](screenshots/spdb-settings-1440.png) | ![设置·tech-blue·模拟数据](screenshots/tech-blue-settings-1440.png) | ![设置·github-white·模拟数据](screenshots/github-white-settings-1440.png) |

## 窄屏代表图

| 页面 | GitHub 白 390px | 其他皮肤／320px专项 |
| --- | --- | --- |
| 首页 | ![首页·390px·模拟数据](screenshots/github-white-home-390.png) | ![首页·窄屏专项·模拟数据](screenshots/spdb-home-390.png) |
| 任务列表 | ![任务列表·390px·模拟数据](screenshots/github-white-list-390.png) | ![任务列表·窄屏专项·模拟数据](screenshots/github-white-list-320.png) |
| 新建需求表单 | ![新建需求表单·390px·模拟数据](screenshots/github-white-form-390.png) | ![新建需求表单·窄屏专项·模拟数据](screenshots/tech-blue-form-390.png) |
| 任务详情 | ![任务详情·390px·模拟数据](screenshots/github-white-detail-390.png) | ![任务详情·窄屏专项·模拟数据](screenshots/tech-blue-detail-390.png) |
| 设置 | ![设置·390px·模拟数据](screenshots/github-white-settings-390.png) | ![设置·窄屏专项·模拟数据](screenshots/github-white-settings-320.png) |

## 操作状态／动效模式

| 筛选抽屉 | 未知回执草稿保护 | 任务解决稿版本冲突 | 减少动效 |
| --- | --- | --- | --- |
| ![筛选抽屉·模拟数据](screenshots/github-white-list-filters.png) | ![未知回执·模拟数据](screenshots/tech-blue-form-unknown.png) | ![409版本冲突·模拟数据](screenshots/spdb-detail-conflict.png) | ![减少动效·模拟数据](screenshots/tech-blue-list-reduced-motion.png) |

## 归属和后续门禁

本原型与输出只位于docs，不被frontend/main、router、package、Vite、生产测试或打包入口导入；不使用生产状态或用户数据。HTML中的内联样式许可只属于这个独立原型CSP，不改变应用安全配置。原型用本地字符图形占位；正式图标继续复用已有Lucide ReactIcon，不把占位字符当最终图标库。

项目经理审查本轮五类信息结构／三皮肤／入口／动效之后，才进入正式开发。届时每类页面要重新以真实React、Ant和领域controller验收；不能把这些29张图当正式迁移截图。

## 单文件的独立复现与证据

```sh
node docs/design/react-full-migration/prototype/bundle-single.mjs --check
node docs/design/react-full-migration/prototype/verify-single.mjs --tools /workspace/opencode-loopper-react/frontend
```

打包器不重设计、不压缩或重写原CSS／JS；脚本置于body尾部，避免inline defer提前运行，CSP以精确脚本SHA-256授权且connect-src none。`--check`比对生成内容，`bundle-single.mjs`无参数可从原三文件重建。父原生查看器工具在本Cloud不可调用；本地Chromium证明、原文件保留与待验边界见[补充记录](../design-gate-supplement.md)及[单文件证据](single-evidence.json)。不是父侧视觉验收，也不是生产React测试。

# 桌面修订：五类页面 × 三皮肤

**DESIGN-ONLY · 实际浏览器渲染 · 模拟数据 · 生产未实施。** 本版落实简洁主体、选中后按需详情，以及统一本地Lucide语义/动作；所有窄屏设计、适配与验收退出本轮。旧[29图及原型](../README.md)保留历史，不作为新版验收。

[打开自包含HTML审查版](review-single.html)；五页和三皮肤可直接切换，无外部CSS/JS/字体请求。GitHub将来只能展示PNG/Markdown，不执行HTML；可下载单HTML后浏览器打开，或在本任务原生网页预览查看。当前只本地交付，未再次发布。

## 默认主画面

1440×960桌面；相同模拟对象和信息结构。没有选中时不常驻详情栏，关键状态仍可见。

| 页面 | spdb 风 | 科技蓝 | GitHub 白 |
| --- | --- | --- | --- |
| 首页 | ![首页·spdb·模拟数据](screenshots/spdb-home-default.png) | ![首页·科技蓝·模拟数据](screenshots/tech-blue-home-default.png) | ![首页·GitHub白·模拟数据](screenshots/github-white-home-default.png) |
| 任务列表 | ![列表·spdb·模拟数据](screenshots/spdb-list-default.png) | ![列表·科技蓝·模拟数据](screenshots/tech-blue-list-default.png) | ![列表·GitHub白·模拟数据](screenshots/github-white-list-default.png) |
| 新建需求 | ![表单·spdb·模拟数据](screenshots/spdb-form-default.png) | ![表单·科技蓝·模拟数据](screenshots/tech-blue-form-default.png) | ![表单·GitHub白·模拟数据](screenshots/github-white-form-default.png) |
| 任务详情 | ![详情·spdb·模拟数据](screenshots/spdb-task-default.png) | ![详情·科技蓝·模拟数据](screenshots/tech-blue-task-default.png) | ![详情·GitHub白·模拟数据](screenshots/github-white-task-default.png) |
| 设置 | ![设置·spdb·模拟数据](screenshots/spdb-settings-default.png) | ![设置·科技蓝·模拟数据](screenshots/tech-blue-settings-default.png) | ![设置·GitHub白·模拟数据](screenshots/github-white-settings-default.png) |

## 规范与证据入口

- [旧→新信息结构与主要动作路径](../../desktop-redesign.md)
- [全站词汇、图标、动作、共享组件API与允许变体](../../semantic-ui-contract.md)／[机器语义表](semantic-registry.json)
- [窄屏逐项退出范围](../../desktop-scope-exclusions.md)：保留既有断言，混合测试中的桌面合同继续
- [浏览器结果与图片SHA-256](evidence.json)／[非作者独立复核](../../desktop-v2-review.md)
- [W0业务证据](../../evidence/w0/README.md)：本地3c848261；25父组失败和Automations消费者缺口独立，视觉原型不能将其变绿

本版不连接真实后端、模型或文件解析，不证明React/Ant已迁移、服务器幂等、File持久化或全站资源清理。unknown恢复为明确模拟；新建需求没有by-request GET，其真实合同仍是显式原key/body POST。设置仍原PUT语义，不添加虚构CAS。

## 选中、保护与恢复状态

以下13图与上面15张默认图合计28张，均为本次最终浏览器渲染，不沿用旧29图。除明确标注1280的动效状态外，尺寸均为1440×960。

| 状态 | 实际截图（模拟数据） |
| --- | --- |
| 首页选中项目 · GitHub白 | ![首页选中](screenshots/github-white-home-selected.png) |
| 列表选中任务 · GitHub白 | ![列表选中](screenshots/github-white-list-selected.png) |
| 四字段表单选中 · GitHub白 | ![表单选中](screenshots/github-white-form-selected.png) |
| 任务详情选中 · GitHub白 | ![详情选中](screenshots/github-white-task-selected.png) |
| 设置选中分组 · GitHub白 | ![设置选中](screenshots/github-white-settings-selected.png) |
| 表单普通草稿离开确认 · spdb | ![草稿确认](screenshots/spdb-form-dirty-confirm.png) |
| 表单未知回执与原身份恢复 · 科技蓝 | ![未知回执](screenshots/tech-blue-form-unknown.png) |
| 表单确定回执交接 · GitHub白 | ![确定回执交接](screenshots/github-white-form-handoff.png) |
| 任务等待决策 · spdb | ![等待决策](screenshots/spdb-task-waiting.png) |
| 任务版本冲突 · spdb | ![版本冲突](screenshots/spdb-task-conflict.png) |
| 任务只读恢复 · 科技蓝 | ![只读恢复](screenshots/tech-blue-task-recovered.png) |
| 设置页面整体草稿 · spdb | ![设置草稿](screenshots/spdb-settings-dirty.png) |
| 桌面1280、减少动效 · 科技蓝 | ![减少动效](screenshots/tech-blue-list-reduced-motion-1280.png) |

最终非作者验证 **205通过／0失败**：五页×三皮肤×两桌面尺寸的30组源版与单HTML精确DOM／像素一致；原生查看器式HTML注入的15组均零资源请求。每张最终PNG回到页面顶部并连续两次截图哈希一致。源码、单文件、图片SHA与已修正的中间失败见[证据](evidence.json)和[独立复核](../../desktop-v2-review.md)。这项结论只适用于设计原型，不代替正式React/Ant验收。

## 本地复现

```sh
node docs/design/react-full-migration/prototype/desktop-v2/generate-single.mjs
node docs/design/react-full-migration/prototype/desktop-v2/generate-single.mjs --check
node docs/design/react-full-migration/prototype/desktop-v2/verify-desktop.mjs
```

验证脚本使用当前frontend的既有正式Playwright和Chromium，不安装候选框架。脚本独占本机静态端口41783，结束关闭浏览器和服务器；如有正常工具权限错误按实际结果报告，不修改网络或安全策略。手动预览可正常启动仅本机静态服务（结束Ctrl-C）：

```sh
python3 -m http.server 41783 --bind 127.0.0.1 --directory docs/design/react-full-migration/prototype/desktop-v2
```

打开 `http://127.0.0.1:41783/review-single.html?page=home&skin=github-white`。这是本地预览，不是部署。

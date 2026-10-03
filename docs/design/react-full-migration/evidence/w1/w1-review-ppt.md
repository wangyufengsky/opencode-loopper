# W1 非作者真实浏览器与源码复核

最终候选通过本轮 W1 隔离基础工程验收：**41 组 PASS／0 FAIL、11 张实际 Chromium 截图、18 个同步卸载首样资源门禁通过**。没有发现本轮已测范围的剩余阻塞。此结论不代表 W2+ 页面迁移、生产 API、全站路由或 W0 红测已经完成。

复核人是原团队 C `/root/react_ppt_canvas`；原启动记录明确指定 `gpt-6.1-sol / xhigh`，当前工具不提供实时模型配置字段。C 只编写独立浏览器脚本、证据、截图和本文，未修改 A 的受控组件、B 的新契约、组长的 provider/theme/registry/preview。此前第一阶段 C 参与过 `acknowledgedOperation`，本文不把该旧模块作为非作者独立审查成果。没有增员、提交、推送或安装依赖。

## 最终执行与来源

基线为 W0 本地提交 `3c848261bfaa603a10fa06652fbbca8e82c3e3b8`，工作区 `/workspace/opencode-loopper-react-full`。浏览器使用项目已安装的官方 Playwright、`/usr/bin/chromium`，实际版本 `151.0.7922.173`；Node `v24.19.0`。仅桌面 `1440×960`／`1280×900` 和 `spdb`／`tech-blue`／`github-white`，没有新增窄屏验收。

组长启动并持有 React-only Vite 41784，C 只连接该入口；本次浏览器、context、trace 已正常关闭，没有停止他人服务。实际最终命令从仓库根目录执行：

```sh
W1_FOUNDATION_EVIDENCE_DIR=/workspace/react-full-w1-evidence/browser-final-v5 \
PLAYWRIGHT_CHROME_EXECUTABLE=/usr/bin/chromium \
node frontend/e2e/w1/verify-foundation.mjs \
  > /workspace/react-full-w1-evidence/browser-final-v5.log 2>&1
```

命令 **exit 0**；[完整结果](../../../../../frontend/e2e/w1/evidence.json)中 `executionComplete=true`，41 项定义全部执行且通过。环境原始日志、完整 `trace.zip`、运行截图原样副本保留在仓库外 `/workspace/react-full-w1-evidence/browser-final-v5/`，没有将环境日志加入交付。

| 最终证据 | 数量／结果 |
| --- | --- |
| 实际浏览器检查 | 41 PASS／0 FAIL |
| 同步卸载首样 | 18／18：监听、fixture interval、fixture RAF、全部 raw RAF、所有 RO 活跃 target 均为空 |
| 同 owner StrictMode 重复挂载 | 10 轮；每轮真实 setup 重放两次，单一 view lease，无重复 write |
| 截图 | 11；全部 scroll `(0,0)`，连续两张完整图 SHA 完全相等后采集，实际稳定采样 2–5 次 |
| 请求 | 37 个同源 localhost GET，0 外部／API／mutation 请求 |
| 浏览器错误／警告 | console error、warning、pageerror 均 0 |
| Vite 开发通道 | 1 条 `ws://127.0.0.1:41784/` HMR websocket；不是产品 SSE，未宣称 root 卸载会关闭开发服务通道 |
| 冻结检查 | 27 个源文件运行前／后 SHA 完全相等；复核时当前文件逐项相等 |

最终 evidence SHA-256：`908936fc2c480d938d52db7b2e97d58fdbc0ebab1971de54327fa9bd5da65a12`。

| 关键文件 | 最终 SHA-256 |
| --- | --- |
| `frontend/e2e/w1/verify-foundation.mjs` | `9aca9a5d09ac9a8a56fa2adac4b544863f50b4cff2f5eccec539280f064cf9c5` |
| `frontend/e2e/w1/preview.tsx` | `b7e7cf49b639b783087d87db2194083929d094e165e46c253761f0c5a4abe1f4` |
| `frontend/src/foundation/components.tsx` | `b1d663e00f06680d06261782c69dd1af5b63ebc2e3405c6c30062713a3472488` |
| `frontend/src/foundation/components.spec.tsx` | `781ff9190364fee5475d4e5bda30a746c4e458edf44f346475c05c130f6fbe42` |
| `frontend/src/foundation/contracts/receipt.ts` | `f446815215b62ca50e63015d263ae37cb919bd5921794fa68289febe1925b596` |
| `frontend/package-lock.json` | `9531ee279895657bd4045c180164ad4fd276eb3c50ffd8d4526f44700bbfee72` |

## 功能与源码复核

| 合同 | 非作者源码锚点与实际验证 |
| --- | --- |
| 受控 UI、中央语义 | [components.tsx](../../../../../frontend/src/foundation/components.tsx):29–47、60–74、235–260；8 类实际 React/Ant 组件在三皮肤／两尺寸渲染，clean write/read/navigation 为 0，真实 Enter／Space 选择、Escape／关闭回焦、展开／收起及非 modal Tab。SVG exact body 来自锁定本地子集；dirty DOM 恰好 10 枚语义图标，按完整 multiset 比较，不忽略重复项。字段 label/htmlFor/required/help 和原生 skip-link 实际可达。 |
| 焦点属于本实例 | 同文件:77–114、145–150、184–218；不可见／失联触发器回本 provider 主内容，enabled icon-only title 与中央中文 name 一致。普通 dirty modal 默认 Stay；真实 Tab／Shift+Tab 始终在实际 dialog 且 `document.hasFocus()`；放弃关闭后的 `afterClose` 回原 caller。嵌套真实 Ant portal 只由最近 DOM scope 接管，内层 Escape／Stay 回内层 trigger，外层取消不 delete/write。 |
| 三皮肤／减少动效 | [provider.tsx](../../../../../frontend/src/foundation/provider.tsx):16–25、30–47；[theme.ts](../../../../../frontend/src/foundation/theme.ts):24–25。切肤保同一真实 input DOM、焦点、controller/body/key 与单一资源 lease；真实 `emulateMedia(reducedMotion)` 不重挂字段，动画 none／transition 0s。公开 motion token 保持 true，减少动效只改变 duration／scoped CSS。 |
| pending／unknown 优先禁止离开 | [navigation.ts](../../../../../frontend/src/foundation/contracts/navigation.ts):12–27、37–64；[controller.ts](../../../../../frontend/src/foundation/contracts/controller.ts):27–43、67–100。真实字段/save/delete/close disabled，Escape／Back 保留 status／原 identity。普通 dirty 必须确认；已开 dirty modal 后经明确公开 fixture 意图进入 SENDING，live policy 立即禁止确认。该并发模拟调用公开 owner API，未点击被 modal 遮挡的 disabled 控件，也未修改私有 VM。 |
| 写回执与能力 | [receipt.ts](../../../../../frontend/src/foundation/contracts/receipt.ts):85–110、124–138、168–223。原 body/key/File refs 捕获；accepted fact 先保留，bad DTO 不暴露 raw receipt、不读、不 handoff、不重复写。无实际 lookup 时 BLOCKED。明确 UNKNOWN idem 重试仅写同一 body/key，accepted 仅读，keyless UNKNOWN 仅核对且未确认仍阻断。 |
| 名称／scope／实际副作用一致 | [preview.tsx](../../../../../frontend/e2e/w1/preview.tsx):130–135；[verify-foundation.mjs](../../../../../frontend/e2e/w1/verify-foundation.mjs):152–158、344–372。`receipt.retryOriginal` 必须中文“重试原操作”且 scope=`server`，实际 writes 1→2；`receipt.readOriginal` 必须“核对原操作结果”且 scope=`read`，只增 reads，writes 不变；真实 UI 中 `ui.retry` 为 0。accepted recovery 后旧失败不再显示，成功 notice 为“原操作核对完成（模拟数据）。”。 |
| File 与 view／operation 分离 | [immutable.ts](../../../../../frontend/src/foundation/contracts/immutable.ts):2–22；[controller.ts](../../../../../frontend/src/foundation/contracts/controller.ts):67–100；[preview.tsx](../../../../../frontend/e2e/w1/preview.tsx):63–79、164–190。测试显式创建真实 `File`，主题切换及 view unmount/remount 保原实例，唯一显式 write 的 `sameFiles` 为 true。强制 pending owner retire 后先资源清零，迟到原 write accepted/receipt/body/key 保留在旧 operation，read／projection 为 0。 |
| 资源精确释放 | [resource.ts](../../../../../frontend/src/foundation/contracts/resource.ts):12–38；fixture 实际注册在 [preview.tsx](../../../../../frontend/e2e/w1/preview.tsx):34–48。scope 先失效、只精确释放自身 disposer；StrictMode 两次 setup 有相应 release，不由 render/effect 发命令。没有全局清监听、伪造 cleanup pointerup 或库私有 patch。 |

导航 mock 只调用注入的本地 port 并记 counter；不接 React Router、不接管 browser history，不创建 SSE 或真实 HTTP。原生 skip-link 的 fragment 行为单独允许，不把它当产品路由切换。

## 资源首样与检测边界

[观察器](../../../../../frontend/e2e/w1/verify-foundation.mjs):56–115 透明记录 native API 的 target/callback/capture identity。监听范围是本 fixture `w1-instance-probe`、现代 `MediaQueryList.change`、window `focusin/keydown`（含实际 Ant focus trap）；interval 归属为锁定 fixture 的 `attachResources/tick` allocation stack。**全部 raw RAF 也要求空**，不排除未知／库 RAF；全部原生 RO 活跃 targets 保持 raw，包括 detached 元素。

[首样门禁](../../../../../frontend/e2e/w1/verify-foundation.mjs):160–181 在同一 `page.evaluate` 中调用真实 `root.unmount()` 后立即 snapshot，之后才检查 strict `[]`。清理前不等待、不发 move/up/cancel/blur 或其他事件。随后独立 probe/sentinel 只证明旧 callback 停止、无关 sentinel 仍正常，不能修复前面的门禁。

18 个首样分别为：File detach 1、UNKNOWN 同 owner 10 轮、forced pending retirement 1、pending/accepted/keyless-read-only/dirty/clean root loss 5、真实 active Ant modal root loss 1。每个 snapshot 的 `listeners/pendingFrames/rawPendingFrames/intervals/observers` 均为空；10 轮仍同 key/body/write=1，remount 每轮 `viewCount=1`。

真实 detached-RO 负控在 :463–475：本测试拥有的 RO observe 实际 div 后移除 div，立即 snapshot 仍包含 `connected=false` target，exact-empty 断言必须抛错；之后才 disconnect 本测试自己的 RO。没有按 connected 过滤观察关系。

检测账本保留 callback／observer 引用，**不是 heap、GC 或 retaining-path 证明**。空的 RO target set 证明观察关系终止，不证明 singleton RO 对象已被 GC。没有宣称所有任意第三方 timeout、React 容器永久委派事件、其他设备／浏览器或所有产品画布均已验证。

## 中间失败与修复，不合并为最终成功

| 原样证据 | 实际结果与根因 | 最终关闭证据 |
| --- | --- | --- |
| `browser-first.log` | import SyntaxError；浏览器未执行，非产品失败。官方 `playwright` 不导出 expect。 | 仅 C 改为项目已安装 `@playwright/test` 的公开 expect；未安装新包。 |
| `browser-first-execution.log`／同名目录 | 38 组：33 PASS／5 FAIL。两个测试夹具错误：把 exact 10 glyph 误写成 >10、删除 action 查到隐藏保留 modal 重复按钮。其余是真实 Ant Alert.message deprecated、减少动效重挂输入丢焦、Modal 首尾 Tab 落 BODY。 | exact glyph multiset 和真实 context 限定保留强断言；组长使用公开 Alert.title／稳定 motion token；A 使用公开 modalRender 的实例局部 React capture。原 INPUT→ASIDE／BODY 失败保留，没有把合法 sentinel 或无焦点边界解释成通过。 |
| `browser-final.log`／`browser-final/` | v3 40 组：39 PASS／1 FAIL；dirty discard 后 Ant afterClose 尝试回隐藏 panel trigger，实际未回原 caller。 | A 加不可见目标过滤及对应 caller 的最终 afterClose；原严格 trigger 焦点断言保留。 |
| 作者 `components-nested-confirmed-before` 负控与 C 源码审查 | nested React portal 键盘事件会经过 outer scope，inner Tab 被 outer 抢焦；该修前 red 是作者实际 unit，未冒称 C 的 browser red。 | A 加 DOM contains／nearest scope；C 最终真实 nested browser 4 Tab＋4 ShiftTab、Escape／Stay／显式内层 confirm、outer cancel 均通过。 |
| `browser-final-v4.log`／`browser-final-v4/` | 41 PASS／0 FAIL、11 PNG，但当时未覆盖名称／scope→副作用配对。逐图审查发现“重试读取”实际执行恢复 write；accepted read 成功仍留旧失败提示。该批不能冒称完整语义合同已通过。 | 组长只修隔离 fixture 既有 semantic key 绑定及 current/unretired owner notice；v5 新增强断言并整批重跑，旧 v4 evidence／截图副本保留。 |
| `browser-final-v5.log`／`browser-final-v5/` | 最终当前字节 41 PASS／0 FAIL、11 PNG、18 首样通过。 | current components typed-test SHA、fixture notice/semantic 修订均纳入 27 个冻结文件；没有沿用旧 SHA 冒充当前执行。 |

两处仅 `closest<HTMLElement>` 的测试类型收窄发生在 v4 后，v4 原样 provenance 保留；当前 v5 直接包含最新测试 hash。此前成功写入后 captureDto 抛错被误当 UNKNOWN 的 primitive 风险由组长发现、B 修复，本文核对最终先 accepted 的实现与 malformed 浏览器负控；不伪造 C 运行过该修前红测。

## 实际截图复核

三张 default 在 v4→v5 字节未变且此前已实际查看；其余八张最终 v5 图片逐张通过 `view_image` 查看。全部 11 张 repo PNG 与 evidence SHA／bytes 以及 raw run 副本相等；图片仅证明对应状态呈现，资源结论由同步账本和负控支持。

| 状态 | 最终图片 |
| --- | --- |
| 三皮肤 default，详情栏隐藏 | [spdb](../../../../../frontend/e2e/w1/screenshots/spdb-default.png)／[科技蓝](../../../../../frontend/e2e/w1/screenshots/tech-blue-default.png)／[GitHub 白](../../../../../frontend/e2e/w1/screenshots/github-white-default.png) |
| 三皮肤 selected，320px 详情与可达动作 | [spdb](../../../../../frontend/e2e/w1/screenshots/spdb-selected.png)／[科技蓝](../../../../../frontend/e2e/w1/screenshots/tech-blue-selected.png)／[GitHub 白](../../../../../frontend/e2e/w1/screenshots/github-white-selected.png) |
| dirty 默认 Stay，明确放弃 | [spdb-dirty-confirm](../../../../../frontend/e2e/w1/screenshots/spdb-dirty-confirm.png) |
| unknown 保身份、持续阻断、中文原写恢复 | [tech-blue-unknown](../../../../../frontend/e2e/w1/screenshots/tech-blue-unknown.png) |
| accepted 原读恢复成功、旧失败清除 | [github-white-accepted-recovered](../../../../../frontend/e2e/w1/screenshots/github-white-accepted-recovered.png) |
| reduced motion 实际 input 焦点仍在 | [tech-blue-reduced-motion](../../../../../frontend/e2e/w1/screenshots/tech-blue-reduced-motion.png) |
| nested 内层默认 Stay，外层仍保留 | [spdb-nested-confirm](../../../../../frontend/e2e/w1/screenshots/spdb-nested-confirm.png) |

主内容／状态／上下文可见，三皮肤文字与动作可辨，GitHub 主操作绿色与选择蓝色区分；所有截图页头明确“模拟数据·独立测试入口·非生产页面”。fixture 内审计 phase／BLOCKED／owner 等工程信息属于隔离验证展示，不能原样迁入产品流。

## 后续门槛

W1 真实 React/Ant browser 41 组与独立 HTML 设计原型 205 项分别计账，不能互相替代。组长持有 W1 unit 69 PASS、全 unit 1485＝1401 PASS＋84 W0 合同红、clean ci/typecheck/build/tooling 的执行；C 没有重复运行这些全量命令，也不把 W0 红算作 W1 新缺陷。[组长总报告](README.md)持有其准确命令、日志和包体警告。

本轮没有实际后端／付费模型／文件选择器或 multipart 上传、File 字节持久化、SSE、生产 409/CAS、by-request 能力或刷新恢复测试。W2+ 必须按每个真实接口定义恢复能力、同 scope 读取乱序及服务端版本合同；W6 才接唯一生产 history／根入口，W7 才要求全站 E2E／noVue tree／锁定构建与资源门禁。独立预览包体 >500KB 警告保留，不能将 W1 隔离入口当作全站预算或发布验收。

C 交还脚本、evidence、11 PNG 和本文；不再修改作者源码或追加范围。当前已测 W1 范围无剩余阻塞，生产迁移与上述后续门槛仍未实施。

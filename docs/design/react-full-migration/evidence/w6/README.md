# W6：唯一 React 入口与 Vue 退场

本地 W6 有限范围验收通过，**不是 W7 全站最终交付**。从 W5 `2186deafdfc6d8bfbd6047ea2b3b8853a5296683` 继续，在 `feat/react-full-migration` 保留已验收页面、React Flow/PPT 画布及纯 TS 协议。没有 push、PR、merge、部署、后端修改或付费模型调用。

查看入口：[真实生产三皮肤截图（56 PNG）](screenshots/README.md) · [全部 31 路由/所有权](routes.md) · [逐测试映射](test-mapping.json) · [删除/替代索引](retirement-manifest.json) · [依赖与零残留](dependency-audit.json) · [干净安装/补丁复现](patch-reproduction.json) · [最终源码 SHA](source-hashes.json) · [验证回执摘要](validation.json)。先前设计原型、各波次及第一阶段截图保留；本目录新图全部是实际生产 React 页面、模拟数据、桌面 Chromium。

## 实际改动边界

1. `main.tsx`/`app/bootstrap.tsx` 原子替换旧入口；React Router **7.18.4** 为唯一 history。该版本 MIT、Node ≥20，兼容项目固定 Node 22.14；没有选用需要 Node ≥22.22 的版本。App、menu、theme、dialog、Story Accounting 和 Task port 走真实 React/纯 TS；没有 Vue 子页面或第二个生产 root。三主题仅更新 TS snapshot，不退休业务 owner。
2. 28 个页面记录、3 个 alias/fallback 记录保持。Designer 无 session 保留原 guard，两个历史 alias 保留 query/hash，404 沿原 fallback；same-document POP 与显式导航共用保护，原生 refresh/离开仍有 beforeunload。普通 dirty 确认、确认中新 pending 重核、竞争导航 epoch、原请求身份/文件/草稿、accepted 仅读恢复均由稳定 owner 持有。
3. 删除 **211 个受版本控制旧执行/样式文件，其中 189 个 `.vue`**；旧测试路径仍保留合同索引。10 个直接 Vue 家族依赖及 31 个旧 lock 节点退场。唯一新增依赖为锁定 React Router，W1 React/Ant、本地 Lucide 与已验收画布不重写。`tsc -b` 与 Vite React 取代 Vue 编译器/插件；生产 build 写实际模块清单。
4. 原 scoped 展示通过 React 对应样式和真实页面保留；19 个 Element 专用变量、12 个 Element 专用规则及两份无消费旧 CSS 清除，已验收 canonical workflow CSS/纯 TS 的 13 个原哈希未变。中央语义表为 **122 对象 / 375 动作 / 137 本地 Lucide glyph**。关闭 UI 与停止/取消服务端仍是不同动作。
5. 源码、配置、测试、脚本、package/lock、实际直接/传递依赖树及构建模块联合审计，**零可执行 Vue 家族依赖、模块引用、SFC**。不是全文忽略名单；历史报告与原测试名称中的 Vue 字样仅作历史说明，历史 storage 值仅作不启用旧引擎的负控。实际 audit 脚本没有可执行目录白名单。

## 最终验证

| 项目 | 实际结果 | 口径 |
| --- | --- | --- |
| 无 `node_modules`/`dist` 开始的 `npm ci` | exit 0，369 packages | 仅清理本工程可重建产物；未清用户数据；6 个锁定 xyflow 入口补丁实际重现 |
| 全量 Vitest | **2363/2363，0 fail、0 pending**，273 文件 | 原 **2332** 名称多重集合 missing 0，新 **31**；不是 `expect` 调用数；W0 **111/111** 在内 |
| typecheck / production build | exit 0 / exit 0 | 最后展示变体修复后重新构建；大包体 >500KB warning 保留 |
| tooling / accounting | **39/39 / 13/13** | 包括零残留负控、补丁版本/哈希失败关闭、测试映射空收集失败负控 |
| W6 实际生产 route browser | **97/97**，0 skip/flaky | 31 记录 × 3 皮肤 + Designer guard + 两项同文档 POP + 未知 MQL 负控 |
| W6 actual bootstrap root browser | **6/6**，三轮共 **18 次**严格卸载 | Projects/Task/Knowledge/PPT/Workflow/Designer；第一同步样不等待自然输入 |
| W2/W3/W4/W5 生产 browser 回归 | **49/49、31/31、29/29、49/49** | 共 158 项；均最终源码/生产构建；正常及活动手势、105px、锁定/布局、SSE、unknown/文件等原门槛保持 |
| W1 React/Ant 组件 fixture | **41/41、18 次**严格卸载检查 | 单独旧基础回归，不替代生产路由；新原始回执留在本地，原 W1 已提交证据字节保留 |
| 历史消费者组织调整后复验 | **3/3** | 已含在 W3 31 项，属重复复验，不加进唯一用例数 |
| 最终 E2E discovery | **566 项 / 77 文件可收集** | `--list` 不执行业务；没有称这 566 项通过 |

生产 browser 共 261 项（158 + 97 + 6），W1 fixture 41 项另列；重复聚焦/负控重跑不叠加为新增覆盖。原 E2E 全站最终运行未执行，W7 待项目经理门禁；最终被发现的全站 566 项与分配置执行口径不同，不把 discovery 的 skipped 字段当实际跳过执行。

精确复验命令在 frontend：

```bash
npm ci
npm run typecheck
npm run build
npm run test:tooling
npm run test:accounting
npm run patch:inspect
npx vitest run --maxWorkers=2 --reporter=json --outputFile=/tmp/w6-unit.json
node ../scripts/verify-w6-test-mapping.mjs /tmp/w6-unit.json /tmp/w6-mapping.json
npm run audit:react-only
npm run typecheck:w6-browser
PLAYWRIGHT_CHROME_EXECUTABLE=/usr/bin/chromium npx playwright test --config e2e/w2/playwright.config.ts
PLAYWRIGHT_CHROME_EXECUTABLE=/usr/bin/chromium npx playwright test --config e2e/w3/playwright.config.ts
PLAYWRIGHT_CHROME_EXECUTABLE=/usr/bin/chromium npx playwright test --config e2e/w4/playwright.config.ts
PLAYWRIGHT_CHROME_EXECUTABLE=/usr/bin/chromium npx playwright test --config e2e/w5/playwright.config.ts
npm run test:w6-browser
npm run build:w6-root-fixture
npm run test:w6-root-browser
```

W1 fixture 使用原自有 41784 loopback Vite server及 `verify-foundation.mjs`；测试结束只关闭自己启动的服务。无需后端/Maven/JAR。当前没有单独 ESLint 配置，未冒称运行独立 lint；严格 TypeScript/tooling 已执行。

## 失败、独立审查与修复

恰好复用原 A/B/C 三员（原启动回执 `gpt-6.1-sol / xhigh`），没有增员或换模型。当前工具只返回任务名称/状态，未冒称读到新的实时模型设置。作者聚焦结果与独立复核分开记录：[A工作流](workflow.md)、[B普通页/Task](ordinary.md)、[C知识/PPT/模板](knowledge-ppt-template.md)。

| 非作者发现 / 原红 | 处理和最终证据 |
| --- | --- |
| A→组长：Story stream close 抛错跳过其它 cleanup；已退休 unhealthy App 留 React DOM；迟到旧 Story GET 错写新选择 | 最小实例清理与 ticket/scope 隔离；三个永久独立原断言 RED→GREEN，全量保留 |
| B→C：无活动 DTO 时 GET 错误不可见 | 原告警始终可见；独立永久原 probe RED→GREEN |
| B→C：初始 Designer multipart generic400 可能已创建 session 却被当可重建身份拒绝 | 仅源码证明 before-write 的错误分类可确定拒绝；generic400/累积预算400/409 保持原 key/body/File UNKNOWN/BLOCK；原探针显式恢复 POST 字节/引用与不重复创建合同保留 |
| C→B/组长：Task、根入口/依赖/样式静态交叉审查 | 有限只读无新增阻塞；没有冒称另跑 B 全量；B 对 C 是两项实际独立 probe 2/2 + 67 文件哈希/名称审核，非另一轮完整254 |
| 最终全量候选 2361 pass / 2 fail | 新会话 Fork 标签改中央中文“分叉会话记录”；原 Markdown 默认展开与 W3 默认折叠以公开 `thinkingPresentation` 变体保留。W3 原测试字节未改，四个旧 Markdown 夹具只声明展开变体，全部原断言不变；真实 Designer 消息/活动消费展开。C 非作者复核及 32/32 聚焦后，全量2363绿 |
| W2/W3 首个最终浏览器批次 39 fail | 原页面级 reduced-motion 正控失效：该订阅现在确属 App 生命周期。精确 provider/产物回调/host 身份证据，页面硬0未改；未知 MQL 真实残留负控必须使 gate throw；App 根仍所有账本 MQL 硬0。A 独立审核，最终全部相关生产 browser 绿 |
| B→组长：映射脚本可能漏空收集错误 | 整体 success、非 passed suite、runtimeErrors、总数/状态全部 fail-closed；新3项 tooling证明空失败suite/跳过/失败summary不能刷绿 |
| 全站仅 discovery 发现 spec 导入 spec 收集失败 | 原84行/5663bytes历史读取合同原文移到普通注册函数，两个原 spec 显式调用；B逐字节复核，原3名称/全部断言保留；重新discovery可收集、相关真实3/3 |

旧 Vue 测试直接关联部分为 **162 文件 / 1069 个定义**（A61/452、B42/229、C47/250、组长12/138）；剩余纯 TS 原合同同样保留。逐文件完整名称/次数/实际 destination、新增31及失败列表见 `test-mapping.json`。没有删除、skip、降断言或改测无关操作；框架特定 storage/selectors/实例观察改为唯一 React 和实际 TS owner，技术替代逐模块说明。

## 仍未验证及 W7 门禁建议

- **W7 全站完整 E2E 尚未运行**。旧首页装饰选择器、旧 Element 表格 CDP provenance 正控等仍有待按实际 React 呈现整理的技术断言；不把当前零 Vue 模块依赖/入口证据误说为这些旧 E2E 全绿。W7 应按实际配置/端口组织完整矩阵，不能仅将不同配置的所有 spec 用一个端口混跑。
- 原 11 历史失败的 [W0 根因档案](../w0/historical-failures.md) 保留。Automations 合法历史消费者已实际迁移并复验，未恢复退役 write API；当前 W0 111 合同全绿不替代原11所在完整 E2E的终态。全站旧测试、字段/路径/呈现等需 W7 逐项归档。
- 窄屏是用户明确取消的范围，混合旧测试未粗暴删除，当前新验收限定桌面；触控真实设备、全部可选参数组合、各模态所有视觉状态、HMR实际切换未测。科技蓝角色流程选中没有最终PNG，截图表明确标出。
- 真实后端/数据库事务、Provider/付费模型、真实文件解析和外部服务停止证明未测。内存File、存储失败及没有服务端 by-request/持久恢复能力的接口不承诺跨刷新完整恢复；新建需求仍是显式原 key+body 幂等POST，没有发明GET receipt。
- observer target关系释放、RAF/timer/capture/自有订阅归零不等于全部对象GC回收；账本为审查持有引用。此前已断开 observer仍存活的采样边界保留，未做全部heap retaining path证明。
- 生产大包体 warning保留，普通视觉微调另归相应后续门禁，不降低安全/清理合同换取通过。W6建议可进入W7审查，**由项目经理放行**；未自行执行最终全站门禁或发布。

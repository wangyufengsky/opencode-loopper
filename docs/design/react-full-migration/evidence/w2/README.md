# W2 普通生产页面 React 迁移：本地审查

本批从 W1 `6ffc7dbef1302450e900979d5484d4f3b3ebfa80` 开始，只交付 W2。**未推送、未部署、未开始 W3–W6，也未删除 Vue。** 原 Vue Router 继续是唯一 history owner；原画布与未迁移的详情、创建、模板运行页面保留。本报告与截图均在独立分支 `feat/react-full-migration`。

## 生产覆盖与所有权

全部 14 个入口由 `frontend/src/router/index.ts` 实际注册 `W2RouteBridge.vue`，再经 `migration/w2Routes.ts` 动态加载真实 React 页面，使用 W1 React/Ant 基础组件。不存在把旧 Vue 页面嵌入 React 标记的实现。只读 Mermaid 继续使用已验收的 React 图，不重写画布交互；本轮真实 Roles 退出暴露的本地 D3 监听清理由最小锁定依赖补丁闭合，见[独立审查与复现](dependency-cleanup.md)。

| 路由 | 实际 React 页面 | 页面/命令 owner | 原断言与审查 |
| --- | --- | --- | --- |
| `/` | `secondary/HomePage.tsx` | read-only 项目/会话投影 | [七页报告](secondary.md) |
| `/projects` | `core/ProjectsPage.tsx` | Projects、公约、文档路径、辅助配置与子账号 controllers | [三页报告](core.md) |
| `/ppt` | `secondary/PptListPage.tsx` | PPT 列表 + 独立创建 operation owner | [七页报告](secondary.md) |
| `/knowledge/history` | `secondary/KnowledgeHistoryPage.tsx` | 查询/游标/归档 owner | [七页报告](secondary.md) |
| `/requirements` | `workflow/RequirementListPage.tsx` | requirementListController | [四页报告](workflow-roles.md) |
| `/workflows` | `workflow/WorkflowLibraryPage.tsx` | workflowLibraryController 的 copy/archive 原身份 | [四页报告](workflow-roles.md) |
| `/designs` | `workflow/DesignerHistoryPage.tsx` | designerHistoryController 的查询 epoch | [四页报告](workflow-roles.md) |
| `/tasks` | `core/TasksPage.tsx` | tasksController + 原 TaskStore 唯一写入/订阅端口 | [三页报告](core.md) |
| `/insights` | `secondary/InsightsPage.tsx` | 用量/质量只读 scope | [七页报告](secondary.md) |
| `/runtime` | `secondary/RuntimePage.tsx` | runtime scope + 显式 start/restart operation | [七页报告](secondary.md) |
| `/tools` | `secondary/ToolsPage.tsx` | tools/skill scope + 显式 source 开关 operation | [七页报告](secondary.md) |
| `/databases` | `secondary/DatabasePage.tsx` | databaseDraft 原 DTO/version/密码 owner | [七页报告](secondary.md) |
| `/settings` | `core/SettingsPage.tsx` | settingsController 与独立账号 owner | [三页报告](core.md) |
| `/roles` | `roles/RoleManagementPage.tsx` | roleManagementController 与 React 只读流程图 | [四页报告](workflow-roles.md) |

`shared/` 中只有纯 TS 端口、leave coordinator 与 React 可复用 chrome。组长是共享路由/bridge/TaskPort/语义生成入口的唯一作者；页面模块不创建 browser history，不导入 Vue/Pinia。边界仅给 React 稳定不可变快照和唯一现有 TaskStore 的命令端口，每个实际 route 实例持有一个订阅并立即清理。没有第二个 Task SSE/轮询 owner。

## 交互和协议

桌面主画面保留摘要与少量动作，详情/高级动作由选择展开；Escape/关闭回到主画面。当前错误、dirty、pending、UNKNOWN 和 accepted-readback 阻断保留在 context 外。原 File、key/body、ID/version/CAS 与 accepted receipt 属于 operation owner；关闭面板不取消服务端命令。未确认写入拒绝切路由，普通 dirty 使用默认“留下”的明确确认。旧确认不能丢弃后来改动的草稿。

React 19 严格生命周期在**实际 React root 边界**启用；render/effect 重放、换肤与 query 更新不启动写入，不退休活跃 owner。真实路由退出先释放视图资源，再退休每个 owner；一个 disposer 抛错也继续清理其余 owner，并显示阻断错误而不默默挂载新实例。迟到读取受 route ticket/query epoch/view lease 约束。

中央语义 registry 在 W1 冻结词汇上增加 13 个已有业务动作和 2 个已有字段，没有覆盖旧映射；122 个对象、186 个动作共享 84 个本地 Lucide 图形。三皮肤只改 tokens；链接保留浏览器 Ctrl/Cmd/Shift 等原生操作。原依赖锁文件不变，无新增依赖。

## 团队与独立复核

只复用原三名成员，历史真实启动配置均为 `gpt-6.1-sol / xhigh`；当前工具不提供平台实时模型回读，不冒称重新验证了配置。没有增加成员。组长集成共享入口并统一回归。

| 原成员 | 独占实现范围 | 非作者复核范围 |
| --- | --- | --- |
| `/root/react_flow_workflow` | workflow/roles 四页与相应测试 | B core 三页/controllers/子账号 |
| `/root/react_legacy_canvas` | core 三页与原四子组件替代 | C secondary 七页/创建与查询 owners |
| `/root/react_ppt_canvas` | secondary 七页、生产浏览器 fixture/资源观测 | A 四页与组长 route bridge/StrictMode/共享端口 |

审查发现与修复及命令证据见三个模块报告和下方最终验证。修前日志留在仓库外，本仓库只保存可审查结论、确定性测试和真实截图，不含环境日志、trace、Slack 状态或凭据。

## W0 转绿与保留红测

W0 原 28 组/111 子测试的冻结基线是 27 通过、84 失败。[原始报告](../w0/README.md) 不改写成绿报告。本批只把原 B1.2 的五项迁到真实 React 流程库 + Vue Router，原 B8.1 的四项迁到真实 React 历史设计页；保留原标题/数量和原业务断言。

九项全部通过，其中八项由红转绿、一项原有正控继续通过。明确保持源 ID/sourceRevision/expectedVersion、key/body、实际筛选、显式恢复，以及 B 比 A 先返回、旧游标追加、退休后成功/错误不写回等原合同。未通过 skip、删用例、放宽断言或旧 Vue fixture 取得这九项通过。

最终全量分类见 `verification.json`；剩余 76 项仍是失败，属于后续已定波次。本批不能表述为全站通过。原 B1.4 与 B9.1 正控仍通过，不错误列为未修缺陷。红测逐项标题与归属见 [W0 本批分类](w0-transition.md)：W3 为 31 项、W4 为 4 项、W5 为 41 项。未复活已退役 create/start/rule/import 接口。

原 11 个历史 E2E 失败的 W0 根因记录与复现保持[独立历史分类](../w0/historical-failures.md)：W0 复现后十项通过、一项 Automations 历史消费者缺口。本批未重跑这组原历史 E2E，不能把旧结果加入本批通过数；[兼容读取/导出落点](../../automations-compatibility-map.md) 和 TemplateTasks redirect 保留，消费者整合归 W3，不能以零 Vue 目标删除历史数据/读取/导出断言。

## 最终验证与边界

最终验证清单、准确计数、命令与源码哈希见 [verification.json](verification.json)，组件定义见 [components.json](components.json)，严格首样见 [resource-summary.json](resource-summary.json)。14 页及其支持组件共 **34 个生产 JSX 函数/箭头组件定义**，不计测试 helper、W1 或既有画布。浏览器使用本批源码的生产 `vite build` 后 `vite preview`，真实 Vue Router 路由和真实 React/Ant DOM，全部网络接口返回明确模拟数据；未使用 W1 preview 入口或设计 HTML 冒充本批页面。图集为 **58 张最终实际截图**，见 [真实生产页面截图](screenshots.md)。

| 最终门禁 | 实际结果 | 说明 |
| --- | --- | --- |
| 全量 unit | 1634＝1558 PASS /76 FAIL /0 skip；命令 exit 1 | 214 个测试文件；76 个失败全在既定 W0，不能称全站通过 |
| W2 专项 unit | 149/149 PASS | 12 个文件，含纯 TS owner、真正 React 页面和真实 Vue Router→React bridge 合同 |
| 原 W0 | 111＝35 PASS /76 FAIL | 原 9 个 W2 合同均通过，八项红转绿；保留其余红测 |
| Chromium 生产路由 | 49/49 PASS，0 skip /0 flaky | 14 路由×3皮肤＋7 专项；直达/reload/back/forward、选择/Escape/焦点、dirty/UNKNOWN/accepted-readback |
| 严格 SPA 首采样 | 42 路由状态＋9 次活动 pan PASS | 首样前无后续释放输入；实例 listener/RO 目标/RAF/timer/capture 均零；外部 owner 资源明示保留 |
| 全工程 typecheck / W1 foundation typecheck | exit 0 /exit 0 | 保留现有严格类型规则 |
| 生产 build | exit 0 | 六入口补丁 check 通过，保留大包体警告 |
| tooling / accounting | 33/33 /13/13 PASS | 含新增实际 ESM、React UMD/CJS 生命周期 2 项；不重复加进 unit 总数 |
| registry / Lucide | check PASS /check PASS | 122 对象、186 动作、84 本地图形，可重生成 |
| 干净 npm ci 复现 | exit 0，483 包 | 最终六入口补丁由仓库机制重装复现，锁文件无改动 |

审查修复：core 的旧草稿确认失效/模型目录草稿修订/子 owner 清理隔离有独立负控；secondary 的过期归档选择与 Ctrl 链接吞新标签，非作者原 probe 修前 0/2、修后 2/2；React 19 实际 root StrictMode 负控为 setups 2 /detach 1 /active 1 /write 0。A 独立 core 58/58，B 独立 secondary 最终 44/44（42 正式＋2 原 probe），C 核 A 和共享入口；独立数不重复累计成新增测试数量。

首轮 Chromium 37/49、第二轮 46/49 的失败保留。完整 DTO、选择器 scope、截图稳定采样与真实 pan 命中点的夹具修正没有降低业务断言；真实 D3 pane 清理由锁定补丁修复。工具 ElementHandle 注入的八个全局监听通过避免该工具入口来消除观测污染，没有按栈过滤或主动删除工具监听。专项最终 3/3、九轮通过后重跑全 49 得到最终结果。方法与副作用另经 A/B/C 非作者复核。

只检查桌面 1440/1280；窄屏是用户明确取消的本轮范围，不删除旧混合用例的桌面合同。支持和验收键盘、焦点、reduced-motion，资源门槛检查实际退出后的首个无输入采样。

真实 Java 后端、模型、文件解析、系统目录选择器、仓库/网络凭据验证、multipart 服务端幂等与真实跨刷新 File 恢复未验证；没有调用付费模型。未知结果无可核对接口时仍阻断，不承诺所有未知写均可自动或跨刷新恢复。ResizeObserver 目标关系、实例 listener/RAF/timer/capture 释放不等于所有 GC 对象已销毁；观测器自身为审查保留引用，堆内存/GC 完整回收未验收。

没有 lint 脚本；type/build/tooling 作为实际可用门禁。生产大包体警告保留在记录中，不降低告警阈值，分包优化归后续退场阶段。任务筛选区的局部密度、任务/设置的原生筛选与分区控件呈现和旧外壳视觉统一留后续集中，不能称最终全站视觉验收。未重跑原全站 305 E2E 或原画布全部 204 Chromium，也未执行 W7 无 Vue 干净安装验收；Vue、Pinia、旧页面和旧夹具继续保留。所有截图只证明其模拟桌面状态；触控硬件/pinch 与完整 GC 回收未验证。

## W3 建议

本批 finite W2 验收后可由项目经理决定 W3：PPT studio、知识库会话、模板创建/文档/来源运行与 Automations 历史读取/导出消费者。应先保持原 endpoint/key/body/File/CAS 与 accepted-readback 合同，将归属 W3 的现存 W0 红测逐项转绿；不得用界面翻新掩盖业务红证据。当前实现没有抢先迁移这些高风险页面或接管 history。

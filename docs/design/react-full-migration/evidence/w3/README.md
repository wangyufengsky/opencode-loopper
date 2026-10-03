# W3：模板、Knowledge、PPT 与历史归档生产迁移

**本批为真实 React 生产路径的本地成果，尚未发布。** 基线为 W2 `a77b86ac40f458b9633acc3691fd2209125cb7a3`，分支 `feat/react-full-migration`。Vue Router 继续是唯一 history owner；未接管 W4–W6，也没有删除 Vue/Pinia、修改后端或已验收画布底层。

先看 [三皮肤 33 张实际浏览器截图](screenshots.md)，再看 [精确验证计数与命令](verification.json)、[31 项原红测试逐项映射与剩余 45 红](w0-current.json)、[资源清理首样摘要](resources.json) 和 [源码／测试／图片 SHA-256 清单](source-manifest.json)。截图来自本轮最终生产构建与 Chromium，使用模拟 HTTP/SSE 和模拟数据，不是设计原型，也不代表真实后端或模型已验证。

## 迁移边界与单一所有者

本批 **5 条 route records、6 个 path 状态**，另保留 `/automations` 的原重定向。每个实际页面只有一个 React root；桥接仅提供现有 router、导航保护、主题和任务端口，不挂旧 Vue 页面或 Pinia 页面状态。

| 实际入口 | React 页面／唯一业务 owner | 功能与深链 |
| --- | --- | --- |
| `/template-tasks` | `TemplateTasksPage` / `catalog/controller.ts`；三家族各自原创建 owner | 模板搜索、配置、项目／分支／目录、日期模式、源码能力、文档 File 与摘要、明确创建及恢复；原需求开发深链 |
| `/template-tasks/document-runs/:id` | `DocumentTemplatePage` / `runs/runController.ts`；父 owner 注册子 owner | 进度、取消／恢复、原文／需求／澄清／补传、报告预览／下载、批次与诊断；Task／Designer 深链 |
| `/template-tasks/source-runs/:id` | `SourceTemplatePage` / 同类独立 run owner | 开始、取消、归档／恢复、冻结输入、覆盖、报告、批次重试、诊断与会话深链 |
| `/knowledge` 与 `/knowledge/:conversationId` | `KnowledgePage` / `knowledge/controller.ts` | 欢迎／既有会话、模型／来源、问答／停止、问题决策、历史消息、引用／原文、附件／来源；History／Project／Settings 深链 |
| `/ppt/:id` | `PptStudioPage` / `ppt/controller.ts` 纯 TypeScript owner | 讨论／计划全部模块、来源／上传、问题与作业、生成／导出、版本／预览、自由对象编辑／属性／自动保存；复用已验收 React canvas／navigator |
| `/automations` → `/template-tasks` → 显式“历史归档” | `HistoryDrawer` / `catalog/history.ts` 只读 owner | 九个现行 GET、版本冻结／hash／immutable、规则 health、运行与审计、关联任务只读核对、两种原格式原字节导出；默认不读归档 |

精确输入／输出／权限／旧组件映射分别见 [模板 run](template-runs.md)、[Knowledge、目录与历史消费者](knowledge-catalog-history.md)、[PPT 与浏览器](ppt-browser.md)。[components.json](components.json) 列本批 40 个生产 React JSX 定义；共享 W1/W2 和既有画布不重复计数。

## 保护与协议

渲染、StrictMode replay、换肤、进入页面和 GET/SSE 不发隐式写入。实际 owner 持原 key/body/id/version/revision/CAS 和 File；pending、unknown、accepted-readback 拦截不安全离开，普通 dirty 显式确认。关闭面板不取消服务端命令。accepted 后核对只读，不重新写；晚回、旧版本和跨 scope 返回不覆盖新页面。

端点能力逐项保留：文档创建有真实 by-request GET，404 仍 unknown；源码／普通报告没有该 GET，不编造 receipt。Knowledge 会话创建用原客户端 UUID 仅读恢复，消息有真实 by-key receipt；keyless 来源操作不能宣称按 key 恢复。批次重试是后端现行原代际 CAS 的专属显式恢复，不泛化为通用 requestKey；409 不 rebasing。PPT 精确保留原 id/key/body/revision，自动保存待决期间不丢 edit owner。

没有承诺所有操作跨刷新完整恢复。File 不持久化为 JSON；文档创建原摘要可用时仍须用户按原顺序重选同字节文件，存储拒绝只保内存。没有 lookup／持久身份的接口应保留原页恢复入口、明确能力不足，不能自动 POST 或新生成身份。见各模块报告的端点合同与正／负控。

## 三人交叉审查与实际修复

原三成员保持启动时 `gpt-6.1-sol / xhigh`，复用原 task ID；当前工具没有实时平台模型读取接口。A `/root/react_flow_workflow` 实现 run；B `/root/react_legacy_canvas` 实现目录／Knowledge／归档；C `/root/react_ppt_canvas` 实现 PPT 与 W3 browser。组长独占依赖、registry、bridge/router、共享文档 renderer、W0 retarget、构建／浏览器／集成。没有增加成员。

| 非作者复核 | 实际范围／独立复跑 | 闭合发现 |
| --- | --- | --- |
| A → B 与组长 shared | B 96 项、shared 7 项；源码／哈希／W0 映射 | 外域创建及 stop 回执隔离、FileReader retire abort、绝对引用行号；最初 SSE timer1 是 jsdom Storage 0ms，不列为已复现泄漏 |
| B → C | PPT 39 项及最新局部 CSS／投影修正 | public retire(false) 拒绝后仍保 owner／timer／原恢复身份；局部 `.ppt-page` 恢复既有 canvas CSS，不套全页、不改底层 |
| C → A 与组长 bridge | run 53 项、bridge 15 项；W2 导航终点测试变更 | accepted 异域／旧版回读不结清、父 revision 不丢子草稿／File、原 CAS；每页 beforeunload 精确释放，桥接复用不遗留旧实例 guard |

完整实际命令、原红与修后证据、hash 和限制在三份模块报告。作者自测没有当独立复核。

浏览器候选曾为 **6/31 通过、25 失败**，下一轮 **19/31 通过、12 失败**，均保留原日志。严格 detached listener 红促成本批受控归档 tabs、同根安全 Markdown／Mermaid、React+纯 Lezer 只读代码；没有过滤这些 listener、放宽 observer 或伪造 mouseup。九个 PPT 第二轮失败为桥接复用未解绑 beforeunload，已修精准归属；三个历史失败为折叠版本正文 fixture 未实际展开，补真实用户点击。

后续即使清理 31/31 通过，像素复核仍发现 PPT 局部 ancestor 缺失导致原对象 CSS 未生效。最终只补 Canvas／Navigator 的局部 `.ppt-page`，在既有六活动 case 加 DTO→屏幕位置／尺寸和 105px／20px 位移正控，并重建重拍。只读代码引用改淡色高亮以保持三主题对比。旧候选图片不冒充最终图。

W2 相关首次 45/49 失败的原因是旧测试在导航目的地仍断言 Vue root0／旧标题；更新为本批真实 React 目的地 root1 和中央标题后重跑。旧页第一次移除 microtask 的资源零断言原样保留，不把终点更新当清理放宽。

## 验收与剩余门禁

最终同一生产／测试源码结果：

| 验证 | 通过 | 失败／未选 | 结论 |
| --- | ---: | ---: | --- |
| W3 相关 unit，17 物理文件 | 226 | 0 | 比 W2 新增 218 项，另含 8 项保留 bridge；不是 226 全新增 |
| 全量 unit，230 物理文件 | 1807 / 1852 | 45 | exit1；仅 W4=4、W5=41 原 W0；0 skip |
| W0 全 111 项 | 66 | 45 | 本批 31 原红转绿；W0 非全绿 |
| W3 Chromium 生产路由 | 31 | 0 | 28 本批＋3 原 read-consistency；69 严格首样、18 活动 drag/resize |
| W2 Chromium 相关回归 | 49 | 0 | 42 普通退出＋9 活动 pan 首样 |
| Typecheck / foundation type / production build | 全通过 | 0 | 锁定 XYFlow 补丁 check 通过；保留包体警告 |
| Tooling / accounting | 33 / 13 | 0 | 正常权限运行；无独立 lint script，不冒称 lint 已跑 |

PPT 局部 CSS 最终修正后，非作者再次复跑 39/39；C 独立审最终浏览器 69 首样和 33 PNG，审计 SHA-256 `73adfdbf6000fbc214e39940b309d5988e42cc9590d679063890e91695ad487b`。18 活动样本 DTO／屏幕投影最大 CSS 量化误差 0.0104167px，保留原 0.05px 门槛；105px／20px 真实输入及整数 world geometry 正控通过。45 SSE 首样含 9 份 Knowledge 原订阅关闭一次、18 份 terminal run 原无活动订阅，不用这些样本冒称 active run SSE 已覆盖。

最终精确计数见 verification.json。W3 原 W0 的 **31 项原红全部转绿**；原 W0 全 111 项为 **66 通过、45 失败、0 skip**，剩余 **W4=4、W5=41**。全量单测退出码仍 1，不能说全站绿。原标题／case 数保留；实际 UI 禁改为负控，原身份 key/body 外部 owner 合同独立保留，不强行修改 disabled DOM 制造不可达状态。

W3 浏览器覆盖真实生产 direct／reload／back／forward、三皮肤状态保留、按选中披露、键盘／焦点、unknown 显式原身份恢复、历史 read/export、PPT 活动 drag/resize 离开。69 份首样在旧 root 第一移除 microtask 采集；不依赖后续自然输入，实例 listener／observer 关系／RAF／timer／capture 清零，外部 sentinel 保留。W2 相关回归另保全部原 49 项及其 42 次普通退出、9 轮活动 pan。

资源账本不是 GC retaining-path 分析。disconnect／remove 证明观察关系和显式资源释放，不宣称所有对象已被 GC；已断开的对象可在采样或测试强引用下存活。Ant 精确已识别的应用模块 100ms timer 单列，原 raw 保留，不用任意 stack 白名单。

没有执行全站剩余 E2E、真实 Java／模型／解析／PPTX 工具链、付费调用、物理触控设备或堆 GC 验证；窄屏按用户决定范围外。原 11 历史失败不统称全绿：本批只修合法归档消费者，并通过原 read-consistency 的真实消费者断言，其余按 [W0 历史根因表](../w0/historical-failures.md) 保留。依赖未变，没有重装或新增 npm ci 证明；最终 W7 的干净安装／零 Vue／全站门槛仍待后续授权与验收。现有大 chunk 和 Rollup PURE 注释警告记录为后续包体技术债。

建议项目经理以本批有限范围结果审 W3 门禁，再按既定计划放行 W4；本报告不构成 W4 或发布授权。

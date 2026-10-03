# W3 文档与源码模板 run 页迁移证据

本报告由生产实现作者 A `/root/react_flow_workflow` 撰写。既有启动记录指定 `gpt-6.1-sol / xhigh`；当前工具没有实时平台配置读取接口。工作区 `/workspace/opencode-loopper-react-full`，本轮基线 `a77b86ac`。文件归属仅 `frontend/src/pages/w3/templates/runs/**` 和本报告；没有修改原 Vue、Pinia、API、W0 spec、foundation、router、可靠画布或依赖。组长持有真实路由接入及 W0 retarget。本文的作者单测不充当独立验收。

## 实际生产入口及功能映射

`runs/index.tsx:1` 导出 `DocumentTemplatePage`、`SourceTemplatePage`，由组长 W3 route registry 在原唯一 VueRouter history 上真实挂载。页面只消费 `W2PageProps` 的导航、guard、retained lifecycle 和 skin；未创建另一份 history、Pinia writer 或演示数据开关。

| 原源码入口 | React 入口 / owner | 保留的实际功能 |
| --- | --- | --- |
| `views/DocumentTemplateView.vue:1` | `runs/RunPages.tsx:39` / `runController.ts:15` | 读取进度、文档限制、冻结版本、WAITING_INPUT/STOPPING 消息、上传未完成提示、canCancel/canResume、Task/Designer 深链、独立执行处置状态 |
| `views/SourceTemplateView.vue:1` | `runs/RunPages.tsx:61` / `runController.ts:15` | PENDING_START 开始、冻结输入恢复、取消确认、归档/恢复归档、源码覆盖计数、测试目录、详细设计路径、批次分页/选择/失败原因、同原所选批次重试 |
| `components/DocumentSourcesPanel.vue:14` | `DocumentPanels.tsx:17` / `documentOwners.ts:11` | 按文件惰性读取章节目录、目录分页、原正文读取携原文件 SHA、独立每通道请求代际 |
| `components/DocumentRequirementsPanel.vue:27` | `DocumentPanels.tsx:53` / `contentOwners.ts:6` | 修订分页、仅待澄清筛选、选中才详情、需求/接受条件/问题、代码证据、原文依据、已采用回答历史；DOCUMENT_SOURCE revision=0 不伪造矩阵 |
| `components/DocumentClarificationForm.vue:14` | `DocumentPanels.tsx:29` / `documentOwners.ts:36` | 本地回答、requestKey/version/requirementRevision/key/正文冻结、发送/未知期间禁改、普通 dirty 确认、原回答恢复 |
| `components/DocumentSupplementForm.vue:17` | `DocumentPanels.tsx:41` / `documentOwners.ts:67` | 读取服务端入口/原 request、真实 File 引用、选择取消不清空、格式/空文件/合并数量和大小限制、移除、收起保草稿、同 File/body/key 显式恢复 |
| `components/DocumentReportsPanel.vue:16`、`SourceArtifactsPanel.vue:17` | `ContentPanels.tsx:19` / `contentOwners.ts:75` | 目录/正文分页、选中预览、相对报告跳转、当前文件及完整 ZIP 下载、下载 URL 精确释放；Markdown 复用真实安全 RichDocument |
| `components/SourceCoveragePanel.vue:12` | `ContentPanels.tsx:11` / `contentOwners.ts:40` | 冻结就绪后逐文件覆盖读取、分页、处理依据展开/收起、失败读取可见 |
| `components/TemplateBatchRecoveryPanel.vue:22` | `RecoveryPanels.tsx:13` / `recoveryOwners.ts:10` | 服务端 ready/environment/stop-proof facets、最多 100 批选择、明确重新检查、原 CAS 域内恢复；文档进度安全变化刷新，task 模式原 5 秒只读轮询/选择暂停 |
| `components/TemplateSessionDiagnosticsPanel.vue:50` | `RecoveryPanels.tsx:28` / `recoveryOwners.ts:61` | ATTENTION/ACTIVE/ALL、前后页、选中诊断详情/允许字段摘要、复制及失败提示、会话深链、CHECK/FINALIZE/STOP 许可、停止确认、5 秒只读轮询 |

表中的 views/components/pages 路径均相对 frontend/src。所有 UI 动作使用中央语义 key、本地 SemanticIcon 和 W1 受控基础组件。原始内部 ID 只在 URL、协议、对象 key 和用户明确展开的诊断摘要使用。文档代码证据复用共享 ReadOnlyCode；未编写新 Markdown、Mermaid 或编辑器实现。桌面布局仅语义 tokens，无具体皮肤判断或新增窄屏要求。

## 写入、读取与资源所有权

`core.ts:14` 用 W1 SnapshotController 的 view lease 持有读取；ticket 同时核对 lease、通道序号和有效 scope。真实 root 的 retained dispose 才退休业务 owner；StrictMode effect cleanup 只 detach。`runController.ts:67` 精确移除 progress handler、清空 onopen/onerror 并 close EventSource，180ms 合并刷新和 10 秒 fallback timer 都有本实例资源登记。晚到成功/错误/finally 不更新退休 snapshot。

主命令 `runController.ts:80` 一次捕获 run ID、action、requestKey、expectedVersion 和排序后的 modelIds。手动 GET/SSE、选择变更和换 skin 不 mint 新身份。所有 UNKNOWN/SENDING/ACCEPTED_READBACK 阻止普通离开。原回执已接受时恢复仅读，不重新 POST；回执 id/version 无效时 readback 抛错保留 accepted 阻断，不因 applyRun 忽略而误结清。普通旧 version GET 不降低当前权威投影。

父 run 在 `runController.ts:99` 持有所有子 owner；有未确认写入的子 owner 时不允许另一子命令或主命令开始。`runController.ts:57` 的 `documentContext` 将原子面板的 revision/state/eligibility 保留到子 owner 安全结清或显式放弃草稿，而页面主任务状态继续显示最新读取。`RunPages.tsx:16` 持续展示子操作恢复与 dirty 提示；SSE 推进 revision 或使上传/回答入口 eligibility 消失不会卸载原 File/回答 owner。`parts.tsx:11` 亦阻止不安全投影替换子 controller。关闭详情不会停止服务端会话。

批次接口没有 requestKey。`batchCasOperation.ts:13` 是仅此端点的原 CAS 身份变体：原 task/document ID、每个 batch ID/version 和完整 selection DTO 不变，用户显式恢复才再次 POST。依据组长独立核查的 `TemplateBatchRetryService.retrySelected` 与 `TemplateBatchStore.java:102–124` 原代际 CAS/复用语义；未宣称通用 key 幂等，未修改 W1 的一般 keyless 能力。GET 返回新版本不会替换原 POST 的 CAS；停止证明不允许时禁止 replay；恢复 409 继续保留 UNKNOWN，而不是换成新 CAS。

诊断 keyed FINALIZE/STOP 冻结原 commandId/action/CAS。已接受写入后的 GET 失败保持 ACCEPTED_READBACK；显式恢复只 GET。已接受的同 batch/version/action 不能因后续旧 canFinalize=true 投影 mint 新命令。CHECK 与 recheck 没有 key，未知仅明确读取核对、保持未确认，不能假定 GET 版本变化证明命令成功。

File 从未 JSON 化或写入 sessionStorage；补传只持实际 native File 引用。创建阶段 hashing/创建幂等归 B 的 creation owner，run 页不重新 create 或重做其冻结摘要。没有声称未支持的跨刷新 File/未知写入恢复。强制卸载的迟到隔离不等于许可用户绕过 pending guard。

## W0 断言 retarget 接口

W0 原标题/数量/原红证据由组长保留；本作者没有编辑原 spec。旧源的基线行号按 `a77b86ac`，当前行号受组长 retarget 缩短影响，须以该基线审原断言。

| 原 W0 case 与基线行 | 数量 | 新 helper / 证据 |
| --- | ---: | --- |
| B3.2 source/document × inflight/unknown，`templates-history-w0.spec.ts:134` | 4 | `run-w0-contract.tsx:40` 的 `templateRunNavigationW0Contract`，实际 W2RouteBridge + MemoryRouter，A→B 与 exit 均留 A，POST1、原 phase 可见 |
| B3.3 source cancel/retry、document resume/cancel，原 `:147` | 4 | `run-w0-contract.tsx:59` 的 `templateRunIdentityW0Contract`，真实 refresh/选择/恢复按钮，requests[1] 与 original 完全一致 |
| B4.1 A 取消确认迟到，原 `:205` | 1 | `run-w0-contract.tsx:80` 的 `templateCancelScopeW0Contract`，真实 route B、旧受控 dialog 退休、0 POST；owner 单测另实际调用退休的原 confirm 回调证明 0 写 |
| B8.3 原目录/正文晚回，原 `:272` | 2 | `run-child-w0-contract.tsx:33` 的 `templateSourcesRetirementW0Contract`，实际 React 根先卸载且执行 retained dispose，随后 resolve；整个 snapshot 与指定 pages/bodies 均不变 |
| B8.3 clarification 晚成功，原 `:280` | 1 | `run-child-w0-contract.tsx:50`，发送时真实 textarea disabled，退休前/后 snapshot、原回答/B草稿、updated0 保留 |
| B8.3 supplement options/upload 晚回，原 `:287`、`:291` | 2 | `run-child-w0-contract.tsx:64`，options 不 open、不解 busy；原 File 引用/数量、updated0；非 disabled DOM force/emit |
| B9.3 原批次 CAS 与 diagnostic accepted read failure，`endpoint-w0.spec.ts:86`、`:93` | 2 | `run-child-w0-contract.tsx:81`、`:93`，真实 GET refresh 和明确恢复；原批次 requests[1]===requests[0]；accepted 只读/no rePOST |

共 16 个原红场景映射。额外原绿控制：B3.2 readonly source/document 用 `templateRunReadonlyLeaveW0Contract`；B9.1 batch 读/重渲染/恢复打开不自动 POST、B9.2 diagnostic 同 commandId/action/CAS、B9.3 stop-proof 通过同 child helper 的模式参数验证。pure owner 的强制退休负控与真实 route guard 是独立证据，未拿 mock navigation.go 充当真实路由退出验证。

## 作者实际执行结果与边界

最终命令在 frontend 执行：

```sh
node_modules/.bin/vitest run src/pages/w3/templates/runs/*.spec.ts src/pages/w3/templates/runs/*.spec.tsx --maxWorkers=1 --reporter=default --reporter=json --outputFile=/workspace/react-full-w3-evidence/run-all-final-v3.json
```

实际 exit0，4 文件 **53/53 PASS**：owner27、真实路由11、真实子面板10、投影/StrictMode5。原始日志 `/workspace/react-full-w3-evidence/run-all-final-v3.log`，结果 JSON 同名。早期 route collect 因并行 B catalog/Knowledge entry 尚未落盘而 ENV_BLOCKED，不计业务红；最终原入口齐备后 11 个 route case 实际通过。

v3 只补强作者持有的两个 test helper，生产文件保持 v2 原字节：`run-child-w0-contract.tsx:92`、`:105` 对 batch no-auto-write / diagnostic unknown 实际逐一重渲染三套 FoundationProvider 皮肤，保留同一 controller，逐次断言真实 skin 标记和 POST1；`run-w0-contract.tsx:86` 在真实路由 B 额外断言无 alert、无 operation phase，承接原 error 空值/acting false 语义。这属于作者修正测试覆盖，不属于对自己实现的独立审查。

另实际执行原 W0 的归属合同：

```sh
node_modules/.bin/vitest run src/w0/templates-history-w0.spec.ts src/w0/endpoint-w0.spec.ts -t 'B3\.[23]|B4.1 pending|B8.3 (sources|clarification|supplement)|B9.1 batch unknown|B9.2 diagnostic unknown|B9.3 (batch unknown|stop-proof|diagnostic accepted)' --maxWorkers=1 --reporter=default --reporter=json --outputFile=/workspace/react-full-w3-evidence/run-w0-helper-final-v3.json
```

实际 exit0，**21 PASS / 0 FAIL**；原两文件总计 58 场景中的另 37 项仅因本次 `-t` 未选择，不能计为通过或宣称删除。21 项为原红16＋readonly2＋B9三项原绿控制。原日志及 JSON `/workspace/react-full-w3-evidence/run-w0-helper-final-v3.*` 保留具体请求身份和 POST 次数。`A-retarget-static-review-final-v3.json` 用 TypeScript AST 对比 `a77b86ac` 与当前原 spec：templates-history 的26个、endpoint的12个直接 it/test 标题表达式逐字相同，无增删；这些是语法表达式数，动态展开后的58个运行场景另计。

额外必要负控定位于 `runOwners.spec.ts:87` 后：错误 id、缺失 version、旧 version 的已 fulfilled 回执仍 accepted/readback 且 POST1；普通旧 GET 不回滚；跨子/主命令只允许一 writer；已 settled 诊断仍拒旧 permission 投影重发；active batch poll 的选择暂停/精确 detach。`runProjection.spec.tsx:20` 起实际 React 检查 dirty/sending/unknown 原 File，原 DOM input 和旧 revision/context 保留、恢复原 request；`:32` 回答 revision 推进；`:42` 根 StrictMode SSE/定时器即时清理，首次卸载 snapshot/timer0 后才发晚自然回调。

来源哈希清单 `/workspace/react-full-w3-evidence/run-freeze-hashes-v3.json` 覆盖本目录 20 文件及最终两个批次 log/JSON；v2 原始证据和旧 hash 清单保留。关键哈希：

- `runController.ts`：`36d240aec01c76c69a234019e03a8107947b16b07013a186307fcfae3183ebdc`
- `RunPages.tsx`：`39766e1ba86eada6dbf40cdbd481976854fd60c30eeb010a43aa0d94f4252b89`
- `runOwners.spec.ts`：`c4444d08962daacb0d9335689898b16c3e99f57e6399b26d3eb30bc1c7a4e2ba`
- `run-child-w0-contract.tsx`：`c07543ceb68d3ec0fa15fbdb93295295daa47356fbb163cb41490575e07c475c`
- `run-w0-contract.tsx`：`537b28e4a6783b86960271e40999857f3f7a18b0d6f888e022f2e036ad17f3b4`
- 最终53项 log / JSON：`55e44db02c79203309458a38d52d2d2f7117ed62d70e214214d9b5682a1123a3` / `5049e4a38604e39ad2b90d0fe6398691a3f7a72d25ec58badfb0e2f58beec805`
- 原 W0 21 项 log / JSON：`36262621d815a7778c2e10532d7069afb162bd0b95237dc9398a9746d4709c2e` / `6c0ae535d1042bf29106a898c4723254fe8660a316f13fddf8f11033baf614fb`

本报告作者没有运行全量/typecheck/build/真实浏览器，没有调用后台或付费 Provider，没有提交。组长增量类型检查曾指出准确 DTO fixture 缺字段，已补真实字段；最终类型、全量 W0 retarget、真实 Chromium 三皮肤/桌面行为与资源账本及非作者复验由组长/C执行，未将本53项当这些门禁。原 Vue/W0 红基线可审而非回写为过去已通过。

## 非作者交叉审查

本目录生产/测试已冻结。按组长授权只读审 B Knowledge/catalog/history 与组长 shared/31 retarget；审查结论在本节明确范围、原始证据和未覆盖项，作者自测不计入该结论。

最终独立审查仅覆盖 B 的 `pages/w3/knowledge/**`、`templates/catalog/**`，组长的 `pages/w3/shared/**` 与 W3 原 W0 retarget。A 没有编辑这些生产或测试文件。B 已冻结29文件；复跑后逐一核 SHA，与 `/workspace/react-full-w3-evidence/knowledge-catalog-freeze-hashes.json` 完全相同，清单 SHA 为 `ffd2af7f7fb8151824ac47c9809a4fb7a334d709bc3f4f119f9f0c2f0da2f8be`。本节103项是非作者复跑他人既有测试及源码审查；不是 A 作者53项或自有 W0 helper 的独立验收。

首轮候选和同域 stop 回执补查均交原作者处理。实际原始前红分层如下：`independent-blockers-before.json` 三项失败中的 create 外域写入与 FileReader 未 abort 属真实缺陷；SSE 的聚合 timer1 由作者进一步定位为 jsdom 的 Storage._dispatchStorageEvent0ms，**不能计为已复现 owned180ms timer 泄漏**。`evidence-offset-before.json` 的片段高亮与 `stop-identity-before.json` 的外域停止回执各1实际失败。前红由 B 执行并保留，A 本轮实际执行的是修复后非作者复验，没有声称自己重跑了原版。

| 问题 / 严重性 | 最终只读修复证据 | 独立复验与归因 |
| --- | --- | --- |
| 创建知识会话 fulfilled 外域 receipt 可导致向错误会话继续发 message，P1 | `knowledge/controller.ts:202` 同时核对原 body.id/projectId/model 与权威原 ID GET，非法 receipt 保持 accepted readback | `knowledge/controller.spec.ts:21` 已 PASS：foreign message0、foreign GET0、原 conversation null、accepted/readback、BLOCK。前红有实际向 foreign POST 的请求证据 |
| 停止 fulfilled 外域 receipt 将 B 投影并读取 B，P2 | `knowledge/controller.ts:220` 首先核对原 conversation.id，随后才投影/刷新 | `knowledge/controller.spec.ts:22` 已 PASS：conversation 保原 ID、foreign GET0、accepted/readback、BLOCK；对应 stop 原始1 FAIL闭环 |
| native FileReader fallback 在 forced retire 时仍 LOADING，P2 | `templates/catalog/creation.ts:33–48` 本次 hashing ResourceScope 精确 abort 并清 handler、reject，摘要之后仍先检查原 scope | `templates/catalog/creation.spec.ts:13` 已 PASS：真实 native reader 先 LOADING，retire 首采 abort1/DONE/handler null、POST0；普通摘要成功与原字节恢复控制均通过 |
| 非首片段证据行高亮偏移，P2 | `knowledge/Evidence.tsx:18,27` Code view 使用 absoluteLines，Markdown 保持相对行 | `knowledge/pages.spec.tsx:36` 已 PASS：startLine50，绝对51对应 second、首行50，切阅读视图仍有实际 Markdown。更换共享 renderer 后只更新 DOM selector，原文本/行号断言不降低 |
| SSE.close 抛异常的 cleanup 尾部策略风险，未复现产品泄漏 | `knowledge/controller.ts:51–52` 分别登记 timer 与 stream cleanup，`:73` finally 清空 handler/归属 | `knowledge/controller.spec.ts:23` 以 storage:null 隔离平台事件，首次 detach timer0，异常不吞。旧聚合 timer1 不再冒称本组件资源泄漏；拆分为防御改进，未给已实跑产品红结论 |

实际非作者命令（frontend）：

```sh
node_modules/.bin/vitest run src/pages/w3/knowledge/controller.spec.ts src/pages/w3/knowledge/browser.spec.ts src/pages/w3/knowledge/pages.spec.tsx src/pages/w3/templates/catalog/creation.spec.ts src/pages/w3/templates/catalog/controller.spec.ts src/pages/w3/templates/catalog/history.spec.ts src/pages/w3/templates/catalog/pages.spec.tsx --maxWorkers=1 --reporter=default --reporter=json --outputFile=/workspace/react-full-w3-evidence/knowledge-catalog-independent-final.json
node_modules/.bin/vitest run src/pages/w3/shared/documents.spec.tsx --maxWorkers=1 --reporter=default --reporter=json --outputFile=/workspace/react-full-w3-evidence/shared-independent-final-v2.json
```

实际分别 exit0，**96/96 PASS、0 skip** 与 **7/7 PASS、0 skip**。日志和 JSON 同名前缀保留。汇总/source 核对 `/workspace/react-full-w3-evidence/A-independent-review-final.json`，sourceMismatches=[]。

其他只读协议核对：catalog `controller.ts:59–77,98–108,135–142` 持原 draftRevision、pending 子 owner 优先 BLOCK，晚 picker/preview 不覆盖改后输入，File 是实际引用；`creation.ts:149–199` 冻结 requestKey/body/字节顺序、known Task 的 keyless Start 显式读取原 ID 后才允许人工再次 Start，accepted read fail 只读；可选 storage 失败保内存，不声明 File 跨刷新。`knowledge/controller.ts:207–246,284–294` 原 question/message key/version/body 显式恢复，keyless upload unknown 无伪 lookup/写重试；`KnowledgePage.tsx:24–30,55–61` root retained owner、effect view lease、context close不stop。`history.ts:83–93,108–148` 仅原9 GET、2导出、晚读取代际隔离、原冻结合同/字节保留与本下载URL释放；`HistoryDrawer.tsx:33–41` 手动激活 tabs，Arrow/Home/End 只移动焦点，Enter/Space 才切页/read，已由 `catalog/pages.spec.tsx` 实际检验，未拿点击旧 AntTabs 冒充新路径。

组长共享旧版实际 browser strict 首采出现被移除目标上的 React portal/EditorView 本地监听未解绑；该事实不自动推论 heap leak，不能过滤 disconnected targets 充当通过。最终 `shared/RichDocument.tsx:32–48,56–82` 使用 DOMPurify html profile 后的 React 元素树，跳过 on*/style/srcdoc 属性、resolver 结果再次 validate；`:16–29` Mermaid 为直接 React child，嵌套 lists 保原层级，IO cleanup 先失效再 disconnect；`:84–91` queued RO callback 有局部 active guard。`shared/ReadOnlyCode.tsx:10–33` 使用已有 Java/JSON Lezer parser、React 文本 escape，线性范围遍历、原始绝对行号/highlight，空行没有不可见字节；没有 EditorView、自己的监听、定时器或 portal root。`shared/documents.spec.tsx:38–53` 已用真正原 observe target 回放迟到 IO callback，首采 observer0 之后才回放；`:64–70` 真实嵌套列表保持，`:55–62` 实际 Lezer readonly/原行高亮。上述7项中的 Mermaid 有明确 mock，只证明 composer/observer边界，不能推广为底层真实 diagram 或浏览器的全资源清零；C 真实 Chromium严格账本仍单独计门禁。

最终关键 hash：Knowledge controller `e5bffaeb50e1c99054041e1aaf93666a800e5d6faed8f496fd7bcf463d7ca78a`；shared RichDocument `6bfe446ca504f8514fd5b1d2d365816cc9db86152ebe6a184c59f2dbfdef6083`、ReadOnlyCode `b2af293550d5bd604c1fcf49a94ed9a6c96c1dc2528df1cbb3fc95d2ae727272`、shared spec `6b1f3bc6ebebedad1ec71e6070f4dc865314b8742fdf682a8b3c5ffe4188220f`。独立96 log/JSON `064b02ecc79e1e32e83c29f01513b4acbf6e2ea6c0cfda4f02870de47d83bfb8` / `f939dde3433340161cf96a8a8e2984e027f291de0f2a552b5396ba596dc4c66d`；独立shared7 log/JSON `e69e2b3f811c11c76d79d83e11aa2e54671f94f470aab599038a0eef85b62f35` / `277a40aa2c7ed6f633330be52eae1301fbb8787b8644d00d6697968df9b204cd`。

此审查范围内没有未关闭的确定实现/测试方法学阻塞。没有独立启动浏览器、读取真实服务或付费模型，也没有将96+7扩称完整W3/全资源/后端幂等证明。全量/typecheck/build、实际5 records×3 skins/1440和1280、生产路由及 resource账本由组长/C最终统一执行。原31 W0 red转绿的 A16+B15中，A16调用自己实现的 helper仍属作者证据；本作者对B/root的非作者审查不混入这一16。原测试标题相同不等于每个旧 positive 已全部迁到React：原 B4 reject/current、B4.2 与 B8.3 existing-prop-scope旧正控仍保留，不能据其Vue绿称本波React已经覆盖所有旧变体。

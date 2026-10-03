# W0 冻结合同：原身份恢复与离开保护

固定运行基线为 `008542f0bf02dc1c1b76f1e75429aab8452b797d`。本文件是项目经理已决定的业务验收合同，**不代表当前生产实现已满足，也不批准 W1**；当前行为结果见 [W0 汇总](README.md)。本轮只增加测试和文档，后续修复必须另经阶段门禁。

## 离开判定顺序

1. `SENDING`、`UNKNOWN`、未安全交接的 `ACCEPTED_READBACK`、上传、在途自动保存：`BLOCK`。普通确认不能放弃操作，不卸载 owner、不释放 File、不改变原请求身份；不能将 abort 读请求当成写未接受的证明。
2. 无未确认写、但有普通 dirty 文本、属性草稿或未发送 File：明确 `CONFIRM_DISCARD`。取消、Escape 保留原文本及 File；只有明确放弃才可离开。
3. 无上述风险：`ALLOW`。服务器任务运行、只读请求和 SSE 本身不强制留在页面，读订阅按实例退休。

出口包括 push/replace/back/forward、同路由换实体、关闭会销毁编辑 owner 的面板和运行时回退。纯说明面板的开关不等于 owner 退出。`beforeunload` 只是浏览器提示请求，不能保证强制刷新、崩溃或浏览器关闭后恢复完整状态。

确定回执的自有导航交接只允许原 owner 签发的原目标。`router.push` resolve 为 `NavigationFailure`、竞争导航取消或 reject 都是未交接，必须保存 receipt/原 ID；显式继续只导航或读取，不能再次 create/confirm。成功写推进版本属于正常事实，不能拿写前 CAS 版本否定本人成功回执。

## 恢复能力按真实接口冻结

| 动作 | 原身份与真实恢复能力 | 禁止的推断／后续断言 |
| --- | --- | --- |
| 新建需求 | [create POST](../../../../../frontend/src/api/workflowRuns.ts#L17) 的 `requestKey`＋冻结 project/template/revision/title/objective；[后端 replay](../../../../../src/main/java/io/opencode/loopper/service/workflow/WorkflowPlans.java#L51) 按 key/digest。UNKNOWN 仅用户显式触发相同 POST；accepted 仅导航原 receipt.id。 | **没有 New 的 by-request GET**，不新增 GET，不自动重投或换 key。真实四字段在 locked 时禁改；外部 projection 变化只在独立 owner 合同测，不改 disabled DOM。 |
| Workflow 命令／复制／归档 | 依既有 action 的 requestKey、源 ID、attempt、revision、commandVersion/modelVersion 捕获；command/model stop 实际 body 使用 `expectedVersion`，其值分别来自原 commandVersion/modelVersion；accepted 后仅原读取或导航。 | 不新增 `processVersion` 字段，不因当前选择、筛选、读取新版本重拼 unknown body；不可把 command accepted-only-read 推广成每个页都有读取回调。 |
| 普通模板 create/start | [create](../../../../../frontend/src/api/client.ts#L1704) 有 requestKey；[start](../../../../../frontend/src/api/client.ts#L1705) 只有原 taskId，[控制器](../../../../../src/main/java/io/opencode/loopper/api/TemplateTaskController.java#L39) 调用现有 Task 生命周期。 | create 已返回 ID 不再次 create；start unknown 先用原 Task 的既有读取确认状态，再按服务端允许的显式动作继续。无通用 start key，不发明字段；无法确认则阻断而非盲重发。 |
| 文档模板 create | 原 metadata/requestKey＋同会话 File 实例、顺序和 bytes/hash；有真实 [by-request GET](../../../../../frontend/src/api/client.ts#L1665)，服务端 [对应入口](../../../../../src/main/java/io/opencode/loopper/api/DocumentTemplateController.java#L48)。GET 404 仍未确认；GET 200 已明确识别原 run 的恢复阶段只导航／读取该 run，不再创建。 | metadata 持久化不等于 File 持久化；刷新后重新选择必须核对 bytes/hash/顺序。已安全找到原回执后，用户明确发起的新创建不是 UNKNOWN 恢复，不强制沿用旧 key；必须区分这两个意图。只这个 endpoint 的 GET 不能移植到 New、普通模板或 Source。 |
| Source/Document run 控制 | 原 runId/action/requestKey/expectedVersion，以及动作实际有的 modelIds；按原 GET 核对，幂等重投必须原 body。 | 读取 version 前进、切批次或同路由换 ID 不解除 unknown；不能以新 key 绕原 CAS。modal 前捕获 scope，确认后复核。 |
| 批次 retry/recheck | 原 task/run/batch ID＋[expectedVersion](../../../../../frontend/src/api/client.ts#L1693)，服务端 [CAS 选择](../../../../../src/main/java/io/opencode/loopper/service/TemplateBatchRetryService.java#L25)；原状态/失败批次读取判定后续能力。 | 没有通用 requestKey；unknown 不自动重发，也不将读回推进的 version 静默当作新重试意图。 |
| Session diagnostic recover | 接口已有 commandId、原 task/batch/version，accepted 后只读诊断结果。 | 不换 commandId；读取失败不重复已接受写。 |
| Designer 初次／后续 context-turn | [multipart](../../../../../frontend/src/api/client.ts#L1960) 的 submissionId、session/scope/workPackage、原讨论/设计 revision、content、原 Files；仅接口承诺的同身份显式恢复。 | submissionId 相同但当前 content/File 不同不是恢复；后来的可编辑草稿保持未发送。无 key 的 message/question/profile/compiler 等不能套该机制。 |
| Designer question/message/profile 等 | [实际接口](../../../../../frontend/src/api/client.ts#L1949) 使用 session/question/package ID，以及各 action 实有的 discussion/design/profile version/runId；无通用 key。 | 原 ID/version/状态显式核对后才允许服务端支持的动作；不能发明 requestKey、自动重新 answer/message，或声称无持久化时跨刷新完整恢复。 |

所有 action 保持既有 DTO、local-UI header、权限及版本域；上表不是新通用网络接口。端点无核对/幂等能力时，如实显示限制，保留本实例原身份、File 和恢复说明；不得把“不知道是否接受”解释为“未接受”。

## 身份、迟到与存储

操作捕获 endpoint/path、entity/epoch、完整 body、真实 key、版本域及 File 顺序；渲染、主题、读取、StrictMode 不发 mutation。同 owner 在途调用合并；用户显式恢复亦不能和原在途请求并行。

每个 await 后复核原 scope；旧 A 回执可以归档给 A，不能更新 B、清 B 锁或导航 B。强制退休夹具只检验迟到防御，不证明普通导航允许丢 unknown。资源退休先失效 token，再清本实例读订阅/计时器/RAF，不能销毁未确认写身份。

storage get/set/remove 失败时维持内存 owner、原请求及 File，说明刷新边界，不因存储失败生成新身份。当前接口没有持久化/by-request 能力就不承诺跨刷新完整恢复；用户身份已丢失时不能以新 key 冒称找回原操作。

## 后续 React 测试落点

W1 的纯 TS owner 合同保留相同 payload/Files、显式 resume、accepted-only-read/nav、scope/lease、CAS 和无自动 mutation 断言；各页面波次使用真实 React 控件、router blocker 和键盘/关闭出口复验。Vue MemoryRouter 的本轮失败证据是固定基线，不能用它声称 React/Ant 已通过。所有 B1–B9 及 Automations 缺项在对应验收前保持可追溯，不删红测、不降低门槛。

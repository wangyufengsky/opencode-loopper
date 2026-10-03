# W0 非作者交叉审查：Workflow、Designer、模板与恢复合同

审查人 `/root/react_ppt_canvas`；2026-10-03；运行基线 `008542f0bf02dc1c1b76f1e75429aab8452b797d`。原启动记录显式为 `gpt-6.1-sol / xhigh`；当前工具不能实时读取平台配置。本报告只读审查另外两名原组员的测试、实际日志／JSON与组长合同，本审查阶段未运行浏览器、修改作者测试或生产／后端、安装、提交或外发。自己编写的历史失败测试不作为自己的独立复核。

**结论：最终 A/B 可作为 W0 的真实失败基线；早期夹具与错误期望已分离，不把它们计为产品缺陷。业务未修，不能据此宣称安全恢复已通过或自动放行 W1。** A 的此前完整聚焦回执为 53 项：8 PASS／45 FAIL；B 使用更正后的 **final-v5**，58 项：19 PASS／39 FAIL；两批完整记录合计 111 项：27 PASS／84 FAIL，均无 skip。A 随后在同一 B1.4 test 补 SENDING／UNKNOWN 两阶段四字段禁用，另实际单项重跑 1 PASS；不把这次重跑重复计为第112项，也不把旧53回执冒称当前新字节全量。28 个父 case 中，25 REPRODUCED_FAIL、3 PASS；只有 B1.4 的分层最小合同标 complete，其余 27 partial。父 case、子测试和真实不可达负控不能混计。

## 1. 核对终证与版本边界

| 作者／实际运行 | 文件结果 | 原始证据 | 出口 |
| --- | --- | --- | --- |
| A：Workflow／Designer final | workflow 32＝5 PASS＋27 FAIL；Designer 21＝3 PASS＋18 FAIL | `/workspace/react-full-w0-evidence/workflow-designer-final.{log,json}` | exit1，53 项；6.59 秒 |
| A：B1.4 最新两阶段单项 | 当前同一 test＝1 PASS；另31只是名称未选中，无新增skip代码 | `/workspace/react-full-w0-evidence/workflow-b1-4-locked-final.{log,json}` | exit0，实际执行1项；2.60 秒 |
| B：模板／endpoint final-v5 | 模板／历史 43＝8 PASS＋35 FAIL；endpoint 15＝11 PASS＋4 FAIL | `/workspace/react-full-w0-evidence/templates-endpoint-final-v5.{log,json}` | exit1，58 项；6.18 秒 |

逐项断言、部分覆盖和未测变体分别见 [A 台账](workflow-designer-results.md)／[JSON](workflow-designer-results.json)、[B 台账](templates-history-results.md)／[JSON](templates-history-results.json)。已逐项核对 A 的 26 个冻结源码 hash、B 的 2 个源码及 2 个回执 hash，均与本地实际文件一致。A 如实分列此前完整运行、后续类型／DTO补齐和最新 B1.4 单项的冻结源码；已核最新单项源码／log／JSON hash、真实 sendingFields／unknownFields 均四项true及原请求。当前冻结版本的完整运行、类型／构建由组长集成核验，不能把名称未选中的31项记为通过。B v5 的当前两份测试与终证对应。

| 当前冻结测试 | SHA-256 |
| --- | --- |
| `frontend/src/w0/workflow-w0.spec.ts` | `471be59eddd217fb3a398e53d4ab70587baaf1d1c923fcffeb21768445bc65ae` |
| `frontend/src/w0/designer-w0.spec.ts` | `0cbbf181235a1ffc3af7477b1e7692df959b07414c903afaa96555b3960f7556` |
| `frontend/src/w0/templates-history-w0.spec.ts` | `1dd6eaf2dae11b6b1550bda42d04267e448ea6b555e056788a9c5684fca09e2c` |
| `frontend/src/w0/endpoint-w0.spec.ts` | `1ce4a37714f661bc971946451ecb5266512b0d4ba4991eaf308cd083d459e42d` |

| 实际终证 | SHA-256 |
| --- | --- |
| `workflow-designer-final.log` | `fb1da32275bf13a2cfe818a35084d855350741ce6aee73d923b2341c5c5308b0` |
| `workflow-designer-final.json` | `f36393bd41d78d99956c69e9c0b162859cafe774dac929f663a5b53b7be9059b` |
| `workflow-b1-4-locked-final.log` | `a18273fd030effdf9eee27ce7198def872b49ccf93e46aa07084659873d025c4` |
| `workflow-b1-4-locked-final.json` | `ee229658c983d238f0726bcc0ff8fb2aa27581d24a600e960ec33a9774acb0a3` |
| `templates-endpoint-final-v5.log` | `f14144325319cce774db56c9724390399e6d4cc6f67eb4a046e6cb537c6be3ce` |
| `templates-endpoint-final-v5.json` | `c12d45d26962e76cb2ae80d1015bf9865e8869eab9e539fa4339e3c787ab6e8f` |

## 2. 真实可达入口与观测方法

- A 的 `workflow-w0.spec.ts:79–91`、`designer-w0.spec.ts:53–61`，B 的 `templates-history-w0.spec.ts:33–42` 使用真实 Vue MemoryRouter／RouterView，原 SFC、guard、command／Pinia owner 运行。API／SSE延迟、modal回复及无关画布／展示投影受控；没有替换 route guard 或通过私有 VM mutation 制造业务入口。B 的 `state()` 只读观察旧 refs。
- New 真实四字段为名称、说明、项目、流程，源码 `WorkflowRequirementNewView.vue:54–62` 的 fieldset／choice 在 locked 时禁用。A 最新 `workflow-w0.spec.ts:160–183` 真实 deferred create 在途、create＝1时检查四个实际控件与retry禁用；`:177` 才 reject，UNKNOWN 四字段仍禁用、retry可用且create仍1；`:182–183` 只点击已启用retry，原body对象不变、总create2。不解除 disabled、不伪造 select/input。外部 projection 纯 owner 负控单列于 `:185–196`。New after只有导航，未给它虚构 GET。
- Designer fixture 的 `READ_ONLY/readOnly=true` 与真实控件不冲突：`DesignerView.vue:380–389` 对 `REVIEWING＋DISCUSSING_REQUIREMENT` 仍允许 composer，这是运行权限语义，不是禁止编辑。A `designer-w0.spec.ts:161,171` 在后来输入前检查实际 textarea 未禁用；question／profile 使用真实按钮，File 经真实 drop handler。没有绕过 disabled 制造“UNKNOWN 后可编辑 New”。
- A 的六子 panel 当前 `workflow-w0.spec.ts:226–231` 经真实用户按钮发命令后查询公开 exposed `canLeave()`，只证明 panel guard；`:233–239` 才是实际 Requirement＋Finish 父页 route 反例。其余五 panel 的关闭／切节点／真实父 route 不假称已测。
- B `templates-history-w0.spec.ts:83–123` 先验证实际 POST／hash/submitting 与禁用，再尝试真实路由或 UNKNOWN 后已启用输入。report start 新增两项证明已知 Task 后仍可离开／改回草稿再 create；`:126–130` 两项正控证明无本地命令的 Source／Document 允许离开，SSE close＝1、无 stop POST。
- 强制 root retirement 后另建 B 的场景只验证迟到防御，不代表通常 pending 导航允许销毁 owner。Vue 卸载会屏蔽 emit；B8.3 的红仅显示退休私有 answer／Files／options 被改变，不能夸大为新根收到 updated 或 B 被改。

## 3. 发现、更正与终证中的业务差异

| 审查点 | 具体证据与最终处理 |
| --- | --- |
| Designer 接受 Task 后的错误读取夹具 | 早期 second 用错 `getTask`，不能算产品红。final `designer-w0.spec.ts:107,112` 已用正确 getTaskOverview/getTaskAudit 夹具，getTaskOverview硬断言通过；首错在 `:115` 恢复入口缺失，`:116` 又显示新建表单。日志中 store 仍含 accepted-task，workspace 已清；不称后台重复创建或所有 receipt 丢失。 |
| endpoint 的主题与时钟夹具 | 早期 dataset 标记不是真皮肤，FileReader＋fake clock 超时不是业务失败。最终 `endpoint-w0.spec.ts:20` 调用公开 applySkin 三皮肤；`:64,75` 在 Blob 读取用真实 timer。`:47` 将真实“重新加载”置于会清 error 的 props read 前，未强造不可达按钮。v5 这些正控全部越过原夹具错误。 |
| question 的刷新边界 | `endpoint-w0.spec.ts:51–57` 是真实 1200ms 轮询及 same-props／三皮肤，无手动刷新按钮；不称按钮刷新或 React StrictMode 已覆盖。start 同原 Task 重试两次的正控也只证明现状，未证明 unknown 后先 GET 的安全恢复。 |
| B3.4 不能把 accepted GET 当 UNKNOWN | 组长指出旧夹具 GET200 已找到原 run，却强制新 bytes／顺序沿旧 key，期望不成立。最终 `templates-history-w0.spec.ts:173–188` 改为真实 ApiError404，未知期间逐每次 POST 核对 metadata/key 与第二参数 Files 的 name/hash/order；变更输入拒绝追加 POST 或显式恢复原 bytes 都合法，不要求“旧 key＋新 Files”。`:191–198` 新正控 GET200 只读原 run、零追加 POST。v5 的 404 负控仍因实际换 key／bytes／顺序而红，200 正控绿。 |
| 不可达与强持有探针的界限 | A `designer-w0.spec.ts:130–136` mandatory question 无 reject，负控 PASS 不等于 reject modal 已测；B3.4 removeItem 未有调用路径，不称抛错恢复已测。旧 refs 被测试有意持有，B8 不是 heap leak／GC证据。 |
| 资源首样 | A `designer-w0.spec.ts:185–190` timeout 真正开始、卸载首样 1→1、SSE close＝1；`:200–208` focus RAF 真正分配，route退出首样 1→1，**采样之后**才交付旧 callback，实际聚焦另一页 textarea。没有先推进时钟或触发 callback 来获取清零；该证据限定 jsdom 中已识别的自有资源。 |

剩余业务红已落在实际合同断言：B1/B2/B3/B5 的 pending 可普通确认离开；B1/B5 的 accepted 导航失败无恢复入口；B3/B6/B9 的 UNKNOWN body/key/CAS/Files 或后来草稿被替换；B4 的 A modal 迟确认实际向 A POST 并覆盖 B run；B6 退休后继续 A refresh/profile POST；B7 未退休 timeout/RAF 与存储异常被误报创建失败；B8 同实例旧查询／附件覆盖新实体及退休 refs 写回。它们是基线合同差异，未在本轮修复，也不统一推断成服务端重复写、数据库损坏或跨新根污染。

B8.1 的 unmount success/error 声明精确在当前 `templates-history-w0.spec.ts:253`、首样断言 `:259`；搜索案例始于 `:239`。报告生成器曾把两条 unmount 声明指到搜索行，作者已仅修md/json锚点，不改变运行结果。

## 4. 冻结恢复合同核对

[recovery-and-leave-contract.md](recovery-and-leave-contract.md) 的 `:7–13` 正确区分 pending/UNKNOWN/不安全 accepted-readback 的 BLOCK、普通 dirty/File 的明确确认、只读/SSE的安全离开，以及 owner 限定目标的 receipt 导航；NavigationFailure 的 resolve 不能视为交接成功。没有将 beforeunload 当成可靠刷新恢复。

`:19–27` 按端点冻结真实能力：New 无 by-request GET；Document 有现有 GET，404仍未知／200找到原run与安全后新意图区分；普通模板 create 有 key、start 只有 taskId；batch 用实际 CAS、diagnostic 用原 commandId；command/model stop的expectedVersion取真实commandVersion/modelVersion、不发明processVersion。Designer context-turn才有multipart submissionId，其余无key动作不套幂等重投。`:33–37` 要求每个 await 复核 scope、旧 finally 不解新锁、存储失败保本实例身份/File，并如实披露刷新限制。内容与最终正负控一致，无本轮新增Java接口或跨刷新File持久化承诺。

## 5. 剩余门禁与独立性

本报告认可最终记录的可追溯性与真实入口方法；没有授权业务修复或关闭准入。27 个 partial 父 case 的未测变体必须保留：全出口 push/replace/pop／同实体切换／owner 面板关闭／runtime 回退、各版本和409、accepted恢复后真正只读／导航、并发合并、保存与后来草稿、完整 File 实例／顺序、受限存储及读回能力不能被某条绿负控代替。React router blocker／StrictMode、真实React/Ant控件、桌面三皮肤键盘、浏览器卸载／GC、真实Java幂等/CAS与文件解析都不由这批Vue/jsdom覆盖。最新用户已取消窄屏设计／适配验收，不新增窄屏门槛。

自编写 C 的 [历史台账](historical-failures.md)／[JSON](historical-failures.json) 单列：原11 before 全红；after 所在四文件20项＝19 PASS／1 FAIL，原11其中10 PASS／1 FAIL；纯health2项是另外证据。根因呈现为8字段／文案过时＋2平台截图夹具＋1“退役路由预期过时并叠加 COMPATIBILITY_CONSUMER_GAP”。保留的原 Automations 可见健康合同仍 FAIL，新增 redirect 和纯health不是等价消费者通过；由 A 做非作者复核，本报告不拿 C 自审代替。

已核上述20项实际视口并逐条标 currentScope：当前桌面15＝14 PASS／1 FAIL，历史390px五项＝5 PASS均OUT_OF_SCOPE。原11中当前桌面6＝5 PASS／1 FAIL，历史窄屏5＝5 PASS。原raw、图片、hash、测试及全部业务断言保持，不重跑，不将旧窄屏结果继续算当前桌面原型验收。

组长另行执行的干净依赖、基线全量单测、typecheck/build/tooling、原全站 E2E 和新增合同用例的完整结果须分栏归档；原11／B1–B9 不 skip、不删断言、不 expected-fail 放行。当前交付仅测试／文档和真实失败证据，后续波次与生产准入仍由阶段门禁决定。

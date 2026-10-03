# W0 B3/B4/B8/B9 模板、历史与 endpoint 实测结果

基线 `008542f0bf02dc1c1b76f1e75429aab8452b797d`；2026-10-03；原团队 legacy（启动记录 gpt-6.1-sol / xhigh，未查询实时平台配置）。本轮只测试/文档，无生产、Java、依赖、浏览器、全量测试或提交改动。

**12 个父 case：2 PASS、10 REPRODUCED_FAIL；全部 coverage=partial。58 个实际 subcase：19 PASS、39 REPRODUCED_FAIL；命令 exit=1。** PASS 仅代表已列变体，不代表整条安全恢复协议或 W1 准入通过。生产合同红保持，没有 skip/drop/放宽断言。

完整逐项结果、测试行/源码锚点、请求身份证据和限制见 [结构化账本](templates-history-results.json)。原输出为 `/workspace/react-full-w0-evidence/templates-endpoint-final-v5.log` 与 `.json`，最终两 spec 的快照 `*.spec.ts.frozen` 在同目录。组长的原 1305 单测绿是另一批证据，不能抵消本轮红。

## 实际方法与证据范围

实际 `RouterView` + memory router 挂原 SFC，实际 Pinia store、Element Plus 控件、原 watcher/guard；仅 API/SSE 回执延迟、文件和后端响应受控。先验证 pending/busy 和实际禁用，再真实 `router.push` 或可编辑原生 input 改值；未向禁用控件 emit、不改 scope/guard 私有状态。观察旧 refs 只读，不注入值。源/文档流记录 SSE close，close 不代表服务器 stop。

File 使用实际 `File` 字节、FileReader arrayBuffer 与 Node WebCrypto SHA-256；metadata 不含 File，reselect 同 bytes、异 bytes、两文件反序分别跑实际 store。multipart 另比较每个 FormData metadata/key、原文件名/上传 byte buffer/hash；mock409 只是前端负控，不是 Java 幂等验证。

主题走现公开 `applySkin(..., false)` 的三种注册皮肤；同值 `setProps` 仅重渲染。当前这些命令 owner 为 Vue，没有 React StrictMode，可达性标为 UNREPRODUCED_OR_UNREACHABLE；不以 canvas StrictMode 或 setProps 冒充。Question 无手动刷新按钮，测试真实 1200ms poll read。

## 父 case 与未穷举边界

| Case | 实际 subcases | 父状态 / coverage | 已复现/通过范围 |
| --- | --- | --- | --- |
| B3.1 | 0 PASS / 9 FAIL | REPRODUCED_FAIL / partial | 创建/hash/start 处理中 busy 禁输入有效，但真实 router.push 能离开；unknown 后输入重新可编辑，换草稿发新 key；已接受 Task/start unknown 再改回草稿又 create。 |
| B3.2 | 2 PASS / 4 FAIL | REPRODUCED_FAIL / partial | Source start/Document resume 的 inflight、unknown 都允许 A→B→exit；id-watch 会清 pending。无本地命令只读页允许退出，SSE close=1 且零 stop POST。 |
| B3.3 | 0 PASS / 4 FAIL | REPRODUCED_FAIL / partial | 四动作均在 GET 3→4 后生成新 key/CAS；Source retry 同时改变选择 batch-a→batch-a+batch-b，原选择不冻结。 |
| B3.4 | 1 PASS / 4 FAIL | REPRODUCED_FAIL / partial | get/set 抛错时同输入两次 explicit retry 保同 key，仍能离开；文档 metadata reload 的 by-request404保未知，同字节重选保 key；异字节/反序实际发新 key和不同bytes/顺序。单独 accepted GET 正控只读找原run、零追加POST。 |
| B4.1 | 2 PASS / 1 FAIL | REPRODUCED_FAIL / partial | A modal pending→真实同组件路由 B→confirm 后实际 POST A，结果把 B run 变 A；当前 A 正常 confirm 单次 POST、reject 零 POST 两正控通过。 |
| B4.2 | 2 PASS / 0 FAIL | PASS / partial | A actual cancel POST 发出后强制根卸载/new B root，A success/error 都不能污染 B run/error/acting；保原 A key/CAS。 |
| B8.1 | 0 PASS / 4 FAIL | REPRODUCED_FAIL / partial | B 新查询先回、A 旧回覆盖 list/facets/cursor；旧 cursor append 混入 B；卸载后旧 read success/error 仍改退休 refs，但 debounce 被清，不新增 read。 |
| B8.2 | 0 PASS / 4 FAIL | REPRODUCED_FAIL / partial | A record 后回覆盖 B；同 attachment id 的 A late 正文进 B cache；A 已缓存跨 B 不重新读取，显示 A；退休 record error 改旧 busy/error。 |
| B8.3 | 1 PASS / 5 FAIL | REPRODUCED_FAIL / partial | 目录/正文、补传 options 卸载后写旧 refs；回答/补传 POST 完成后清旧草稿/Files。Vue unmount 自身屏蔽 updated emit，实测没有跨根业务 emit；新 B clarification 草稿未变。运行 id/revision 的既有 clarification watcher 正控通过。 |
| B9.1 | 6 PASS / 0 FAIL | PASS / partial | 实际 API fetch 的 start/batch/question/message 每次未知仅1 POST，body 无虚构 key；actual batch reload/props/render/三 applySkin/recovery open 以及 actual question 1200ms poll/props/三 applySkin 无自动重写。 |
| B9.2 | 3 PASS / 1 FAIL | REPRODUCED_FAIL / partial | report 外部 owner draft 改后恢复会替 key（UI 可编辑路径另有 B3.1）；multipart 两次同 File 确认 metadata/key/actual bytes/hash/name 相同；diagnostic unknown retry 原 action/CAS/commandId 相同；mock server samekey/different digest 返回409。 |
| B9.3 | 2 PASS / 3 FAIL | REPRODUCED_FAIL / partial | report 同草稿确实复用已知 Task id，仅事实正控，未实现先 GET；改后恢复 create3次。batch unknown GET version3→4 后重新发新 CAS；服务端 ready=false/STOPPING 投影阻断为正控。diagnostic accepted 后 GET失败/再旧 read 后重新 POST 同 commandId=2，丢 accepted 阶段。 |

### B3.1 创建三分支与已接受 Task 的导航/原意图保护

源码：`frontend/src/views/TemplateTasksView.vue:110`、`frontend/src/stores/templateTaskStore.ts:12`、`frontend/src/stores/sourceTemplateStore.ts:15`、`frontend/src/stores/documentTemplateStore.ts:31`。

创建/hash/start 处理中 busy 禁输入有效，但真实 router.push 能离开；unknown 后输入重新可编辑，换草稿发新 key；已接受 Task/start unknown 再改回草稿又 create。

实际变体：
- **REPRODUCED_FAIL** `B3.1 report: real create in flight must block route leave; disabled inputs cannot be mutated` — `frontend/src/w0/templates-history-w0.spec.ts:83`；首断言 `AssertionError: expected '/exit' to be '/template-tasks' // Object.is equality`
- **REPRODUCED_FAIL** `B3.1 source: real create in flight must block route leave; disabled inputs cannot be mutated` — `frontend/src/w0/templates-history-w0.spec.ts:83`；首断言 `AssertionError: expected '/exit' to be '/template-tasks' // Object.is equality`
- **REPRODUCED_FAIL** `B3.1 document: real create in flight must block route leave; disabled inputs cannot be mutated` — `frontend/src/w0/templates-history-w0.spec.ts:83`；首断言 `AssertionError: expected '/exit' to be '/template-tasks' // Object.is equality`
- **REPRODUCED_FAIL** `B3.1 document: hashing really active before any POST must block leave` — `frontend/src/w0/templates-history-w0.spec.ts:94`；首断言 `AssertionError: expected '/exit' to be '/template-tasks' // Object.is equality`
- **REPRODUCED_FAIL** `B3.1 report: unknown receipt cannot replace original intent via editable UI` — `frontend/src/w0/templates-history-w0.spec.ts:101`；首断言 `AssertionError: expected { …(6) } to deeply equal { …(5) }`
- **REPRODUCED_FAIL** `B3.1 source: unknown receipt cannot replace original intent via editable UI` — `frontend/src/w0/templates-history-w0.spec.ts:101`；首断言 `AssertionError: expected { …(7) } to deeply equal { …(6) }`
- **REPRODUCED_FAIL** `B3.1 document: unknown receipt cannot replace original intent via editable UI` — `frontend/src/w0/templates-history-w0.spec.ts:101`；首断言 `AssertionError: expected { …(5) } to deeply equal { …(5) }`
- **REPRODUCED_FAIL** `B3.1 report/start-inflight: accepted Task identity blocks leave and survives real editable draft` — `frontend/src/w0/templates-history-w0.spec.ts:113`；首断言 `AssertionError: expected '/exit' to be '/template-tasks' // Object.is equality`
- **REPRODUCED_FAIL** `B3.1 report/start-unknown: accepted Task identity blocks leave and survives real editable draft` — `frontend/src/w0/templates-history-w0.spec.ts:113`；首断言 `AssertionError: expected "createTemplateTask" to be called 1 times, but got 3 times`

剩余/限制：
- Source preview 进行中的离开、Source/Document 每个输入字段独立变体未测；真实 File hash 卡住/三 create/report start 已测。
- 浏览器原生刷新/关闭、完整跨进程恢复未测。

### B3.2 Source/Document 运行页 pending 作用域与安全只读离开

源码：`frontend/src/views/SourceTemplateView.vue:72`、`frontend/src/views/SourceTemplateView.vue:79`、`frontend/src/views/DocumentTemplateView.vue:48`、`frontend/src/views/DocumentTemplateView.vue:62`。

Source start/Document resume 的 inflight、unknown 都允许 A→B→exit；id-watch 会清 pending。无本地命令只读页允许退出，SSE close=1 且零 stop POST。

实际变体：
- **PASS** `B3.2 source: read-only owner without local command may leave and closes SSE without stop POST` — `frontend/src/w0/templates-history-w0.spec.ts:126`
- **PASS** `B3.2 document: read-only owner without local command may leave and closes SSE without stop POST` — `frontend/src/w0/templates-history-w0.spec.ts:126`
- **REPRODUCED_FAIL** `B3.2 source/inflight: same-record and exit navigation preserve pending scope` — `frontend/src/w0/templates-history-w0.spec.ts:132`；首断言 `AssertionError: expected '/template-tasks/source-runs/B' to be '/template-tasks/source-runs/A' // Object.is equality`
- **REPRODUCED_FAIL** `B3.2 source/unknown: same-record and exit navigation preserve pending scope` — `frontend/src/w0/templates-history-w0.spec.ts:132`；首断言 `AssertionError: expected '/template-tasks/source-runs/B' to be '/template-tasks/source-runs/A' // Object.is equality`
- **REPRODUCED_FAIL** `B3.2 document/inflight: same-record and exit navigation preserve pending scope` — `frontend/src/w0/templates-history-w0.spec.ts:132`；首断言 `AssertionError: expected '/template-tasks/document-runs/B' to be '/template-tasks/document-runs/A' // Object.is equality`
- **REPRODUCED_FAIL** `B3.2 document/unknown: same-record and exit navigation preserve pending scope` — `frontend/src/w0/templates-history-w0.spec.ts:132`；首断言 `AssertionError: expected '/template-tasks/document-runs/B' to be '/template-tasks/document-runs/A' // Object.is equality`

剩余/限制：
- 路由守卫实际挂在 RouterView owner；没有用私有 ref 改路由。
- 取消/重试动作的 A→B 独立变体、GET 卡住时只读离开未测。

### B3.3 Source retry/cancel、Document resume/cancel 未知身份冻结

源码：`frontend/src/views/SourceTemplateView.vue:88`、`frontend/src/views/DocumentTemplateView.vue:69`。

四动作均在 GET 3→4 后生成新 key/CAS；Source retry 同时改变选择 batch-a→batch-a+batch-b，原选择不冻结。

实际变体：
- **REPRODUCED_FAIL** `B3.3 source/cancel: refresh version and batch selection must not mint a new unknown command` — `frontend/src/w0/templates-history-w0.spec.ts:145`；首断言 `AssertionError: expected [ 'A', 'cancel', { …(3) } ] to deeply equal [ 'A', 'cancel', { …(2) } ]`
- **REPRODUCED_FAIL** `B3.3 source/retry: refresh version and batch selection must not mint a new unknown command` — `frontend/src/w0/templates-history-w0.spec.ts:145`；首断言 `AssertionError: expected [ 'A', 'retry', { …(3) } ] to deeply equal [ 'A', 'retry', { …(3) } ]`
- **REPRODUCED_FAIL** `B3.3 document/resume: refresh version and batch selection must not mint a new unknown command` — `frontend/src/w0/templates-history-w0.spec.ts:145`；首断言 `AssertionError: expected [ 'A', 'resume', …(1) ] to deeply equal [ 'A', 'resume', …(1) ]`
- **REPRODUCED_FAIL** `B3.3 document/cancel: refresh version and batch selection must not mint a new unknown command` — `frontend/src/w0/templates-history-w0.spec.ts:145`；首断言 `AssertionError: expected [ 'A', 'cancel', …(1) ] to deeply equal [ 'A', 'cancel', …(1) ]`

剩余/限制：
- Source retry 的仅改选择/仅改版本两种独立变体未分开；本次同时变化。
- 成功/明确拒绝/HTTP 409 的各服务端真实语义未执行。

### B3.4 可选浏览器存储与文档真实 bytes/顺序恢复

源码：`frontend/src/stores/sourceTemplateStore.ts:6`、`frontend/src/stores/documentTemplateStore.ts:16`、`frontend/src/stores/documentTemplateStore.ts:26`、`frontend/src/stores/documentTemplateStore.ts:36`。

get/set 抛错时同输入两次 explicit retry 保同 key，仍能离开；文档 metadata reload 的 by-request404保未知，同字节重选保 key；异字节/反序实际发新 key和不同bytes/顺序。单独 accepted GET 正控只读找原run、零追加POST。

实际变体：
- **REPRODUCED_FAIL** `B3.4 report: denied get/set storage retains in-memory original identity and blocks leave` — `frontend/src/w0/templates-history-w0.spec.ts:164`；首断言 `AssertionError: expected '/exit' to be '/template-tasks' // Object.is equality`
- **REPRODUCED_FAIL** `B3.4 source: denied get/set storage retains in-memory original identity and blocks leave` — `frontend/src/w0/templates-history-w0.spec.ts:164`；首断言 `AssertionError: expected '/exit' to be '/template-tasks' // Object.is equality`
- **REPRODUCED_FAIL** `B3.4 document: denied get/set storage retains in-memory original identity and blocks leave` — `frontend/src/w0/templates-history-w0.spec.ts:164`；首断言 `AssertionError: expected '/exit' to be '/template-tasks' // Object.is equality`
- **REPRODUCED_FAIL** `B3.4 document: by-request 404 remains unknown; actual File bytes/order cannot replace original operation` — `frontend/src/w0/templates-history-w0.spec.ts:173`；首断言 `AssertionError: expected { …(5) } to deeply equal { …(5) }`
- **PASS** `B3.4 document: accepted by-request receipt performs reads only and identifies original run` — `frontend/src/w0/templates-history-w0.spec.ts:191`

剩余/限制：
- removeItem 在这三个 store 没有调用路径（UNREPRODUCED_OR_UNREACHABLE，调用数0），没有伪造 throw 成功证明。
- Source/report 没有支持的 by-request 恢复；未虚构通用刷新恢复。
- 已分开 Document metadata/by-request404未知与GET200已接受仅读；畸形 metadata、reload 后存储再次失效未测。
- 真实 FileReader+WebCrypto；没有持久化/恢复 File；字节/反序 store 负控不是 UI 被禁时注入事件；允许拒绝changed输入零追加POST或原bytes/顺序恢复，绝不强制旧key+新bytes。

### B4.1 Document 取消确认窗的异步 scope

源码：`frontend/src/views/DocumentTemplateView.vue:62`、`frontend/src/views/DocumentTemplateView.vue:65`、`frontend/src/views/DocumentTemplateView.vue:70`。

A modal pending→真实同组件路由 B→confirm 后实际 POST A，结果把 B run 变 A；当前 A 正常 confirm 单次 POST、reject 零 POST 两正控通过。

实际变体：
- **REPRODUCED_FAIL** `B4.1 pending confirm A followed by real route B must never POST stale cancellation` — `frontend/src/w0/templates-history-w0.spec.ts:203`；首断言 `AssertionError: expected "documentTemplateCommand" to not be called at all, but actually been called 1 times`
- **PASS** `B4.1 reject real modal leaves original A and issues zero writes` — `frontend/src/w0/templates-history-w0.spec.ts:212`
- **PASS** `B4.1 current modal confirm submits exactly once with original A CAS` — `frontend/src/w0/templates-history-w0.spec.ts:217`

剩余/限制：
- A stale POST 的失败回执、modal pending 时根卸载未另测。
- 原页面允许 A→B 为本次基线红；不把测试路由能力称成安全回退。

### B4.2 已发 Document A POST 的强制退役隔离

源码：`frontend/src/views/DocumentTemplateView.vue:71`、`frontend/src/views/DocumentTemplateView.vue:79`。

A actual cancel POST 发出后强制根卸载/new B root，A success/error 都不能污染 B run/error/acting；保原 A key/CAS。

实际变体：
- **PASS** `B4.2 success: already-sent A POST after forced retirement cannot pollute new B owner` — `frontend/src/w0/templates-history-w0.spec.ts:222`
- **PASS** `B4.2 error: already-sent A POST after forced retirement cannot pollute new B owner` — `frontend/src/w0/templates-history-w0.spec.ts:222`

剩余/限制：
- 此 PASS 仅代表强制退役的 late callback 防线，不代表未知导航安全；前置 B3.2 hard-block 合同是红。
- 未模拟浏览器 refresh/hard close。

### B8.1 历史查询、游标与退休 owner

源码：`frontend/src/views/DesignerHistoryView.vue:97`、`frontend/src/views/DesignerHistoryView.vue:113`、`frontend/src/views/DesignerHistoryView.vue:199`。

B 新查询先回、A 旧回覆盖 list/facets/cursor；旧 cursor append 混入 B；卸载后旧 read success/error 仍改退休 refs，但 debounce 被清，不新增 read。

实际变体：
- **REPRODUCED_FAIL** `B8.1 search B resolves before A: list/facets/cursor must stay in B query` — `frontend/src/w0/templates-history-w0.spec.ts:239`；首断言 `AssertionError: expected [ 'A' ] to deeply equal [ 'B' ]`
- **REPRODUCED_FAIL** `B8.1 old cursor append after new filter cannot append A into B` — `frontend/src/w0/templates-history-w0.spec.ts:246`；首断言 `AssertionError: expected [ 'B', 'A-next' ] to deeply equal [ 'B' ]`
- **REPRODUCED_FAIL** `B8.1 success: unmount prevents late retired-owner writes and debounce revival` — `frontend/src/w0/templates-history-w0.spec.ts:253`；首断言 `AssertionError: expected [ { id: 'A', projectId: 'p', …(11) } ] to deeply equal []`
- **REPRODUCED_FAIL** `B8.1 error: unmount prevents late retired-owner writes and debounce revival` — `frontend/src/w0/templates-history-w0.spec.ts:253`；首断言 `AssertionError: expected '无法读取历史设计' to be '' // Object.is equality`

剩余/限制：
- 旧 ref 变化只证明 retired-scope 合同差异；未证明它污染一个新根实例。
- 搜索+游标已测，项目/状态/归档/排序逐字段与选择 UI 未穷举；没有触发业务写按钮。

### B8.2 冻结 Task 设计与附件 cache scope

源码：`frontend/src/views/TaskDesignHistoryView.vue:27`、`frontend/src/views/TaskDesignHistoryView.vue:52`、`frontend/src/views/TaskDesignHistoryView.vue:62`。

A record 后回覆盖 B；同 attachment id 的 A late 正文进 B cache；A 已缓存跨 B 不重新读取，显示 A；退休 record error 改旧 busy/error。

实际变体：
- **REPRODUCED_FAIL** `B8.2 frozen record A arriving after real route B cannot overwrite B` — `frontend/src/w0/templates-history-w0.spec.ts:261`；首断言 `AssertionError: expected 'A' to be 'B' // Object.is equality`
- **REPRODUCED_FAIL** `B8.2 attachment A late with same file ID cannot populate B cache` — `frontend/src/w0/templates-history-w0.spec.ts:266`；首断言 `AssertionError: expected 'A冻结正文' to be undefined`
- **REPRODUCED_FAIL** `B8.2 cached A same attachment ID must be invalidated when route becomes B` — `frontend/src/w0/templates-history-w0.spec.ts:271`；首断言 `AssertionError: expected last "getTaskDesignAttachmentPreview" call to have been called with [ 'B', 'same-file' ]`
- **REPRODUCED_FAIL** `B8.2 retired record error must not clear old busy or add error` — `frontend/src/w0/templates-history-w0.spec.ts:276`；首断言 `AssertionError: expected false to be true // Object.is equality`

剩余/限制：
- 同路由 id-watch 已测；真实 remount 附件 success/error 及缓存独立再建 owner未穷举。
- 退休旧 refs 不能等同新页污染；跨 id cache/record 的实际 B 污染分别有证据。

### B8.3 Document Sources/Clarification/Supplement 子 owner 退役

源码：`frontend/src/components/DocumentSourcesPanel.vue:14`、`frontend/src/components/DocumentSourcesPanel.vue:17`、`frontend/src/components/DocumentClarificationForm.vue:11`、`frontend/src/components/DocumentClarificationForm.vue:14`、`frontend/src/components/DocumentSupplementForm.vue:16`、`frontend/src/components/DocumentSupplementForm.vue:17`、`frontend/src/components/DocumentSupplementForm.vue:33`。

目录/正文、补传 options 卸载后写旧 refs；回答/补传 POST 完成后清旧草稿/Files。Vue unmount 自身屏蔽 updated emit，实测没有跨根业务 emit；新 B clarification 草稿未变。运行 id/revision 的既有 clarification watcher 正控通过。

实际变体：
- **REPRODUCED_FAIL** `B8.3 sources/directory: late response after root unmount cannot mutate retired refs` — `frontend/src/w0/templates-history-w0.spec.ts:280`；首断言 `AssertionError: expected { file: { items: [ { …(5) } ], …(1) } } to deeply equal {}`
- **REPRODUCED_FAIL** `B8.3 sources/body: late response after root unmount cannot mutate retired refs` — `frontend/src/w0/templates-history-w0.spec.ts:280`；首断言 `AssertionError: expected { 'file:0': { fileId: 'file', …(5) } } to deeply equal {}`
- **REPRODUCED_FAIL** `B8.3 clarification sent POST after forced root retirement cannot emit updated or clear draft` — `frontend/src/w0/templates-history-w0.spec.ts:288`；首断言 `AssertionError: expected '' to be '原始回答' // Object.is equality`
- **REPRODUCED_FAIL** `B8.3 supplement options read after unmount cannot open retired form` — `frontend/src/w0/templates-history-w0.spec.ts:295`；首断言 `AssertionError: expected true to be false // Object.is equality`
- **REPRODUCED_FAIL** `B8.3 supplement sent upload after forced root retirement cannot emit updated` — `frontend/src/w0/templates-history-w0.spec.ts:299`；首断言 `AssertionError: expected [] to have a length of 1 but got +0`
- **PASS** `B8.3 existing prop scope guards reject old child callbacks without unmount` — `frontend/src/w0/templates-history-w0.spec.ts:304`

剩余/限制：
- 目录/正文/options/两 POST success root-retire 已测；迟到 error、新 B Supplement/options 的所有组合未测。
- Sources/Supplement props change 正控未新增（相邻存在的断言未借作本轮已通过）。
- 强制卸载仅验证第二道防线，正常离开必须先阻断未知写，B3 当前红。

### B9.1 四种无 key endpoint 与非自动重写

源码：`frontend/src/api/client.ts:1697`、`frontend/src/api/client.ts:1705`、`frontend/src/api/client.ts:1849`、`frontend/src/api/client.ts:1989`、`frontend/src/components/TemplateBatchRecoveryPanel.vue:52`、`frontend/src/components/SessionMonitorPanel.vue:123`。

实际 API fetch 的 start/batch/question/message 每次未知仅1 POST，body 无虚构 key；actual batch reload/props/render/三 applySkin/recovery open 以及 actual question 1200ms poll/props/三 applySkin 无自动重写。

实际变体：
- **PASS** `B9.1 start: real fetch sends one unkeyed request on uncertain delivery` — `frontend/src/w0/endpoint-w0.spec.ts:36`
- **PASS** `B9.1 batch: real fetch sends one unkeyed request on uncertain delivery` — `frontend/src/w0/endpoint-w0.spec.ts:36`
- **PASS** `B9.1 question: real fetch sends one unkeyed request on uncertain delivery` — `frontend/src/w0/endpoint-w0.spec.ts:36`
- **PASS** `B9.1 message: real fetch sends one unkeyed request on uncertain delivery` — `frontend/src/w0/endpoint-w0.spec.ts:36`
- **PASS** `B9.1 batch unknown then rerender/read refresh/theme and recovery open never retry automatically` — `frontend/src/w0/endpoint-w0.spec.ts:45`
- **PASS** `B9.1 question unknown then real poll read/same-props/skin changes never resubmit` — `frontend/src/w0/endpoint-w0.spec.ts:51`

剩余/限制：
- 四 transport 无重试已测；start/message 的实际 page rerender/recovery open 未测；message UI owner由 A 组专题覆盖，不能借本文件 transport 替代。
- 当前命令 owner 是 Vue，无 React StrictMode 路径（UNREPRODUCED_OR_UNREACHABLE），setProps 不是 StrictMode。
- Question 无手动刷新按钮，真实 poll 已测；不宣称点击不存在按钮。
- no-key 不等于可以安全用户重发；本场景 PASS 只禁止自动重写，不证明 read-before-retry 恢复。

### B9.2 keyed create/multipart/diagnostic 的各自合同

源码：`frontend/src/stores/templateTaskStore.ts:12`、`frontend/src/api/client.ts:1657`、`frontend/src/api/client.ts:1794`、`frontend/src/components/TemplateSessionDiagnosticsPanel.vue:150`。

report 外部 owner draft 改后恢复会替 key（UI 可编辑路径另有 B3.1）；multipart 两次同 File 确认 metadata/key/actual bytes/hash/name 相同；diagnostic unknown retry 原 action/CAS/commandId 相同；mock server samekey/different digest 返回409。

实际变体：
- **REPRODUCED_FAIL** `B9.2 report external-owner negative control: changing then restoring unknown draft cannot replace original key` — `frontend/src/w0/endpoint-w0.spec.ts:59`；首断言 `AssertionError: expected { …(7) } to deeply equal { …(7) }`
- **PASS** `B9.2 multipart real File bytes and metadata identical on explicit unchanged retry` — `frontend/src/w0/endpoint-w0.spec.ts:63`
- **PASS** `B9.2 diagnostic unknown retry keeps original action/CAS/commandId` — `frontend/src/w0/endpoint-w0.spec.ts:70`
- **PASS** `B9.2 mock server rejects same key/different digest; does not claim actual Java validation` — `frontend/src/w0/endpoint-w0.spec.ts:74`

剩余/限制：
- report draft 三态只读 owner 负控，不是直接篡改 disabled UI；B3.1 独立真实 UI。
- multipart 本项同字节 explicit retry；B3.4 独立真实 reselect 字节/顺序 negative control。
- 409 是确定性 mock 约束并验证 client error，未运行 Java/真实后端，不证明其完整 digest 或幂等实现。
- accepted 后只读合同放 B9.3；并非所有 keyed API 共用一套 CAS。

### B9.3 接受阶段不能重新创建/写，batch CAS/stop proof 区分

源码：`frontend/src/stores/templateTaskStore.ts:17`、`frontend/src/components/TemplateBatchRecoveryPanel.vue:52`、`frontend/src/components/TemplateSessionDiagnosticsPanel.vue:150`、`frontend/src/components/TemplateSessionDiagnosticsPanel.vue:168`。

report 同草稿确实复用已知 Task id，仅事实正控，未实现先 GET；改后恢复 create3次。batch unknown GET version3→4 后重新发新 CAS；服务端 ready=false/STOPPING 投影阻断为正控。diagnostic accepted 后 GET失败/再旧 read 后重新 POST 同 commandId=2，丢 accepted 阶段。

实际变体：
- **PASS** `B9.3 accepted Task/start unknown unchanged retry only starts known Task` — `frontend/src/w0/endpoint-w0.spec.ts:79`
- **REPRODUCED_FAIL** `B9.3 accepted Task/start unknown changed then restored draft must never create another Task` — `frontend/src/w0/endpoint-w0.spec.ts:83`；首断言 `AssertionError: expected "createTemplateTask" to be called 1 times, but got 3 times`
- **REPRODUCED_FAIL** `B9.3 batch unknown GET advances CAS: original retry cannot silently become new version` — `frontend/src/w0/endpoint-w0.spec.ts:87`；首断言 `AssertionError: expected { task: 'task-A', …(1) } to deeply equal { task: 'task-A', …(1) }`
- **PASS** `B9.3 stop-proof projection blocks retry without inventing key` — `frontend/src/w0/endpoint-w0.spec.ts:91`
- **REPRODUCED_FAIL** `B9.3 diagnostic accepted response followed by read failure must never rePOST receipt` — `frontend/src/w0/endpoint-w0.spec.ts:94`；首断言 `AssertionError: expected "recoverTemplateSession" to be called 1 times, but got 2 times`

剩余/限制：
- 未调用真实 backend stop/CAS；这里只测试现前端依服务端投影守卫及旧 read 负控。
- unchanged start2 仅原 TaskID 复用，不能计完整安全恢复 PASS；要求明确先读确认、unknown 保留，no-key 不盲重发。
- diagnostic accepted/read-fail 真实 UI 重新可点，但 GET旧 canFinalize=true 是独立模拟陈旧读，不声称真实服务必返此组合。

## 失败分类和误报排除

最初 templates-history 38=3绿/35红，7 项是测试夹具首错（1 File.arrayBuffer 不可重定义、6 Vue Proxy structuredClone）；endpoint-first 15=9绿/6红，2 FileReader/fake timers 超时未触达合同。合计 9 个初始夹具首错不是产品缺陷。随后 batch theme/props 触发真实新 GET 清掉旧 error，使“重新加载”按钮不存在，是先后夹具错误，已改先点击真实 reload 再 props/皮肤。所有原失败输出保留；最终58无这类首错。另经root/C独立审查纠正B3.4：早先GET200已识别原run后强行要求UNKNOWN冻结不成立；final-v5改为真实ApiError404仍未识别原run、逐实际上传Files/bytes/顺序断言，并独立增加GET200只读正控。该更正保留前批次，但不沿用错误产品推断。

冻结 Task 历史与 Document stale cancel 的 A→B 是真实新页污染；Designer/子 panel 卸载后的旧 ref 更新是退休 scope 合同差异，不能写成同样串页，也不是 GC/堆泄漏证据。Vue 屏蔽卸载 owner 的 emit：Clarification/Supplement `updated` 未穿过根；草稿/Files/busy 仍被晚到回执清掉，分别断言并如实分类。B4.2 强制根退休后的 generation 防线通过；这不补足 B3 的实际导航阻断红。

B9.3 的 unchanged start 两次只证明复用已接受 Task ID，没有先读取状态，不能升级成完整安全恢复通过。无 key endpoint 仅禁止自动 POST 为 PASS，明确未知后要求读确认或保留用户决定，不允许凭本地 key 捏造幂等。Diagnostic stale read 是明确模拟的旧 canFinalize 投影，accepted receipt 不重新 POST 的合同红与真实服务器是否返回该组合分开。

## 命令与哈希

```text
cwd=/workspace/opencode-loopper-react-full/frontend
npx vitest run src/w0/endpoint-w0.spec.ts src/w0/templates-history-w0.spec.ts --maxWorkers=1 --reporter=verbose --reporter=json --outputFile=/workspace/react-full-w0-evidence/templates-endpoint-final-v5.json
exit=1; 2 files; 58 tests; 19 pass; 39 fail
```

| 冻结证据 | SHA-256 |
| --- | --- |
| `/workspace/opencode-loopper-react-full/frontend/src/w0/templates-history-w0.spec.ts` | `1dd6eaf2dae11b6b1550bda42d04267e448ea6b555e056788a9c5684fca09e2c` |
| `/workspace/opencode-loopper-react-full/frontend/src/w0/endpoint-w0.spec.ts` | `1ce4a37714f661bc971946451ecb5266512b0d4ba4991eaf308cd083d459e42d` |
| `/workspace/react-full-w0-evidence/templates-endpoint-final-v5.log` | `f14144325319cce774db56c9724390399e6d4cc6f67eb4a046e6cb537c6be3ce` |
| `/workspace/react-full-w0-evidence/templates-endpoint-final-v5.json` | `c12d45d26962e76cb2ae80d1015bf9865e8869eab9e539fa4339e3c787ab6e8f` |

## Automations 落点与准入

具体落点已在 [兼容映射](../../automations-compatibility-map.md) 冻结为 TemplateTasks 用户主动打开的四页签只读 Drawer，9 GET/两个 GET 导出每项有 consumer、字段、触发和断言；唯一 legacy read controller，不分出第二 writer。当前没有正式消费者，未实现/未执行；10 个旧写保持退役，不复活 MANUAL/AUTO_START/REVIEW_REQUIRED、import-preview-confirm 或 secret 签发。旧数据和内部历史拒绝/持久化断言不得据迁移删除。W3 生产授权及动态行为门禁另行取得；本轮结果不开放 W1。

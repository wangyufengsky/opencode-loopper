# W0：W2 转绿与保留失败

固定 W0 原始证据不改写；本表来自 W2 最终全量单测，不是静态推测。111 项＝35 通过、76 失败、0 skip。九项 W2 原合同全部通过，八项由红转绿。

| 组 | 当前通过 | 当前失败 | 后续归属 |
| --- | ---: | ---: | --- |
| B1.1 | 1 | 6 | W5 |
| B1.2 | 5 | 0 | W2 已绿 |
| B1.3 | 1 | 2 | W5 |
| B1.4 | 2 | 0 | 原正控通过 |
| B2.1 | 0 | 13 | W5 |
| B2.2 | 0 | 1 | W5 |
| B2.3 | 0 | 1 | W5 |
| B3.1 | 0 | 9 | W3 |
| B3.2 | 2 | 4 | W3 |
| B3.3 | 0 | 4 | W3 |
| B3.4 | 1 | 4 | W3 |
| B4.1 | 2 | 1 | W3 |
| B4.2 | 2 | 0 | 原正控通过 |
| B5.1 | 1 | 4 | W5 |
| B5.2 | 0 | 4 | W5 |
| B5.3 | 0 | 2 | W5 |
| B6.1 | 1 | 1 | W5 |
| B6.2 | 0 | 2 | W5 |
| B6.3 | 0 | 2 | W5 |
| B7.1 | 0 | 1 | W5 |
| B7.2 | 0 | 1 | W5 |
| B7.3 | 1 | 1 | W5 |
| B8.1 | 4 | 0 | W2 已绿 |
| B8.2 | 0 | 4 | W4 |
| B8.3 | 1 | 5 | W3 |
| B9.1 | 6 | 0 | 原正控通过 |
| B9.2 | 3 | 1 | W3 |
| B9.3 | 2 | 3 | W3 |

## 九项实际 React 合同

B1.2 五项仍经生产 W2RouteBridge、实际 MemoryRouter 与 React WorkflowLibraryPage 执行；四个 pending/unknown copy/archive 场景不得离开，原第五项保持筛选后原 body/key 的显式恢复。B8.1 四项经 React DesignerHistoryPage 验证乱序、旧追加与根退休成功/错误。原标题、数量和原业务断言保留。

原定义保存在 W0/W1 commit 及 W0 文档；当前 `src/w0/workflow-w0.spec.ts`、`templates-history-w0.spec.ts` 仅重定向这九项 body。没有删、skip 或降断言。

## 76 项红测逐项标题

W3 31、W4 4、W5 41。仍全部保留为失败，不能用 W2 新增通过数抵消。表中标题直接来自最后 JSON，源码行号以文件中的原 describe/test 为准。

| 波次 | 组 | 原文件 | 未满足断言标题 |
| --- | --- | --- | --- |
| W3 | B3.1 | [templates-history-w0.spec.ts](../../../../../frontend/src/w0/templates-history-w0.spec.ts) | W0 B3 creation/run identity and navigation B3.1 document: hashing really active before any POST must block leave |
| W3 | B3.1 | [templates-history-w0.spec.ts](../../../../../frontend/src/w0/templates-history-w0.spec.ts) | W0 B3 creation/run identity and navigation B3.1 document: real create in flight must block route leave; disabled inputs cannot be mutated |
| W3 | B3.1 | [templates-history-w0.spec.ts](../../../../../frontend/src/w0/templates-history-w0.spec.ts) | W0 B3 creation/run identity and navigation B3.1 document: unknown receipt cannot replace original intent via editable UI |
| W3 | B3.1 | [templates-history-w0.spec.ts](../../../../../frontend/src/w0/templates-history-w0.spec.ts) | W0 B3 creation/run identity and navigation B3.1 report/start-inflight: accepted Task identity blocks leave and survives real editable draft |
| W3 | B3.1 | [templates-history-w0.spec.ts](../../../../../frontend/src/w0/templates-history-w0.spec.ts) | W0 B3 creation/run identity and navigation B3.1 report/start-unknown: accepted Task identity blocks leave and survives real editable draft |
| W3 | B3.1 | [templates-history-w0.spec.ts](../../../../../frontend/src/w0/templates-history-w0.spec.ts) | W0 B3 creation/run identity and navigation B3.1 report: real create in flight must block route leave; disabled inputs cannot be mutated |
| W3 | B3.1 | [templates-history-w0.spec.ts](../../../../../frontend/src/w0/templates-history-w0.spec.ts) | W0 B3 creation/run identity and navigation B3.1 report: unknown receipt cannot replace original intent via editable UI |
| W3 | B3.1 | [templates-history-w0.spec.ts](../../../../../frontend/src/w0/templates-history-w0.spec.ts) | W0 B3 creation/run identity and navigation B3.1 source: real create in flight must block route leave; disabled inputs cannot be mutated |
| W3 | B3.1 | [templates-history-w0.spec.ts](../../../../../frontend/src/w0/templates-history-w0.spec.ts) | W0 B3 creation/run identity and navigation B3.1 source: unknown receipt cannot replace original intent via editable UI |
| W3 | B3.2 | [templates-history-w0.spec.ts](../../../../../frontend/src/w0/templates-history-w0.spec.ts) | W0 B3 creation/run identity and navigation B3.2 document/inflight: same-record and exit navigation preserve pending scope |
| W3 | B3.2 | [templates-history-w0.spec.ts](../../../../../frontend/src/w0/templates-history-w0.spec.ts) | W0 B3 creation/run identity and navigation B3.2 document/unknown: same-record and exit navigation preserve pending scope |
| W3 | B3.2 | [templates-history-w0.spec.ts](../../../../../frontend/src/w0/templates-history-w0.spec.ts) | W0 B3 creation/run identity and navigation B3.2 source/inflight: same-record and exit navigation preserve pending scope |
| W3 | B3.2 | [templates-history-w0.spec.ts](../../../../../frontend/src/w0/templates-history-w0.spec.ts) | W0 B3 creation/run identity and navigation B3.2 source/unknown: same-record and exit navigation preserve pending scope |
| W3 | B3.3 | [templates-history-w0.spec.ts](../../../../../frontend/src/w0/templates-history-w0.spec.ts) | W0 B3 creation/run identity and navigation B3.3 document/cancel: refresh version and batch selection must not mint a new unknown command |
| W3 | B3.3 | [templates-history-w0.spec.ts](../../../../../frontend/src/w0/templates-history-w0.spec.ts) | W0 B3 creation/run identity and navigation B3.3 document/resume: refresh version and batch selection must not mint a new unknown command |
| W3 | B3.3 | [templates-history-w0.spec.ts](../../../../../frontend/src/w0/templates-history-w0.spec.ts) | W0 B3 creation/run identity and navigation B3.3 source/cancel: refresh version and batch selection must not mint a new unknown command |
| W3 | B3.3 | [templates-history-w0.spec.ts](../../../../../frontend/src/w0/templates-history-w0.spec.ts) | W0 B3 creation/run identity and navigation B3.3 source/retry: refresh version and batch selection must not mint a new unknown command |
| W3 | B3.4 | [templates-history-w0.spec.ts](../../../../../frontend/src/w0/templates-history-w0.spec.ts) | W0 B3 creation/run identity and navigation B3.4 document: by-request 404 remains unknown; actual File bytes/order cannot replace original operation |
| W3 | B3.4 | [templates-history-w0.spec.ts](../../../../../frontend/src/w0/templates-history-w0.spec.ts) | W0 B3 creation/run identity and navigation B3.4 document: denied get/set storage retains in-memory original identity and blocks leave |
| W3 | B3.4 | [templates-history-w0.spec.ts](../../../../../frontend/src/w0/templates-history-w0.spec.ts) | W0 B3 creation/run identity and navigation B3.4 report: denied get/set storage retains in-memory original identity and blocks leave |
| W3 | B3.4 | [templates-history-w0.spec.ts](../../../../../frontend/src/w0/templates-history-w0.spec.ts) | W0 B3 creation/run identity and navigation B3.4 source: denied get/set storage retains in-memory original identity and blocks leave |
| W3 | B4.1 | [templates-history-w0.spec.ts](../../../../../frontend/src/w0/templates-history-w0.spec.ts) | W0 B4 document cancellation scope B4.1 pending confirm A followed by real route B must never POST stale cancellation |
| W3 | B8.3 | [templates-history-w0.spec.ts](../../../../../frontend/src/w0/templates-history-w0.spec.ts) | W0 B8 history and child retirement B8.3 clarification sent POST after forced root retirement cannot emit updated or clear draft |
| W3 | B8.3 | [templates-history-w0.spec.ts](../../../../../frontend/src/w0/templates-history-w0.spec.ts) | W0 B8 history and child retirement B8.3 sources/body: late response after root unmount cannot mutate retired refs |
| W3 | B8.3 | [templates-history-w0.spec.ts](../../../../../frontend/src/w0/templates-history-w0.spec.ts) | W0 B8 history and child retirement B8.3 sources/directory: late response after root unmount cannot mutate retired refs |
| W3 | B8.3 | [templates-history-w0.spec.ts](../../../../../frontend/src/w0/templates-history-w0.spec.ts) | W0 B8 history and child retirement B8.3 supplement options read after unmount cannot open retired form |
| W3 | B8.3 | [templates-history-w0.spec.ts](../../../../../frontend/src/w0/templates-history-w0.spec.ts) | W0 B8 history and child retirement B8.3 supplement sent upload after forced root retirement cannot emit updated |
| W3 | B9.2 | [endpoint-w0.spec.ts](../../../../../frontend/src/w0/endpoint-w0.spec.ts) | W0 B9 endpoint contracts B9.2 report external-owner negative control: changing then restoring unknown draft cannot replace original key |
| W3 | B9.3 | [endpoint-w0.spec.ts](../../../../../frontend/src/w0/endpoint-w0.spec.ts) | W0 B9 endpoint contracts B9.3 accepted Task/start unknown changed then restored draft must never create another Task |
| W3 | B9.3 | [endpoint-w0.spec.ts](../../../../../frontend/src/w0/endpoint-w0.spec.ts) | W0 B9 endpoint contracts B9.3 batch unknown GET advances CAS: original retry cannot silently become new version |
| W3 | B9.3 | [endpoint-w0.spec.ts](../../../../../frontend/src/w0/endpoint-w0.spec.ts) | W0 B9 endpoint contracts B9.3 diagnostic accepted response followed by read failure must never rePOST receipt |
| W4 | B8.2 | [templates-history-w0.spec.ts](../../../../../frontend/src/w0/templates-history-w0.spec.ts) | W0 B8 history and child retirement B8.2 attachment A late with same file ID cannot populate B cache |
| W4 | B8.2 | [templates-history-w0.spec.ts](../../../../../frontend/src/w0/templates-history-w0.spec.ts) | W0 B8 history and child retirement B8.2 cached A same attachment ID must be invalidated when route becomes B |
| W4 | B8.2 | [templates-history-w0.spec.ts](../../../../../frontend/src/w0/templates-history-w0.spec.ts) | W0 B8 history and child retirement B8.2 frozen record A arriving after real route B cannot overwrite B |
| W4 | B8.2 | [templates-history-w0.spec.ts](../../../../../frontend/src/w0/templates-history-w0.spec.ts) | W0 B8 history and child retirement B8.2 retired record error must not clear old busy or add error |
| W5 | B1.1 | [workflow-w0.spec.ts](../../../../../frontend/src/w0/workflow-w0.spec.ts) | B1.1 New unresolved create navigation sending/back must retain original owner |
| W5 | B1.1 | [workflow-w0.spec.ts](../../../../../frontend/src/w0/workflow-w0.spec.ts) | B1.1 New unresolved create navigation sending/push must retain original owner |
| W5 | B1.1 | [workflow-w0.spec.ts](../../../../../frontend/src/w0/workflow-w0.spec.ts) | B1.1 New unresolved create navigation sending/replace must retain original owner |
| W5 | B1.1 | [workflow-w0.spec.ts](../../../../../frontend/src/w0/workflow-w0.spec.ts) | B1.1 New unresolved create navigation unknown/back must retain original owner |
| W5 | B1.1 | [workflow-w0.spec.ts](../../../../../frontend/src/w0/workflow-w0.spec.ts) | B1.1 New unresolved create navigation unknown/push must retain original owner |
| W5 | B1.1 | [workflow-w0.spec.ts](../../../../../frontend/src/w0/workflow-w0.spec.ts) | B1.1 New unresolved create navigation unknown/replace must retain original owner |
| W5 | B1.3 | [workflow-w0.spec.ts](../../../../../frontend/src/w0/workflow-w0.spec.ts) | B1.3 accepted New receipt survives failed handoff cancelled retains receipt and offers navigation-only recovery |
| W5 | B1.3 | [workflow-w0.spec.ts](../../../../../frontend/src/w0/workflow-w0.spec.ts) | B1.3 accepted New receipt survives failed handoff guard-false retains receipt and offers navigation-only recovery |
| W5 | B2.1 | [workflow-w0.spec.ts](../../../../../frontend/src/w0/workflow-w0.spec.ts) | B2.1 real nested command guards actual Requirement parent refuses route leave while its Finish child is unknown |
| W5 | B2.1 | [workflow-w0.spec.ts](../../../../../frontend/src/w0/workflow-w0.spec.ts) | B2.1 real nested command guards candidate/sending refuses leaving actual command panel |
| W5 | B2.1 | [workflow-w0.spec.ts](../../../../../frontend/src/w0/workflow-w0.spec.ts) | B2.1 real nested command guards candidate/unknown refuses leaving actual command panel |
| W5 | B2.1 | [workflow-w0.spec.ts](../../../../../frontend/src/w0/workflow-w0.spec.ts) | B2.1 real nested command guards commit/sending refuses leaving actual command panel |
| W5 | B2.1 | [workflow-w0.spec.ts](../../../../../frontend/src/w0/workflow-w0.spec.ts) | B2.1 real nested command guards commit/unknown refuses leaving actual command panel |
| W5 | B2.1 | [workflow-w0.spec.ts](../../../../../frontend/src/w0/workflow-w0.spec.ts) | B2.1 real nested command guards finish/sending refuses leaving actual command panel |
| W5 | B2.1 | [workflow-w0.spec.ts](../../../../../frontend/src/w0/workflow-w0.spec.ts) | B2.1 real nested command guards finish/unknown refuses leaving actual command panel |
| W5 | B2.1 | [workflow-w0.spec.ts](../../../../../frontend/src/w0/workflow-w0.spec.ts) | B2.1 real nested command guards human/sending refuses leaving actual command panel |
| W5 | B2.1 | [workflow-w0.spec.ts](../../../../../frontend/src/w0/workflow-w0.spec.ts) | B2.1 real nested command guards human/unknown refuses leaving actual command panel |
| W5 | B2.1 | [workflow-w0.spec.ts](../../../../../frontend/src/w0/workflow-w0.spec.ts) | B2.1 real nested command guards push/sending refuses leaving actual command panel |
| W5 | B2.1 | [workflow-w0.spec.ts](../../../../../frontend/src/w0/workflow-w0.spec.ts) | B2.1 real nested command guards push/unknown refuses leaving actual command panel |
| W5 | B2.1 | [workflow-w0.spec.ts](../../../../../frontend/src/w0/workflow-w0.spec.ts) | B2.1 real nested command guards writeback/sending refuses leaving actual command panel |
| W5 | B2.1 | [workflow-w0.spec.ts](../../../../../frontend/src/w0/workflow-w0.spec.ts) | B2.1 real nested command guards writeback/unknown refuses leaving actual command panel |
| W5 | B2.2 | [workflow-w0.spec.ts](../../../../../frontend/src/w0/workflow-w0.spec.ts) | B2.2 original process stop identity retains command version/attempt on explicit retry and blocks leaving unknown stop |
| W5 | B2.3 | [workflow-w0.spec.ts](../../../../../frontend/src/w0/workflow-w0.spec.ts) | B2.3 accepted write/readback split actual Finish child retains accepted readback and only rereads on recovery |
| W5 | B5.1 | [designer-w0.spec.ts](../../../../../frontend/src/w0/designer-w0.spec.ts) | B5.1 ordinary Designer drafts require real route confirmation followup-file: declining leave preserves draft |
| W5 | B5.1 | [designer-w0.spec.ts](../../../../../frontend/src/w0/designer-w0.spec.ts) | B5.1 ordinary Designer drafts require real route confirmation followup-text: declining leave preserves draft |
| W5 | B5.1 | [designer-w0.spec.ts](../../../../../frontend/src/w0/designer-w0.spec.ts) | B5.1 ordinary Designer drafts require real route confirmation initial-file: declining leave preserves draft |
| W5 | B5.1 | [designer-w0.spec.ts](../../../../../frontend/src/w0/designer-w0.spec.ts) | B5.1 ordinary Designer drafts require real route confirmation initial-text: declining leave preserves draft |
| W5 | B5.2 | [designer-w0.spec.ts](../../../../../frontend/src/w0/designer-w0.spec.ts) | B5.2 unresolved Designer writes forbid route leave followup/sending retains File owner |
| W5 | B5.2 | [designer-w0.spec.ts](../../../../../frontend/src/w0/designer-w0.spec.ts) | B5.2 unresolved Designer writes forbid route leave followup/unknown retains File owner |
| W5 | B5.2 | [designer-w0.spec.ts](../../../../../frontend/src/w0/designer-w0.spec.ts) | B5.2 unresolved Designer writes forbid route leave initial/sending retains File owner |
| W5 | B5.2 | [designer-w0.spec.ts](../../../../../frontend/src/w0/designer-w0.spec.ts) | B5.2 unresolved Designer writes forbid route leave initial/unknown retains File owner |
| W5 | B5.3 | [designer-w0.spec.ts](../../../../../frontend/src/w0/designer-w0.spec.ts) | B5.3 accepted Task handoff retains original identity guard-false: confirmed Task retains an explicit navigation-only recovery |
| W5 | B5.3 | [designer-w0.spec.ts](../../../../../frontend/src/w0/designer-w0.spec.ts) | B5.3 accepted Task handoff retains original identity reject: confirmed Task retains an explicit navigation-only recovery |
| W5 | B6.1 | [designer-w0.spec.ts](../../../../../frontend/src/w0/designer-w0.spec.ts) | B6.1 retired question callbacks stay with original owner reply accepted after retirement must not launch a new A refresh or alter independent B |
| W5 | B6.2 | [designer-w0.spec.ts](../../../../../frontend/src/w0/designer-w0.spec.ts) | B6.2 profile preview/modal await scope modal: retirement invalidates profile action before mutation |
| W5 | B6.2 | [designer-w0.spec.ts](../../../../../frontend/src/w0/designer-w0.spec.ts) | B6.2 profile preview/modal await scope preview: retirement invalidates profile action before mutation |
| W5 | B6.3 | [designer-w0.spec.ts](../../../../../frontend/src/w0/designer-w0.spec.ts) | B6.3 follow-up immutable multipart operation and later draft typing while a real send is in-flight remains an unsent draft after original acknowledgement |
| W5 | B6.3 | [designer-w0.spec.ts](../../../../../frontend/src/w0/designer-w0.spec.ts) | B6.3 follow-up immutable multipart operation and later draft unknown recovery uses original body/File and leaves later reachable edits unsent |
| W5 | B7.1 | [designer-w0.spec.ts](../../../../../frontend/src/w0/designer-w0.spec.ts) | B7.1 owned terminal retry timeout immediate retirement first snapshot clears active retry before any natural callback completes |
| W5 | B7.2 | [designer-w0.spec.ts](../../../../../frontend/src/w0/designer-w0.spec.ts) | B7.2 owned composer focus RAF immediate retirement retirement cancels queued composer focus before it can focus another page |
| W5 | B7.3 | [designer-w0.spec.ts](../../../../../frontend/src/w0/designer-w0.spec.ts) | B7.3 storage failures cannot discard accepted identity accepted initial File receipt stays recoverable when workspace persistence fails |

## 独立历史 E2E

原 11 项历史失败的根因与 W0 重跑前后证据见 [historical-failures.md](../w0/historical-failures.md)。本批不再跑这一独立历史组；Automations 历史消费者仍未闭合，不计为通过；现行 redirect 和历史读取/导出合同仍在，W3 必须按 compatibility map 补消费者，不复活退役写入口。

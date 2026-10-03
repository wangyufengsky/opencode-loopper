# 控制器、导航与资源：实施前冻结的接口设计

基线：`a3c692d38925206883f2b0a1255479108cfd439e`。以下 TypeScript 是**文档中的设计草案**，不是已实现模块，不改 DTO 或后端。W1 需用现有 endpoint 和合同测试验证接口足够，再冻结；不得为了套统一接口给没有 key/CAS 的 endpoint 新添协议字段。

## 1. 单一状态、命令与订阅所有者

```ts
type Identity = Readonly<{ domain: string; id: string; epoch: number }>
type Unsubscribe = () => void
interface SnapshotPort<T> {
  getSnapshot(): Readonly<T> // 未变化时同一引用，变化时不可变新快照
  subscribe(listener: () => void): Unsubscribe
}
type LeaveDecision =
  | { kind: 'ALLOW' }
  | { kind: 'CONFIRM_DISCARD'; description: string }
  | { kind: 'BLOCK'; reason: string; recoveryAction: string }
interface PageOwner<T> extends SnapshotPort<T> {
  readonly identity: Identity
  canLeave(): LeaveDecision
  attachView(): Unsubscribe // 每次独立view lease；返回的unsubscribe幂等，不创建/启动业务
}
```

`ApplicationKernel` 按 domain/id 建立 controller，API/storage/clock/resource scope 都注入。Zustand vanilla 只存不可变投影；React `useStore`／`useSyncExternalStore` 只订阅。原 Pinia 暂时是 delegate，不另持一份会写的状态和原 actions。全局 accounting 与主题、Task route stream 分 scope；离开 Task 不关闭全局 owner。

显式 `create/start/save/answer/stop/upload` 等领域方法属于各自 controller，不用一个通用 `dispatch(url, data)` 打散权限与回执。进入、render、StrictMode setup 重放、主题变化、route loader 都不发业务写命令。UI lease 可释放读资源，但不能因此注销 unresolved write 身份或说服务器已经停止。

## 2. 回执与恢复按 endpoint 能力区分

```ts
type ReceiptPhase = 'IDLE' | 'SENDING' | 'UNKNOWN' | 'ACCEPTED_READBACK' | 'SETTLED'
type RecoveryPlan =
  | { kind: 'READ_ORIGINAL' } // 已接受或接口能按现有identity查询
  | { kind: 'RETRY_IDENTICAL' } // 仅原endpoint承诺幂等且用户显式恢复；绝不自动重发
  | { kind: 'BLOCKED'; explanation: string }
interface OperationOwner {
  readonly identity: Identity
  readonly phase: ReceiptPhase
  readonly recovery: RecoveryPlan
  resumeOriginal(): Promise<void>
}
```

业务操作捕获不可变的path、payload、requestKey/commandId（该endpoint真有时）、expectedVersion/revision（真有时）、File对象引用与原顺序。unknown不从当前表单重新拼body；accepted后只read，不再发mutation。没有通用key的模板start/batch或部分Designer动作使用现有ID/version/read，不一律重发。AbortController取消读取不证明写入停止；客户端停止状态须遵守原后端证明。

恢复能力逐endpoint登记，不能将`READ_ORIGINAL`解释为每个接口都有by-request GET。RequirementNew使用[原create POST](../../../frontend/src/api/workflowRuns.ts#L17)，后端[按原key／摘要重放](../../../src/main/java/io/opencode/loopper/service/workflow/WorkflowPlans.java#L51)；当前无公开按requestKey查询GET。UNKNOWN时只提供用户显式“恢复本次提交”的同key／冻结body幂等POST；已知receipt.id后只打开／读取该需求。独立原型“查询原回执”没有网络行为，不证明这个GET存在。没有受支持的恢复能力则`BLOCKED`，不得生成新key或推测写入未接受。

409、拒绝或读取失败不清本地草稿。每个await后复核captured identity/epoch与该操作的版本域：旧A回执可以归档到A，但不能更新B、清B锁或导航B。CAS按endpoint判定；**成功写入会推进版本**，不能机械要求读回version等于提交前expectedVersion。本人回执的新revision供后续layout/readback使用，别把正常版本推进误判成迟到。modal显示之前捕获scope，确认之后再次复核；不能在confirm后才采新scope，把旧动作错归新页面。

AppSettings只有原settings PUT，没有CAS version；这里不增加expectedVersion。Database、角色激活/ZIP发布、PPT、Workflow graph/layout、Task冲突解决稿等各自保留实际版本域，不用一个“全站revision”混合。

## 3. 草稿、File与视图的交接

草稿保存 baseline、edited、dirty、scope与原版本；Ant Form保留输入中间态，controller保留持久意图。背景读取不覆盖dirty；主题切换、tab/折叠、选择变化不重建owner。Numeric空值/负号不自动复位成默认。正式恢复的multipart仍用原File实例；metadata或同名文件不能冒充相同bytes。

刷新恢复只能按当前已有服务端/存储合同实现。可存request metadata不等于能序列化File；无法恢复字节时明确要求重选并核对原身份/hash。storage拒绝时保留内存并披露刷新风险。未发送普通dirty／File只有明确确认才可放弃；pending/unknown/accepted-readback写入、上传或在途自动保存默认阻断离开，不能用confirm清空或热切换。

**项目经理已确定的离开合同（设计已决定，生产尚未修复）：**先判未settle写入，再判普通dirty。`SENDING`／`UNKNOWN`／尚未安全交接的`ACCEPTED_READBACK`返回`BLOCK`，维持原owner、原payload／File／版本和原请求身份，直到核对为安全状态；不提供“放弃并重新提交”，不自动重复write。已settle后仍有草稿／未发送File则`CONFIRM_DISCARD`，取消／Escape必须保留；无风险为`ALLOW`。只读加载和服务端Task运行本身不构成在途本地写阻断。

这些规则覆盖侧栏、链接、back/forward、同路由换id、关闭业务编辑弹层以及回退／运行时切换。关闭一个纯说明面板但仍留在原owner里可以允许；不能将其等同于丢弃编辑器／操作owner。没有持久化body/key或by-request能力的页面，当前实例仍提供按实际endpoint实现的原身份恢复入口，并明确“刷新后可能无法恢复本次提交”；身份已丢失时不得伪造新身份冒称恢复。metadata、同名File和内存保留均不等于跨刷新完整恢复。

每个attachView返回独立lease，其unsubscribe幂等；仅最后lease清该owner可退休的读资源。StrictMode退休lease不能关闭另一个view，或丢unknown/File owner。跨语言容器交接顺序：先纯TSowner红/绿测试→Vue delegate使用同一owner验证→React whole page订阅同一owner→下次安全route进入选择新view→最后删Vue delegate。不能短时间两个store都自动保存、两个stream都merge或两个按钮分别发同一命令。

## 4. NavigationPort与路由唯一所有者

```ts
interface LocationProjection { path: string; query: URLSearchParams; hash: string }
interface NavigationPort {
  read(): LocationProjection
  navigate(destination: string, options?: { replace?: boolean }): Promise<boolean>
  back(): void
  // 实际history owner注册；React页只提供LeaveDecision，不创建第二router。
  registerLeavePolicy(owner: PageOwner<unknown>): Unsubscribe
}
```

过渡VueRouter拥有push/replace/pop，临时整页bridge只是投影props。最终W6一次切ReactRouter7.18.4Data模式：router在React树外建一次，SPA blocker与beforeunload分别覆盖导航、刷新／关闭。URL query、Designer条件guard、Knowledge最后位置、列表返回滚动和3redirect保持；不同ID/同路由更新也经过policy。已运行服务端Task的普通读视图可以离开并关SSE；并非“只要服务器RUNNING就禁导航”。阻断针对当前本地有丢失／未知写风险的操作。SPA出口必须真正阻断；beforeunload只能请求浏览器提示，不能保证拦住用户确认刷新、浏览器终止或崩溃，也不能补上不存在的身份／File持久化。

**已接受创建的自有导航交接是特定例外，不是全局bypass。** 例如RequirementNew已有`:20`从自己的确定receipt导航到新实体。设计允许原owner按已知receipt/ID发一次限定目标handoff，守卫不能将其误拦为普通危险离开；未知是否接受时仍阻断。handoff对象应由owner签发并核原scope/目标，UI不能自行开总开关。导航失败仍保存已知receipt/ID，恢复只导航／读取，不再create。必须检查`navigate`的boolean结果，guard取消／返回false和Promise reject均为失败；基线只await router.push，不检查NavigationFailure，不能把设计要求写成已经可靠实现。此静态缺口与实际影响待[B1红测](w0-evidence-ledger.md)结清。

最终删除Vuebridge和Vuefallback；画布作为普通React子组件，不重复createRoot。W2–W5回退只在安全的新进入点选视图；W6后通过完整构建checkpoint外部回退，不在最终包带旧Vue。操作未settle时先按原恢复合同处理，不能强制切owner。

## 5. ResourceScope：按实例归属立即释放

```ts
interface ResourceScope {
  readonly identity: Identity
  own(dispose: () => void): Unsubscribe
  isCurrent(captured: Identity): boolean
  dispose(): void // 幂等；清本实例资源，不删别人的监听
}
```

实现负责记录自己的listener、timeout/interval、RAF、ResizeObserver观察关系、SSE/subscription、pointer capture和Blob URL。timer等异步返回时先isCurrent再更新snapshot或排下个timer，dispose后不复活poll。React effect cleanup、route scope换ID、cancel、blur/lost capture分别走相应生命周期，不依赖下一次自然mouseup。

dispose先失效scope token，再逐项清资源；一个disposer抛错不能跳过余下清理，错误仍报告。disposed后再own的新资源必须立即清理或拒绝注册，不能变成永不释放的尾部资源。清理函数返回的取消登记也幂等，不能让取消登记与真正释放的职责含糊。

保留已验自有画布手势与XYFlow补丁，不重新打开高风险原生拖动／连接／pan。Ant/CodeMirror/ECharts等第三方也要准确判明其资源所有权：测试探针未知来源fail closed，不复制旧Element内部RAF的provenance排除。两实例不能互清；StrictMode retired owner立即释放，不让读写命令重放。

首采样资源归零证明观察关系／订阅释放，不证明所有JS对象已GC；第一阶段RO/renderer断开后仍存活的采样限制保留。需要堆分析时另记录retaining path，不能用“没有observer target”宣称全堆泄漏不存在。

## 6. W1接口准入清单

至少覆盖：factory无side effect；同scope独立lease重复attach/detach；两scope迟到读写；UNKNOWN原payload/File/key；ACCEPTED_READBACK仅nav/read、不重mutation；自有确定receipt导航交接与失败后仅nav/read（guard false／取消及reject分别测）；成功写推进revision；409保draft；存储拒绝；pending／unknown所有SPA出口严格阻断、普通dirty确认取消、未发送File确认、beforeunload限制披露；显式同原身份恢复且无自动重复write／换key；无持久化／by-request页面不承诺刷新恢复；StrictMode无重复mutation/互停；timer await后卸载不重排；dispose期间异常仍清其余与dispose后own立即处理；双实例资源不互清。先检查当前相邻测试合同，再新增有价值的失败/并发断言，不写仅复述实现的测试。

W0的[B1–B9红测／证据](w0-evidence-ledger.md)和[Automations兼容映射](automations-compatibility-map.md)是生产迁移前的硬准入项；本轮仅设计补充，不把规则冻结算成运行修复或测试通过。

验收时业务方法和SnapshotPort由模块作者实现，组长只整合共享接口与kernel；接口不足先有反例和明确改动再移交，避免各模块为方便单独造第二命令或history owner。当前只交设计，尚未通过这些正式实现检查。

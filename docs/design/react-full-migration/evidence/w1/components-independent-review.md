# W1 共享 UI 组件独立审查

审查者为原任务 `/root/react_legacy_canvas`，被审组件作者为 `/root/react_flow_workflow`，provider 为组长持有。沿用既有启动记录显式指定的 `gpt-6.1-sol / xhigh`；当前工具不能读取实时平台模型配置。本轮未修改组件、provider、原页面或 W0 测试，未启动浏览器、安装依赖或运行全量门禁。

## 独立执行结果

基线为 W0 本地提交 `3c848261`，被审对象为当前未提交 W1 文件。**冻结组件 v4、最终两处 `closest<HTMLElement>` 测试类型收窄后的 20 项及组长 registry/theme 的 8 项经非作者实际复跑，共 28/28 PASS、exit 0，日志无 stderr 警告；前后源码/test hashes 与最终冻结值一致。** 图标生成器 `--check` 独立通过。另只读核对 C 最终 `/workspace/react-full-w1-evidence/browser-final-v4/evidence.json`：complete=true，41/41 PASS、errors/warnings=[]，sourceBefore==sourceAfter。其中运行组件 hash 与最新候选同为 `b1d663e0…`，registry 同为 `1724215f…`；C 生成时 test hash 是类型收窄前的 `a060a96b…`，不能伪改成最终 `781ff919…`。本审查者未启动浏览器，C 的执行与本人的 raw 复核明确分开。旧 12/14/16 项、C 40 项的 39 PASS/1 FAIL 均保留为历史，不冒充最终准入。

最终类型收窄候选的实际执行命令：

```text
cwd: frontend
npx vitest run src/foundation/components.spec.tsx src/foundation/registry-theme.spec.tsx --maxWorkers=1 --reporter=default --reporter=json --outputFile=/workspace/react-full-w1-evidence/components-registry-independent-v4-typed.json
exit: 0
2 files / components 20 + registry/theme 8 = 28 PASS / 0 FAIL
```

| 最终文件 | SHA-256 |
| --- | --- |
| `frontend/src/foundation/components.tsx` | `b1d663e00f06680d06261782c69dd1af5b63ebc2e3405c6c30062713a3472488` |
| `frontend/src/foundation/components.spec.tsx` | `781ff9190364fee5475d4e5bda30a746c4e458edf44f346475c05c130f6fbe42` |
| `frontend/src/foundation/registry-theme.spec.tsx` | `1724215f6940d3ca76a2ed0b80886fa1602056d2cdc2bc32e4ec3e4ca663efd7` |
| `components-registry-independent-v4-typed.log` | `c91b48346e6a3c49899f6d503567c9d1d19689dfb7154ffdb311d4beac8e7c7d` |
| `components-registry-independent-v4-typed.json` | `5b7a8d3f790cb39f1731ba02565c83960e6118aff28d01034e78121323e4952e` |

类型收窄只在 spec `:283,288` 的两个真实 dialog 查询中声明已有 HTMLElement 类型，runtime source 未改、断言未降级。组长另持 transpiled JS 等价核验，本报告不把该核验冒称本人的执行。以下保留类型收窄前 v4 的独立结果及 C 对应 provenance。

v4 实际执行命令：

```text
cwd: frontend
npx vitest run src/foundation/components.spec.tsx src/foundation/registry-theme.spec.tsx --maxWorkers=1 --reporter=default --reporter=json --outputFile=/workspace/react-full-w1-evidence/components-registry-independent-v4.json
exit: 0
2 files / components 20 + registry/theme 8 = 28 PASS / 0 FAIL
```

v4 源码与独立输出 SHA-256：

| 文件 | SHA-256 |
| --- | --- |
| `frontend/src/foundation/components.tsx` | `b1d663e00f06680d06261782c69dd1af5b63ebc2e3405c6c30062713a3472488` |
| `frontend/src/foundation/components.spec.tsx` | `a060a96b22e60c64fb793fd5d9f626c269bec12bcd77a5fb496101527edb4cdd` |
| `frontend/src/foundation/registry-theme.spec.tsx` | `1724215f6940d3ca76a2ed0b80886fa1602056d2cdc2bc32e4ec3e4ca663efd7` |
| `components-registry-independent-v4.log` | `b95c86084312bf7e841134107cf22a4aad12696543d3b777e9250bd2e225193d` |
| `components-registry-independent-v4.json` | `3e6b27674af68b7731faf721839cab365beb0e0a67db8bf2515971ca88dd0969` |

以下保留 v3 中间候选的独立执行结果，不作为最新候选验证：

v3 实际执行命令，使用真实 React 和 Ant Design，没有 mock Modal/Button/Input：

```text
cwd: frontend
npx vitest run src/foundation/components.spec.tsx src/foundation/registry-theme.spec.tsx --maxWorkers=1 --reporter=default --reporter=json --outputFile=/workspace/react-full-w1-evidence/components-registry-independent-v3.json
exit: 0
2 files / components 16 + registry/theme 8 = 24 PASS / 0 FAIL

cwd: repository root
node scripts/generate-foundation-icons.mjs --check
exit: 0
78 本地 Lucide 语义图标子集可复现
```

v3 源码与独立输出 SHA-256：

| 文件 | SHA-256 |
| --- | --- |
| `frontend/src/foundation/components.tsx` | `7ae9d0e5e259e538eb188c17556298d3b5cf17612a70913762bd696ff337e710` |
| `frontend/src/foundation/components.spec.tsx` | `c308916e4a1947653f656c871bd140e9be54f8e53859c73aa2ba657c69a91adc` |
| `frontend/src/foundation/registry-theme.spec.tsx` | `1724215f6940d3ca76a2ed0b80886fa1602056d2cdc2bc32e4ec3e4ca663efd7` |
| `components-registry-independent-v3.log` | `37c439282de6f3000d0637de75459eefac2ed0700586c2eea97aa31c878d0bba` |
| `components-registry-independent-v3.json` | `3b4f6f94d1b3e32f0acd2c64e781a82bf3cb900e86480c052f8eae54241bb1e8` |
| `scripts/generate-foundation-icons.mjs` | `a652a6f55defd4075fb9adbed9f52ab3e5ef198d5a7908b2bdcc2aca48db257e` |
| `generator-independent.log` | `02e655bee5a21e58667f8f43336a1c2bfb9062c1127c306e64fb6cc6d3cf69a4` |

以下保留 v2 的独立实际执行命令与输出，均为中间候选：

```text
cwd: frontend
npx vitest run src/foundation/components.spec.tsx --maxWorkers=1 --reporter=default --reporter=json --outputFile=/workspace/react-full-w1-evidence/components-independent-v2.json
exit: 0
1 file / 14 tests PASS / 0 FAIL
```

v2 源码与独立输出 SHA-256：

| 文件 | SHA-256 |
| --- | --- |
| `frontend/src/foundation/components.tsx` | `85030da1a2df865f06b69ed2905be5babf95a2d5ac2580d968a045bb351ccf40` |
| `frontend/src/foundation/components.spec.tsx` | `800da9999d414740167f55e7f089cce89c372ed9aaf89ea6c240cb550c47bd70` |
| `components-independent-v2.log` | `333fc0970d49f1737f8bd27be0e66392abb469c3033b8552c9180705924de80c` |
| `components-independent-v2.json` | `18b97a332a0a06d2c8cd5df634a7459bfc8729ac8648b17f6cfdf26b062da5a4` |

以下保留首次旧候选 12 项结果及发现时的哈希；它是修复前的历史证据，不冒充 v3 的 16 项：

```text
cwd: frontend
npx vitest run src/foundation/components.spec.tsx --maxWorkers=1 --reporter=default --reporter=json --outputFile=/workspace/react-full-w1-evidence/components-independent.json
exit: 0
1 file / 12 tests PASS / 0 FAIL
```

标准输出记录于 `/workspace/react-full-w1-evidence/components-independent.log`。源码和输出 SHA-256：

| 文件 | SHA-256 |
| --- | --- |
| `frontend/src/foundation/components.tsx` | `58bbbf3b080f0e59c8f385aa2d4fdea73f4a336fe3d246ef19df7e2498e6aa18` |
| `frontend/src/foundation/components.spec.tsx` | `7e3f7c3696213d5f4fb3db6f5d7bdad7947aaabc458fa14992c007550c029289` |
| `frontend/src/foundation/provider.tsx` | `f531ec21c34f5a717d45e37d1a97fd9d23c8c19382131850e5a34735d11385ed` |
| `components-independent.log` | `5d45c4adfa576db1b3d4c6400392d9cbd10dda9fa4ddf4945ee3498e41c5f241` |
| `components-independent.json` | `ba9b761d8a7b9f59ca7a54efdd3dec64672dc8f2376a05e3c6725b63dd611c04` |

## 已通过的范围

| 行为 | 源码 / 测试证据 | 独立结论 |
| --- | --- | --- |
| 局部 Ant portal | v4 `components.tsx:132–150`；`provider.tsx:33–48`；`components.spec.tsx:87,270` | Modal 等待宿主 commit 后解析局部 getContainer；真实 dialog 位于本实例 `.loopper-foundation` 内。未回退全局 body；内层 portal 的 Tab 不交由外层捕获接管。 |
| 普通确认焦点 / Tab | v4 `components.tsx:77–114,135–152`；`components.spec.tsx:87,214,243,270,302,329` | 打开后焦点默认“留在当前页面”；Escape 只 dismiss。局部 Tab 捕获正反 wrap，动态 BLOCK/busy 重算；不加 document/window 监听。真实 Modal 关闭后回仍存活 caller 或本实例 main，Stay 回可见 panel close。jsdom 与 C 浏览器证据分列。 |
| dirty 期间变为 unknown/BLOCK | v4 `components.tsx:192–221`；`components.spec.tsx:71` | 已打开的普通放弃确认收到新 BLOCK prop 后立即禁用破坏性确认并显示原因；回调读取当前 prop，未调用旧确认或 close。取消确认后仍保留上下文。 |
| 关闭展示与服务端取消分离 | v4 `components.tsx:176–221`；`components.spec.tsx:46,62,161,302,329` | aside 使用 hidden，input 实例和值保留；普通关闭只发 owner 的 onClose，task.cancel 未被调用。BLOCK 拒绝 close/Escape，busy 确认也拒绝 Escape/按钮；缺失草稿 owner 回调时不擅自本地清除。 |
| 无自动业务写 | `components.tsx:29–47,59–74`；`components.spec.tsx:19,35,103` | render、StrictMode 重放、皮肤变化、unmount 不调用动作回调或 history；hidden/disabled/busy 阻止动作。组件不从 semantic catalogue 推导许可。 |
| 可达的高级内容与选择 | `components.spec.tsx:113,123,133,144` | list 当前回调、table 行键盘选择与内嵌动作互不混淆；field 标签/错误关联；disclosure 保留草稿 DOM，扩展仅发受控意图。File 用例证明外部 owner 的原 File 引用不因展示变化被清空。 |

`onClose/onCancel/onConfirm/onAction` 是注入的 owner 意图口。组件没有 API client、领域 controller、SSE 或 endpoint authority；上述“没有服务端取消”证据是未触发该测试提供的 task.cancel 回调，不能推广为任意业务页错误接线也安全。

## 独立发现与修复复核

1. **P2，已关闭：触发器退休后的焦点兜底缺失。** 旧候选 `components.tsx:77–80` 仅选择 `explicit?.current ?? captured`，目标 disconnected 时结束；也不会从 disconnected explicit 尝试存活 captured。发现依据为 `semantic-ui-contract.md:106` 的具名主内容要求，原 `components.spec.tsx:46,87` 都保留触发器。作者初修中 captured body 会遮蔽 fallback 的问题也经独立源码审查反馈。最终 `components.tsx:77–94` 排除不在本 provider、不可见或不可用的候选，优先存活的 explicit/captured，再找最近 provider 等于本 host 的 `.ui-shell-main`；`components.tsx:69` 为主内容提供语义名称，provider 已退休或 Modal 已卸载时不抢焦点。新增 `components.spec.tsx:177` 真正 focus/click 一个 React 触发器，再通过另一 UI 入口移除它，检查原 DOM disconnected/ref null 后关闭面板；非作者复跑证明焦点落本实例具名 main，另一个实例未被抢焦点。此项最初是静态发现，不伪造未修版新增红测或浏览器复现。
2. **P2，已关闭：可用 icon-only 动作缺少提示。** 旧候选 `components.tsx:38–44` 有语义 aria-label，但 enabled icon-only 没有 title/Tooltip。最终 `components.tsx:39` 使用同一 `semanticName(actionKey,target)` 提供原生 title，disabled 仍保留真实原因。新增 `components.spec.tsx:203` 独立复跑检查 title 与 aria-label 相同、目标名称一致、禁用后提示保留原因且不触发动作。它验证原生提示属性，未声称真实浏览器的提示绘制或新增焦点 popup 已验收。
3. **C 的真实浏览器发现，最终复验关闭：Ant trap 配置无法单独保证严格 Tab。** 非作者只读核对 `/workspace/react-full-w1-evidence/browser-first-execution/evidence.json` 的 `ordinary dirty close: real modal starts at Stay, traps Tab, Escape retains draft, explicit discard alone clears` 失败，以及 `/workspace/react-full-w1-evidence/focus-diagnostic.log`：Tab 从 Stay 到 Discard 后曾落 BODY，后续才回库焦点，不能把 BODY 当 sentinel 容许。作者用公开 `Modal.modalRender` 中的本实例 React `onKeyDownCapture`（最终 `components.tsx:97–114,148`）接管边界：每次按实际 DOM 重算可用控件，排除 disabled/fieldset、hidden/aria/inert、负 tabindex 和 CSS 隐藏；边界 preventDefault/stopPropagation，再 focus 本 dialog 内目标，零可用项时 focus 本 scope。新增 `components.spec.tsx:214,243` 经本审查者实跑验证正反 wrap、内部普通 Tab 未拦截、BLOCK/busy/全 disabled 后重算且不触发业务回调。原 14 项没有此负控；最终 C 的同源码 41 项全部通过，浏览器执行与本人仅 raw 核对分开，不用 jsdom 替代真实遍历。
4. **v3 剩余浏览器失败及新增实例边界，v4 已复验关闭。** `/workspace/react-full-w1-evidence/browser-final/evidence.json` 保留 40=39 PASS/1 FAIL，dirty discard 的实际 Modal afterClose 最终焦点失败。另作者真实 nested 前红 `/workspace/react-full-w1-evidence/components-nested-confirmed-before-results.json` 精确为预期内层 input、实际外层 input，不能把最初重复测试 ID/选择器诊断当此已确认产品红。v4 `components.tsx:101` 检查原生 DOM target 属于当前 scope 且最近 scope 正确，拒绝 React portal 逻辑祖先跨实例捕获；`:77–94` 拒绝 hidden/disabled 等不可用回焦目标；`:211` 在 panel 已关闭时给内部 Modal 原 caller/main 的目标，panel 保留时仍回 dialog 原 close trigger。新增 `components.spec.tsx:270,302,329` 经非作者实际运行：两个真实 Ant portal 内层 Tab 保留其正反端点；discard 后分别验证 caller 存活/已移除的两种最终焦点；Stay 返回可见 close、草稿保留且不 discard。C 最终 41/41 同源码 raw 复核支持这些浏览器边界关闭，不把旧 39/1 替换掉。

设计接口同步事项已由组长关闭：`semantic-ui-contract.md:114` 现明确 W1 采用与 `contracts/types.ts:5–12` 相同的 discriminated union、disabled/block reason 必填，原操作状态留在唯一 owner；设计包装目标与已经实施的 8 类基础组件分开列示。本审查未修改共享源。

## 动态减少动效与本地图标的独立复核

C 原浏览器 `real reduced-motion media changes tokens/CSS without replacing focused draft or creating a writer` 行失败证明焦点失效；该日志本身不单独证明 DOM 重建。独立只读核对锁定 Ant 的 `es/config-provider/MotionWrapper.js:19–26`：首次 parentMotion 与 motion 不同时加入两层 provider，源码支持组长已确认的 true→false 重建原因。不应把重建后的恢复焦点当保持原 owner/DOM。组长采用公开 token：`theme.ts:22–25` 始终 `motion:true`，减少动效仅三 duration 为 `0s`；`foundation.css:34–35` 使用局部宿主属性/媒体查询取消动画，不改业务皮肤域或发命令。

本审查者独立运行 `registry-theme.spec.tsx`：8 项全绿，包括 `:69` 的真实 Ant Input，false→true 后 input 同 DOM、焦点、未保存 value、selection `[1,3]` 及 consumer lease 保持，切回 false 焦点/lease 保持；`:58` StrictMode 媒体监听按原 callback 精确释放；三皮肤仍用既有 tokens。第一次独立 8 绿的 `registry-independent-v2.log` 曾有 focus 未包 act 的测试警告（保留），作者只修测试 act 包装后，v3 24 项及最终 v4 28 项日志均无警告，不能把第一次警告隐去。最终 registry spec hash 见上表。

独立 `node scripts/generate-foundation-icons.mjs --check` exit 0，78 图标可复现；只读取安装与锁定版本、icons 原文 hash、semantic catalogue 并比较生成子集，不写文件、不请求网络。生成器 hash 及实际输出 hash 见上表。此检查不替代 C 的三皮肤实际像素或语义浏览器证据。

## 方法限制与准入边界

20+8/28 证明上述 jsdom 场景经非作者复跑通过，未发现该聚焦范围剩余组件阻塞；不能称全部 W1 UI 合同通过。C 真实浏览器 41 项的执行、截图和资源账本属于 C，本文只读核对其完整结果、前后源码和失败为空；没有独立重启浏览器。C 明确资源边界为同步 unmount 首快照的特定 w1 probe/MediaQueryList/window focusin/keydown，fixture owned RAF/interval 及所有 raw RAF、所有原生 RO active targets；不声称任意第三方 timer、React delegated-container listeners 或 heap/GC 已穷举。复杂 radio/其他 nested popup 的原生 Tab 顺序、SSR、真实 endpoint 权限/恢复仍未由本审查实测。File 用例不检查 multipart 字节/哈希或跨刷新恢复；StrictMode 用例是展示 DOM 与回调保持，不是命令 owner 的恢复取证。纯 TS contracts 的 39 项作者测试与本 UI 审查是分开的证据，W0 的 84 条合同红也不被这些绿测抵消。

本次不涉及窄屏适配，不移除原窄屏断言，也不以 OUT_OF_SCOPE 允许终态保留执行 Vue fixture。未进入 W2 页面迁移。两个 P2、接口同步及上述 Modal 边界已复核关闭；全量门禁与整体准入由组长持有。本报告文件冻结并归还组长。

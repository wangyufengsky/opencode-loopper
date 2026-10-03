# W1 纯 TS 契约独立审查

审查者为原任务 `/root/react_flow_workflow`，沿用原启动记录的 `gpt-6.1-sol / xhigh`；当前工具不能实时读取平台模型配置。契约作者为 `/root/react_legacy_canvas`，provider/theme/semantic 作者为组长。本审查只读这些源码并独立执行其已有测试，未修改被审实现；审查者自己编写的 UI 组件与作者测试不计入独立行为证据。基线为本地 W0 提交 `3c848261`。

最终 v2 契约及当前 provider/theme/semantic 独立结果为 **49/49 PASS、0 FAIL、0 SKIP、exit 0**：receipt 15、navigation 12、resource/controller 12、registry/theme 8、ownership 2。78 个本地 Lucide 子集的可复现检查也实际 exit 0。已核对范围内没有未修复的契约准入阻塞；后文明确真实接口适配与资源证明的边界，此结论不代表 W2 页面或 W0 业务红测已修复。

## 发现与处理

1. **成功写入后的 DTO 捕获异常已修复。** 旧候选在写入 fulfilled 后先捕获 DTO，再登记 accepted，异常会误归 UNKNOWN。此旧缺陷由 B 自检发现并经本审查者源码确认；不存在未修版本的实际红测记录，不能称为已运行复现。最终 `frontend/src/foundation/contracts/receipt.ts:135–144` 先登记 accepted/ACCEPTED_READBACK，再捕获回执，以 `receiptAvailable` 独立控制快照、读回和 handoff（`:105–116,129–138,185–210`）。三个新增负控 `receipt.spec.ts:123,137,151` 已实际独立复跑：无 lookup 保持 accepted/BLOCKED；有真实 lookup 只能显式查原身份再读；lookup 返回无效 accepted DTO 也不退成 UNKNOWN、不传给 reader、不重复 write。原 key/body/version 保留。
2. **原 lease 测试标题与行为的差异已纠正。** 中间版所谓 UNKNOWN 实际是 accepted/deferred read，未使用真实 controller lease。最终 `resource-controller.spec.ts:108` 明确 accepted/read-lease cleanup/forced loss；`:127` 实际制造 UNKNOWN，执行 `attachView()` 的最后 detach/remount，断言 remount 不自动写，用户显式恢复才以同一 identity 发原写。此为测试证据修正，不是业务修复。
3. **真实 adapter 尚属后续波次。** injected navigate 只有真实成功才能返回 true，RecoveryCapability 只有真实 endpoint 能力才能登记。纯 TS 测试没有调用后台、实际 VueRouter 守卫或虚构 by-request GET，也没有创建跨刷新恢复能力。

## 源码与实际行为证据

表中 `contracts/` 均指 `frontend/src/foundation/contracts/`。所有测试均由被审作者编写，本审查者实际单 worker 执行。

| 主题 | 最终源码锚点 | 已运行的测试与结论 |
| --- | --- | --- |
| 无 key / UNKNOWN | `receipt.ts:92–100,168–199` | `receipt.spec.ts:81,93,98,196`：无 key 不获重写能力；NONE 无 lookup；endpoint 明确提供 READ_ORIGINAL 才可显式读，UNCONFIRMED 仍 UNKNOWN。伪幂等能力与正文/key 不一致明确失败。无新 key 或通用 GET。 |
| DTO / File 真引用 | `immutable.ts:2–22`；`receipt.ts:85–89` | `receipt.spec.ts:38,196`：实际 FileReader 读原字节，保留原 File 实例、顺序与数组；调用者后改同名 File、正文、endpoint、key/revision 不污染原操作。DTO 复制冻结，循环/原生对象拒绝；File 不塞进 DTO、不冻结本体。 |
| sending / 通知重入 | `receipt.ts:114–119,145–156,168–184` | `receipt.spec.ts:24,55,65`：构造/订阅/快照不写；在途 Promise 合并，先登记 running 再通知。订阅者重入不重复 dispatch，异常单列 notificationFailures，不改变 accepted、不妨碍其他订阅者。 |
| pending leave / dirty | `navigation.ts:12–33,53–69`；`controller.ts:37–40` | `navigation.spec.ts:18,29,40,55,62,123`：SENDING/UNKNOWN/不安全 accepted 优先 BLOCK，dirty/File confirm 不能绕过；普通 dirty/File 明确确认，拒绝不导航。await 后重查 scope、新 pending、draftRevision；同意图合并，其他目的地不能冒称成功。 |
| accepted 仅读 / 版本推进 | `receipt.ts:129–144,175–205` | `receipt.spec.ts:111,123,137,151`：accepted recoverWrite 只 read，使用成功回执 version 20，原请求 commandVersion 19 保留；无效 DTO 不充当合法 receipt，显式 lookup 校验后才 read/handoff，无自动查或重发。 |
| accepted 精确交接 | `receipt.ts:34–42,207–219`；`navigation.ts:64–69` | `navigation.spec.ts:72,91,101,116`：原 owner 确定回执签发 WeakMap/WeakSet 身份及精确 destination permit；false/reject 保回执/key、不再 create，真实成功才消费。成功目的页退休旧 scope 仍能交接；伪 permit 即使 clean 也不调用 port，不能绕另一未决操作。没有独立公开 complete 捷径。 |
| scope / 迟到隔离 | `scope.ts:4–13`；`receipt.ts:122–133,221–223`；`controller.ts:41–47,63–68,94–103` | `receipt.spec.ts:174,187`；`resource-controller.spec.ts:14,50,100,108`：同值不同实例仍不互认，retire 后 token 无效，await 后投影经 apply。迟到 accepted 留原 owner，不通知新 view、不覆盖新 controller；退休后 execute/recover 拒绝且不改原 phase。 |
| cleanup 异常逐清 | `resource.ts:16–38`；`controller.ts:49–51,73–90,94–103` | `resource-controller.spec.ts:20,33,45,50,87`：仅本实例精确 callback/计时器；先失效，逆序全部尝试、异常汇总、余项继续。returned cancellation 真释放、幂等，disposed 后 own 立即处理；setup/cleanup 双错均保留，之后可新 lease。 |
| 独立 view lease | `controller.ts:53–90` | `resource-controller.spec.ts:61,74,108,127,148`：多个 attachView 共一个读 owner，各 detach 幂等，最后 detach 才清读；另一 controller 不受影响。普通 detach 不退休 operation/File；UNKNOWN 保同 owner，remount 不写，显式原身份恢复。通知异常不破坏快照或其他订阅者。 |

## provider / theme / semantic 只读审查

`frontend/src/foundation/provider.tsx:16–25` 按实例登记/移除同一媒体 callback，显式 reducedMotion override 不登记监听；`:33–48` 提供稳定局部宿主/getContainer，不按 skin key 重挂，不创建 history/命令，宿主未挂载/退休时失败而不退到 body。`registry-theme.spec.tsx:43,58,69` 实际证明皮肤切换 DOM 保持、StrictMode 媒体监听最终清零、reduced-motion 切换后真实 Ant Input 的 DOM/焦点/value/selection/consumer lease 保持。

`theme.ts:11–32` 仅投影既有 SkinDefinition 颜色/字体/圆角与公开明暗算法；主按钮三态仍使用 scoped CSS 的既有 primaryButton 变量，GitHub 绿色主操作与蓝链接区分。`:22–25` 固定公开 motion token，以零时长/scoped CSS 减少动态，避免更换 Ant provider 树。三皮肤 token 用例在 `registry-theme.spec.tsx:33`，没有皮肤业务分支。

`semanticRegistry.ts:19–32` 集中读取中文 label/name 与 **78 个本地子集**，未知 key/glyph 失败，runtime 不导入整套 Lucide。`SemanticIcon.tsx:4–11` 为唯一 trusted SVG 出口，始终 decorative。`scripts/generate-foundation-icons.mjs:7–30` 以锁版本、本地 icons 源 hash、registry names 重建子集；只允许无旋转/翻转的 canonical alias 并防循环；`--check` 逐字节比较、不写入。这不是安装 tarball integrity 的独立下载证明。

`registry-theme.spec.tsx:12,22` 核对所有登记项中文名、本地图标、31 route、close/server cancel 语义分离。`ownership.spec.ts:7–25` 对 runtime/冻结桌面 registry 的 objects/actions/guards/components/routes 逐项比较，并扫描 foundation 无 Vue/Pinia/vue-router、fetch、第二 history、分散 raw SVG。语法扫描是辅助门槛，不能替代全部副作用分析；本审查也只读核对了实现。Catalogue/availability 不授予服务端许可。

## 独立执行与原始证据

在 `/workspace/opencode-loopper-react-full/frontend` 实际执行：

```sh
./node_modules/.bin/vitest run src/foundation/contracts/receipt.spec.ts src/foundation/contracts/navigation.spec.ts src/foundation/contracts/resource-controller.spec.ts src/foundation/registry-theme.spec.tsx src/foundation/ownership.spec.ts --maxWorkers=1 --reporter=default --reporter=json --outputFile=/workspace/react-full-w1-evidence/contracts-independent-frozen-results.json > /workspace/react-full-w1-evidence/contracts-independent-frozen.log 2>&1
```

exit 0，5 文件/49 测试全部 PASS，0 skip，11.57s。此最后一轮在 UI 组件 v4 冻结后执行，ownership 扫描覆盖最终组件字节；组件作者测试仍不计入此独立结果。第 8 个 registry 测试由组长随后新增，因此旧 48 项不能代表当前字节。仓库根另执行 `node scripts/generate-foundation-icons.mjs --check`，exit 0，78 子集可复现。

所有原始证据位于仓库外 `/workspace/react-full-w1-evidence/`：

| 文件 | SHA-256 |
| --- | --- |
| `contracts-independent-frozen.log` | `5f897acf0b296102e53b8ac3305115f083e4c55115ad91913f6ddfb57c28bfad` |
| `contracts-independent-frozen-results.json` | `12f18eb3a56d0d59af76616d590d9bea5db546df2f2d9261ef41c87de3f70276` |
| `icons-independent-final-current.log` | `02e655bee5a21e58667f8f43336a1c2bfb9062c1127c306e64fb6cc6d3cf69a4` |

旧正常 DTO 候选独立 45 绿及 v2 48 绿保留为历史，不替代当前 49。一轮 clean-ci 同时移走 node_modules 造成空日志/exit 130 为 ENV 中断，没有测试运行或本审查者安装行为，不计产品失败。

## 最终被审字节

前端路径均以 `frontend/src/foundation/` 为前缀。

| 文件 | SHA-256 |
| --- | --- |
| `contracts/receipt.ts` | `f446815215b62ca50e63015d263ae37cb919bd5921794fa68289febe1925b596` |
| `contracts/receipt.spec.ts` | `a26c7d72dfb4f4f4e0ac1a7a529b8172ffbf49699884d20fa123e19ba9687b96` |
| `contracts/navigation.ts` | `91749c38d3321c86765bdf1c80aa34f19eaeedf3a653344e617cfc2506d65400` |
| `contracts/navigation.spec.ts` | `8d255ab7e478e1512ff16afc99b1a42935530157a97d9d62f3caa1b75fa794ab` |
| `contracts/controller.ts` | `0eaed69bd1abd3e3596606ef086a59bbfe68d3b2a1be9174e4b9b0e4caeb2a35` |
| `contracts/resource.ts` | `af108047aace2082d24348f56578fc8629b8ad44d545b4189aa07a788704ab09` |
| `contracts/scope.ts` | `635a9b837e0177801ee593a0cdc9d8e816f8c69c602c91ebe23db965c123a5f3` |
| `contracts/immutable.ts` | `36d091029fbee3baa151aeb86dcc013fbf4674eeb10d08eae3f34d58db5a8a1a` |
| `contracts/types.ts` | `a2bdcf79e18a12690ddd8a0ce411a404fe82c25ca0d4268c87cf63522cc5b2d1` |
| `contracts/resource-controller.spec.ts` | `734bbbbbf118f6d19172a2a20be23c1fd458fd6faa59d06d7c5b2cc1df291094` |
| `provider.tsx` | `f531ec21c34f5a717d45e37d1a97fd9d23c8c19382131850e5a34735d11385ed` |
| `theme.ts` | `bcec2551c0d23de456ac6bb6e323bb548726d353fe087cd08aa2741349e11193` |
| `semanticRegistry.ts` | `1a4858d38db786e557d34891f4bb23f046af4cdef23a2d1235128d6b9180e4c4` |
| `SemanticIcon.tsx` | `aa8bf3d584e357df85b4c347b0f1330d672794a54bfe193af5afa0b591639222` |
| `semantic-glyphs.json` | `37b78605e15d97e4c560ce02ca855aa58e44002ca2d541667f4616b3ffaee9fb` |
| `semantic-registry-data.json` | `09f23554b5f1224a105f835bfd2209b0c74950371a4c198791aaf63a6df61be6` |
| `registry-theme.spec.tsx` | `1724215f6940d3ca76a2ed0b80886fa1602056d2cdc2bc32e4ec3e4ca663efd7` |
| `ownership.spec.ts` | `07551aaedaceac856d7dd72cec23c5e1f983355ea6dc8ae3a07d14b7a619e4fc` |
| 仓库根 `scripts/generate-foundation-icons.mjs` | `a652a6f55defd4075fb9adbed9f52ab3e5ef198d5a7908b2bdcc2aca48db257e` |

## 准入边界

- 这里只证明实例 scope；同作用域内请求乱序、服务端版本/revision/权限须由 endpoint/domain controller 持有。
- capability、definitive-rejection 判定及 navigate true 的实际历史提交含义须由真实 adapter 证明；mock true/lookup 不是后台协议或 VueRouter NavigationFailure 的生产适配。
- detach 保同一 owner；forced retire 留原身份/回执并拒绝重启。没有跨 owner 的持久化 hydration，不承诺跨刷新 File/draft 恢复，不能丢数据后自动重发/换 key。
- 资源容器先失效并逐个尝试/报告释放；实际 SSE/RO/RAF/listener disposer 必须真正清自身资源。timer/listener 单测不是全部画布资源/弱引用 GC 浏览器证据。
- 本审查未启动浏览器或执行全量 build/typecheck，组长/C 持有那些证据；审查者实现的 UI 组件另由 B/C 审查，其作者测试不能补入这里的 49 项。

W1 为基础 UI/controller 准入，W2 页面替换仍未获授权。W0 84 条正确业务红测保持原状，桌面原型 mock 也不是业务协议修复。

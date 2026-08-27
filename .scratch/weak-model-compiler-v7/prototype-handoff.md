# 弱模型 Compiler v7 原型回交

状态：原型问题已回答，生产实现尚未开始。

## 原型问题

在不放松零未覆盖、Java 聚焦测试、路径边界和结构冲突门禁的前提下，能否通过服务端的可逆规范化、唯一空位补全和唯一最优能力求解，减少弱模型导致的整稿重设计与 Compiler 调用？

## 当前实现证据

- `DesignerAcceptanceFastPathResolver` 只要求 `SCENARIO / REVIEW` 恰好归属一个 Stage；`DELIVERABLE` 可以共享，但未要求每个正向交付路径都进入某个 Stage。
- `DesignerAcceptancePlanCompiler.groups(...)` 只保留 Stage 已引用的非验收事实。
- `DesignerAcceptancePlanCompiler.allowedPaths(...)` 先使用当前 Stage 的 material facts；只有该集合为空时才回退到包级路径或宽泛技术栈路径。
- `DesignerAcceptanceStageEvidenceBinder` 把派生结果同时写入 Stage、聚焦测试和显式 `GIT_DIFF`。
- `VerifierEngine` 在执行期把任何不匹配 `allowedPaths` 的实际改动判为 `outside allowed paths`。

因此存在一条真实的失败路径：需求明确要求 A、B 两个文件，Stage 只引用 A，设计编译可以成功，但开发工程师正确修改 B 后才在执行期失败。

一次针对当前源码的临时探针得到：

```text
required=[src/main/java/example/Service.java, config/external-adapter.yml]
stageMaterialFacts=[1]
derivedAllowedPaths=[src/main/java/example/Service.java]
missingRequiredPath=true
```

这证明路径信息丢失发生在设计到执行合同之间，不是运行期验证器误判。

## 原型结果

交互原型：`/Users/wangyufeng/.codex/visualizations/2026/08/27/01a0413e-c967-7b10-be3d-d4c3ac8cc375/weak-model-v7-compiler-prototype.html`

SHA-256：`a7098afd45a10507cc232e51b589e38b0e0d4cd9a2b06646f4596e2d54217824`

10 个定向故障注入样本的结果：

| 指标 | 当前 v6 | 原型 v7 |
| --- | ---: | ---: |
| 设计编译通过 | 3/10 | 6/10 |
| 端到端可执行 | 2/10 | 6/10 |
| Compiler 模型调用 | 3 | 1 |
| 整稿重设计 | 3 | 0 |
| 必须阻断的硬缺口 | 4/4 保持阻断 | 4/4 保持阻断 |
| 端到端验收降级 | 0 | 0 |

这些数字只证明算法方向，不代表生产通过率。样本刻意覆盖机械偏差、冗余模型调用、路径遗漏和必须继续失败关闭的硬缺口。

当前聚焦基线测试为 25/25：

- `DesignerAcceptancePlanningAlgorithmTest`：15/15；
- `DesignerAcceptanceFastPathResolverTest`：8/8；
- `DesignerPackagePlanCompilerTest`：2/2。

## 回交结论

采用“无损容错 + 设计到执行一致性门禁”，不采用单纯放宽 Compiler：

1. 模型只负责业务语义和真正无法唯一决定的闭集选择。
2. 服务端冻结显式的必改路径义务，Compiler 成功前证明每条义务已进入 Stage 路径合同。
3. 唯一归属由服务端补全；多个合理归属只做局部消歧。
4. 项目根外写入、删除、闭集外能力、无机器能力和结构冲突继续阻断。
5. 同时度量设计编译和端到端执行，禁止用更多 Judge-only 条件制造表面通过率。

正式设计见 [spec.md](spec.md)，实施从 [001-mutation-obligation-conservation.md](issues/001-mutation-obligation-conservation.md) 开始。

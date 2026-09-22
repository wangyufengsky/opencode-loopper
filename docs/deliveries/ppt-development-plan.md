# PPT 工作室实施账本

Goal：完整交付独立 PPT 工作室、PPT Agent、结构化制作 MCP、可编辑 PPTX 和 PNG 预览，完成适用验收、新版 JAR、本地服务更新与本地提交。

权威行为见 [PPT 合同](../ppt-contract.md)。用户已确认现有 PPTX 提取后重制、基础画布编辑、JAR 内置能力。基线为 `7928783`、版本 `0.4.61`，实施开始工作区干净，之前的皮肤修改已独立提交。

| 阶段 | 状态 | 证据/下一步 |
| --- | --- | --- |
| P0 合同/Interface | 已完成 | 独立协议、revision/CAS、场景、角色和 REST 已落盘，按集成反馈细化 |
| P1 引擎 | 核心验收及 WPS 手动数据编辑通过 | 21 项引擎测试及三类图表 XML Schema 校验通过；WPS 柱组端点裁切已修复并复验；原生对象/嵌入工作簿独立修改回读通过 |
| P2 持久化/REST | 已完成 | 11 项集成通过，真实 REST 完成 12 页样本、三轮修改、封面像素锁定及历史制品哈希不变；已补强局部范围正向回归 |
| P3 PPT Agent/MCP | 已完成 | 私有 MCP 2 项与 Agent 12 项聚焦通过；真实模型完成规划、两页制作、同会话参数/溢出修正与导出查询；导出阶段竞争已修复复验，旧代际重启停止证明已实测 |
| P4 工作台 | 已完成 | 18 项 Vitest、4 项 Playwright 通过；原生 Chrome 验证七模块保存、刷新、方向确认、手工制作、键盘/拖动/缩放、最新 PNG 与实际 PPTX 下载 |
| P5 集成交付 | 验收与本地服务交付完成 | 0.4.63完整门禁：后端2196项0失败/0错误/3条件跳过，前端537项通过；正式JAR空PATH隔离验收、原生往返和正式服务替换通过；最终证据见 [0.4.63交付](0.4.63.md)，本任务差异检查后本地提交 |

文件所有权：制作引擎 Agent 拥有 `ppt/` 引擎与相邻测试、字体资源；运行时 Agent 拥有 PPT agent/MCP/runtime 新增文件；前端 Agent 拥有 PPT 新页/组件/API/store/test；主 Agent 拥有迁移、持久化、作品/作业 REST、公共注册、合同、版本及最终集成。构建统一由主 Agent 调度，不并发写构建输出。

## 已核验的开发运行时证据（2026-09-22）

以下证据来自隔离开发服务与实际 Provider，原始记录位于 `data/ppt-dev-runtime-20260922/`。规划、制作、同会话导出及重启续作四个已完成 run 的消息均确认角色为 `loopper-ppt`、模型为 `opencode-go/deepseek-v4.1-flash`；安全摘要保留工具时序、反馈、成功回执和制品哈希，四个 run 的持久化 scope 凭证匹配数均为 0。

| 验收 | 已确认结果 | 原始记录 |
| --- | --- | --- |
| 同会话修正并导出 | run `5630937d-7a61-428c-a08c-8b44a12571c4` 在 REVIEW 下调用 11 次工具：保存 revision 3 后检查发现 `TEXT_OVERFLOW`，测量所需高度 34.75、可用高度 0；修正为 revision 4 后检查无问题，PNG 与 PPTX 作业均完成，两次 `ppt_get_job` 均成功。run 为 COMPLETED，停止证明为 `REMOTE_TERMINAL`，作品为 EXPORTED，确认导出阶段切换不再误停会话 | `model-second-run-evidence.json` |
| 真实规划候选 | run `e9c16046-aad2-4666-816f-d30d1c40720d` 在 BRIEFING 中读取上下文/能力并通过 `ppt_submit_plan` 保存 revision 1，随后以 `REMOTE_TERMINAL` 完成 | `model-workflow-plan-evidence.json` |
| 真实两页制作与参数纠错 | run `9d619a79-90f0-4c4d-ac37-5e458753b3ca` 在 PRODUCING 中执行 16 次工具。首次 `create_slide` 因“shape 必须是字符串”收到 `PPT_INVALID_OPERATION / FIX_AND_RESUBMIT`；同 Session 修正后保存 revision 3，随后修复检查报告的 8 项溢出，保存 revision 4、复检无问题，2 页 PNG 预览完成，run 以 `REMOTE_TERMINAL` 完成 | `model-workflow-production-evidence.json` |
| 重启恢复 | run `d8581e15-12e7-4b4d-a22f-de0ceb2aebee` 重启前为 WAITING_INPUT。确认旧应用 PID 72925 与受管 OpenCode PID 72938 停止后，新应用 PID 78045 将旧 run 转为 STOPPED，原因 `RUNTIME_EXITED`，证明 `OWNED_PROCESS_EXITED`；原问题与作品 revision 保留 | `restart-evidence.json` |
| 真实浏览器工作台 | 原生 Chrome 创建作品 `2a41c27e-d642-4bdf-86fb-37901df690d0`，七模块保存并刷新回读一致；完成方向确认、手工制作、点击后方向键移动、拖动与缩放。revision 14/15/16 预览均完成，最终检查无问题并导出，浏览器实际下载 30,480 字节 PPTX，ZIP 内存在原生文本与讲稿页；该作品 Agent 始终 IDLE，没有调用 Provider | `frontend-evidence.md` |

同会话导出的 PPTX 为 30,617 字节，SHA-256 `d740b7deab6b26ea3891a2f64929297fbfb85b447bd28c8b557ee95b67125b2a`。浏览器手工制作下载文件 SHA-256 为 `6600a3828fc7730469c793f0c349f2e6435663b04aa2986b012e41a5c4c9a26d`。这些是 PPTX 制品证据，正式应用 JAR 的独立哈希、字节数与运行证据已记录于0.4.63交付文档。

工作流证据中的 `jobs/artifacts` 是作品级汇总：两页作品后续 EXPORT 作业也已完成，但该制作 run 的工具时序止于预览完成，不把后续导出归因到 PRODUCING 会话。规划 run 与制作 run 使用不同 Session，分别保留阶段冻结与停止证明。

前端真实验收发现并修复了“等待刷新返回过早，使预览冻结旧 revision”和“点击对象未聚焦，方向键不能移动”两处问题；修复后重新以实际服务回读坐标、尺寸与作业 revision，完成下载核验。浏览器扩展入口最初不可用的记录仍保留，后续原生 Chrome 验收已完成。

## 最终交付与未覆盖项

- 0.4.63正式门禁、JAR、160文件备份、原地址服务替换和最终浏览器验收均通过。运行JVM22899、受管OpenCode22964，内部MCP已连接；完整信息见 [最终交付记录](0.4.63.md)。
- 上述运行时证据来自 macOS 隔离开发服务及当前 Provider；不替代真实 Windows 或内网部署/Provider 验证。Microsoft PowerPoint 已打开12页并显示中文与备注；本机未激活、只读，未验证编辑保存。
- WPS 打开、原生文字编辑保存、图表轴修复已实测；用户随后手动确认第7页“编辑数据”可打开，按18改20后图表更新。该项明确作为用户手动确认，区别于自动化未显露数据窗口的记录，见 `office-ui-evidence.json`。
- 已补验重启后新 run `234030ba-44ae-4e1b-9d6b-b66f5787d270` 成功继续保存 revision 1，旧 run 保持 STOPPED，凭证匹配数为 0；见 `restart-evidence.json` 与 `model-restart-resume-evidence.json`。前端证明重启后恢复可操作，未采集停机瞬间的 SSE 断线截图。

开发反馈输出保留在 `/tmp/ppt-backend-round*.log` 等聚焦日志。最终交付记录须分别列出源码测试、真实模型、办公软件、正式 JAR 与实际服务证据，不能用本账本的阶段进度替代。

# W7 本地 Release Candidate 验收

已完成本轮授权的全站**桌面、模拟运输**验收与必要回归修复，等待项目经理最终审查。分支 `feat/react-full-migration`，基准 `1e9161e06c01b5aa76b6a2b19789ac8379423195`。没有 push、PR、merge、部署、main修改、凭据/网络设置修改或付费模型调用。

直接查看：[三皮肤实际截图（187 PNG）](screenshots/README.md) · [最终机器回执](validation.json) · [全部31路由/动作与唯一所有者](task-system.md) · [逐测试结果与去重](browser-accounting.json) · [原2332单测逐文件映射](test-mapping.json) · [首错及修复](regressions.md)。各分组文档保留按时间标注的中间诊断，终态以本页与统一冻结批次为准。

## 一个明确的最终源码

统一冻结 SHA-256：`d1a38dd1471e5c5d0d23aeafa845c7494d3bc30e2d5875dd37c24e9faded596d`，覆盖817个源码、测试、工具及语义输入文件；[逐文件哈希](source-freeze.json)。所有最终单元、生产构建和下表浏览器结果均基于这份字节。运行前后严格验证相同，没有把其他revision的绿拼进来。

最终生产构建127文件聚合SHA：`454f1f79446aa3766bee47efc62919f034e95ae2e1945d1f78df9af7368d0303`。三员各自生产批次前后逐文件一致；文档/图片整理不修改运行代码。最终本地提交的准确ID在任务交付正文提供。

## 最终执行结果与计数

| 验证 | 实际终态 | 口径 |
|---|---|---|
| 干净 npm ci | PASS / exit0，369包 | 本工程node_modules/dist先为空；六处锁定补丁重现 |
| 全量unit | **2384/2384 PASS**，0失败/跳过 | 274测试文件；W0 **111/111**包含在内，不另加总数 |
| 原断言映射 | PASS | 原W5的2332 fullName/次数全部保留；W6的2363也全保留，W7新增21；[增量](unit-delta.json) |
| typecheck / browser typecheck | PASS / exit0 | 正式app与E2E TS配置 |
| build | PASS / exit0 | 实际生产bundle；保留大chunk警告 |
| tooling / accounting | **39/39、13/13 PASS** | 0 skip/fail；语义与本地Lucide生成物check亦PASS |
| 可执行Vue审计 | PASS，**0错误** | 775前端文件、3998模块引用、33脚本、426直接/传递包、3940实际bundle模块 |
| 最终Playwright桌面 | **521/521注册项 PASS** | **518唯一项，521次执行，0retry/skip/fail；missing/excess=0** |
| W1独立React/Ant | **41/41 PASS** | 另列，不混入521；18份严格退出证据 |
| 实际App/bootstrap根卸载 | **6/6 PASS，18轮** | 6注册项已包含521；第一同步资源样本空，再等待迟到写入负控 |
| 真实Spring集成 | **NOT_RUN** | 本机8080健康接口实际errno111 Connection refused |

项目没有lint脚本/config，未宣称ESLint通过。类型、构建和既定tooling已执行。[准确命令、时间、退出状态和日志哈希](checks.json)，原完整日志/trace保留在原Cloud的 `/workspace/react-full-w7-evidence`，不将环境日志或私人信息加入仓库。

### 浏览器分批、唯一行为与范围变化

原收集566注册项/563唯一项只是可收集清单；最终重新收集为 **575/572**，仍不是通过数。[最终收集](final-discovery.json)及[原完整清单](test-discovery-baseline.json)均保留。

用户取消的54个纯窄屏注册项不执行，测试源码保留。混合用例中另有36文件的50处窄屏分支保留原断言但本轮范围外；这些不是额外50个注册项，也不是桌面skip。[逐项范围外记录](out-of-scope.json)。没有以取消窄屏移除其他业务合同。

原桌面512注册项，加[9个长只读图1440px等价案例](desktop-additions.json)，形成最终521。三条共享read-consistency合同分别在旧合同入口和W3生产入口真实执行，因此521比518唯一行为多3；浏览器项目标签变化不算新增行为。最终每注册项只有一个passed attempt，重试0。诊断、修复前运行和W1框架不累加为全站通过数。

| 最终批次 | 注册项 | 执行者 | 运行入口 |
|---|---:|---|---|
| 旧合同A/B/C/组长 | 12+49+111+88=**260** | 原三员及组长 | 真实React路由，开发构建，原业务合同 |
| W2 / W3 / W4 / W5 | **49 / 31 / 29 / 49** | 组长 / B / A / C | 最终生产构建 |
| 全31路由×三皮肤及4个guard | **97** | A | 最终生产构建，直达/reload/Back/Forward/query/hash/redirect/scope退出 |
| 整个App根 | **6** | 组长 | 编译的真实App/bootstrap隔离生命周期入口 |

根卸载输出初次环境变量名称不匹配，实际6份样本写入 `/tmp/w6-root-evidence`；只复制与本次结果匹配的6份新样本并逐SHA/18轮硬空验证，没有为搬文件重跑或加总。见[root-cleanup.json](root-cleanup.json)。

W1首次误用production preview，与原开发源码URL/函数资源归属正控不符，实际33 PASS/8 FAIL；8项均首先在RAF正控0≠1失败，不能说已跑到StrictMode重放断言。恢复原Vite开发夹具后同一脚本41/41及18份退出证据通过，源码/过滤/断言不动。17份含before的样本都有真实RAF1正控；强制owner退休一份只有after，不冒作18个before正控。React官方说明额外Effect重放只在开发模式发生。[官方StrictMode文档](https://react.dev/reference/react/StrictMode)、[两次完整回执](foundation-results.json)。共41唯一检查、82次场景执行；不是删掉8个失败或只重试失败项。脚本会写旧受控证据，因此每次执行后逐字节恢复历史文件，本次新记录单独留存。

## 本轮改动边界与独立审查

没有重写已验收画布底层、入口/history、API或后端。修复只针对验收暴露的等价回归：数据库id/version缓存隔离与skip-link焦点；Role桌面局部布局；Task权威终止投影；Knowledge accepted仅读恢复及引用空间；PPT资料/手动/恢复草稿查看；Catalog原评分/历史入口；Designer权威交接与错误；工作流内置只读及画布留白；需求预览父实例保留、执行布局dirty、StrictMode合法读取、分段保存/推送原身份恢复提示。

旧E2E迁到真实React统一语义、选择后上下文、真实Ant确认和真实DTO；保留原动作、请求次数/身份/CAS、File字节与顺序、权限及业务保护，建立[原断言→新路径映射](test-mapping.json)、[全部route/action责任表](task-system.md)和各分组清单。Role单测只修歧义details定位；Node单测只修真实就绪及短act导航，未强改disabled DOM；PPT原三条Recovery字节不变，补正式只读恢复负控。Strict资源负控仍拒绝未知RAF、退休callback复用、detached观察关系；没有放宽空数组、全局伪造事件、删除外部监听或运行时改库私有状态。

复用原三名gpt-6.1-sol/xhigh，实际task名称未变；模型设置沿用原启动回执，当前工具不提供实时模型配置读回，不虚构新团队。

| 原成员 | 真实执行及非作者复核 | 证据 |
|---|---|---|
| A react_flow_workflow | 自执行12+97+29=138；非作者Role相关50、C76+B18=94；30张非A默认图与12高级状态图，只读核W5的64首样/原身份，不冒自己跑C49 | [task-system](task-system.md) |
| B react_legacy_canvas | 自执行49+31=80；复核A Task、组长DB/焦点/资源负控；实际看W5全部30图，其中18张Workflow/Designer属于非作者、12Requirement明确有自身来源不冒独立 | [W3及独审](knowledge-ppt-template/README.md)、[逐图边界](knowledge-ppt-template/non-author-release-images.json) |
| C react_ppt_canvas | 自执行111+49=160；复核B Knowledge/PPT/Catalog、W1启动模式与最终41/18、W4 Task实际图；自身30图只计作者机器稳定性 | [W5及独审](workflow-designer/README.md) |
| 组长 | 单一共享入口/依赖/证据工具/源码冻结与集成；实际88+49+6=143、独立W1；所有business修复均有原组员非作者复核 | [首错与修复](regressions.md) |

W3实际69退出首样、W4实际44首样（36route+4root+2nativeSSE+2active）、W5实际64首样（45route+5root+2nativeSSE+12active）均严格保原门槛。W6实际93页scope退出及App根18轮证明各层归属；App级同身份MQL在route之间本应继续存活，最后root另行释放。计数存在行为重叠，不把首样总和当唯一业务或独立测试总数。

## 历史11项逐一闭合

[11条逐ID/实际原title及结果](historical-11.json)，旧[W0首次与修正后结果](../w0/historical-failures.md)不改写。根因为8个过时字段/文案预期、2个private/tmp截图运输问题，以及1个旧Automations route预期叠加真实合法消费者缺口。

| ID | 根因/合同去向 | 本轮终态 |
|---|---|---|
| HF-DB-1440 | 旧host→真实JDBC/username；原test/save、任务批次投影保留 | 原title真实PASS |
| HF-DB-390 | 同合同及历史窄屏布局 | OUT_OF_SCOPE，未跑 |
| HF-DOC-1440 | /private/tmp截图ENOENT；原File、同key2POST、按需正文/报告、下载/reload合同 | 原title真实PASS |
| HF-DOC-390 | 同运输根因与原合同 | OUT_OF_SCOPE，未跑 |
| HF-AUTOMATIONS | 旧route＋实际历史read/export消费者缺口，W0after仍FAIL；W3显式archive读取合法health、九GET/两导出、刷新清旧error，不恢复退役write | 原完整健康恢复合同在旧/生产入口均PASS；不是仅alias PASS |
| HF-ROLE-spdb-1440 | 旧精确文案；原CONFIG_ONLY/limitations/权限/工具/版本/桌面布局 | 原title真实PASS |
| HF-ROLE-spdb-390 | 同文案与权限合同 | OUT_OF_SCOPE，未跑 |
| HF-ROLE-tech-blue-1440 | 同文案与权限合同 | 原title真实PASS |
| HF-ROLE-tech-blue-390 | 同文案与权限合同 | OUT_OF_SCOPE，未跑 |
| HF-ROLE-github-white-1440 | 同文案与权限合同 | 原title真实PASS |
| HF-ROLE-github-white-390 | 同文案与权限合同 | OUT_OF_SCOPE，未跑 |

结论是6个桌面实际通过、5个取消范围不执行；不是“11全绿”或笼统忽略历史。历史数据/导出保留；旧create/start/rule/import写接口没有复活。

## 首错、波动与剩余限制

第一冻结a882…全量unit实际2373/2383，10失败；停止并发浏览器后原Settings/route未改超时即可通过，Role定位、Node就绪夹具及PPT真实只读入口分别修复。第一冻结浏览器计划357注册，实际325 PASS、8 timeout FAIL、1 INTERRUPTED、23 NOT_RUN；所有原trace/JSON保留，后两者不是skip刷绿。[逐首错与最后状态](first-failures.json)。8个浏览器超时的相关E2E没有为时限修改，最终最多2Chrome且unit/build先完成，原30s整案一次通过；标记诊断负载波动，不保证慢设备绝无波动。更早候选和关联复验命令按分组保留，不加到最终521。

- 真实Spring human/system集成不可用：实际GET `http://127.0.0.1:8080/actuator/health` 返回Connection refused/errno111。未下载JAR/Maven、未启动另一工程、未绕过权限。真实后端、付费模型、真实文件解析、真实数据库连接与git发布/服务端stop proof端到端均未验证。
- 恢复能力按已有接口：pending/unknown保原key/body/id/version/revision/File/draft；accepted仅读核对；无by-request GET的需求创建只允许用户显式同key/body幂等POST，不发明GET。没有后端/存储支持的跨刷新File或完整持久恢复不作保证。
- 资源断开不等于所有GC对象立即消失。退出首样验证自有监听/RAF/timer/capture与observer的target关系释放，保留外部哨兵；已断开对象在采样中仍存活、测试账本持有引用的边界不隐藏，不冒称堆无泄漏证明。触控结论限Chromium实际Pointer/CDP仿真，未测实体设备。
- [实际构建大小](build-artifacts.json)：主JS **1,630,732 bytes**（可复现gzip level9 **497,252 bytes**）；cynefin690,830、ReactIcon568,138、mermaid.core564,142 bytes。大chunk警告没有提高阈值；首次加载/解析风险保留，不扩大W7做包体优化。
- 两张partial恢复图顶部重复阻断提示局部遮字；下方完整阻断正文与恢复动作持续可见，三方已实际看图，作为非阻断像素例外保留。其他紧凑metadata/重复短refresh标签属于既定context变体或未定视觉观察，不冒称功能bug。截图不覆盖所有权限/历史/恢复组合，File屏外字节依据请求断言。

本轮交付本地候选，不将模拟浏览器全过称为真实后端全过。项目经理决定最终接收和后续发布；此前发布授权不延用。

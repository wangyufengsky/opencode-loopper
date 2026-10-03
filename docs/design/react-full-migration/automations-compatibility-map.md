# Automations 退役边界与兼容能力映射

本文件是全 React 迁移的 **W0 设计门禁**，不是实现完成记录。2026-10-03 在 `feat/react-full-migration`、源码基线 `0717a4a5c3f5bf6fb61af8208136d5353e05a941` 只读核对前端、后端、SQL、产品合同和测试；本轮只新增本文件，未安装依赖、运行生产测试、启动浏览器或修改生产代码。作者是原团队 `react_legacy_canvas`；原启动记录显式为 `model=gpt-6.1-sol`、`reasoning_effort=xhigh`，当前工具没有实时配置字段，不能把该记录当作实时平台查询结果。

**结论：旧 Automations 的历史查询、导出、持久化与既有 run 对账仍存在；旧公开写入已经明确退役。当前没有路由挂载旧 View，也没有在正式 TemplateTasks 页面消费这些历史查询。** `/automations` 重定向成功不等于这些能力已经整合。孤立 View 的按钮、mock 成功回执、可直接调用的 Java Service 都不能证明当前 HTTP 写入可用。迁移必须补齐历史读能力的可达映射，保留退役拒绝和旧数据保护，不能擅自恢复已退役的创建、触发、导入或密钥签发。

## 证据口径与准入状态

权威产品合同是 [功能合同](../../seven-feature-contract.md) 第405–407、432–446行：冻结 v1/v2 保持原验证语义；新自定义模板编辑、导入和事件/定时规则已退役；V77 后仅手动选择服务端目录及参数，旧模板、规则、检测健康、运行记录保留查询。执行历史与模板目录分层。

下表所用 `FE` 为 `frontend/src/`，`BE` 为 `src/main/java/io/opencode/loopper/`，`BT` 为 `src/test/java/io/opencode/loopper/`；行号对应本轮静态源码，测试引用只证明断言存在，**没有本轮通过结果**。

| 标记 | 准入含义 | 当前责任与下一步 |
| --- | --- | --- |
| C：兼容读存在，UI 缺失 | 当前 GET/导出可用的静态代码证据；正式 UI 没有等价消费者 | 组长负责 W0 定下归档入口与合同；legacy 模块作者负责 W3 React 展示/controller；后端兼容 owner 保持原读协议。缺入口、字段映射或行为证明时阻断生产迁移中的删除/替换。 |
| R：公开写入退役 | 有产品合同、Controller 拒绝和部分回归断言 | 保持拒绝，不迁出可执行写按钮；组长与后端兼容 owner 持有全端点拒绝门禁，legacy 模块作者不能改为调用内部 Service 或新模板任务写接口。 |
| H：历史内部合同 | Service/Mapper/SQL/领域状态机仍可读写或直接测试 | 保留冻结数据、兼容测试和历史 run 对账；没有功能移交与行为等价证据不得删。保留内部方法不意味着重新开放用户操作。 |
| L：有限后继能力 | 当前 TemplateTasks 自身的目录、参数、确认/开始与历史导航 | legacy 模块作者持有 TemplateTasksView、templateTaskStore 及其新模板创建 controller；workflow 作者持有 Document/Source run/store 的分支协议接口；任务模块持有 Task 读取/恢复。它们只替代批准的新任务入口，不等价于旧规则、旧 run、旧模板管理。 |
| P：待定归档呈现 | 本文件提出的必要 UI 映射，还没有实现或验收 | 必须由组长在 W0 结清位置、消费者、数据范围、测试迁移；W3 实现之前不得写成“当前支持”，W6 退出 Vue 之前必须有实际 React 消费链和测试。 |

上表区分设计准入与实施验收：W0 可以批准保持退役和补齐历史只读呈现，但未实现的 P/C 项不会自动转为通过。旧 View/VTU 测试退出的前提是每个断言已有明确历史兼容、退役拒绝或 React 行为去向。本文“后端兼容 owner”指现有 Java Service/Controller/Mapper 的职责边界；本轮只审既有合同并设计后续测试，不授权 Java 改动，也不另设成员。全站仍使用原三人分工。

## 当前路由与 UI 消费链

| 实际入口/消费者 | 当前行为与范围 | 原能力去向及门槛 |
| --- | --- | --- |
| `/automations` | `FE/router/index.ts:25` 固定 redirect `/template-tasks`，没有 `component`；`BE/web/SpaFallbackController.java:18–25` 只负责把旧深链交 SPA。`BT/web/SpaFallbackControllerTest.java:37–43` 验证 HTML fallback，不验证浏览器 redirect 或旧能力可达。 | 保持旧书签 redirect，W6 不另建历史 history owner。需补真实路由测试，证明不挂旧 View、不调用旧写入；单条 redirect 断言不能替代历史读取/恢复测试。状态：L/P；owner：组长。 |
| 主导航 | `FE/components/AppSidebar.vue:23–35` 只有“模板任务”，没有自动化链接。 | 当前没有历史自动化入口；归档入口须在既定导航/TemplateTasks 设计内明确，不能默认视作已经存在。状态：C/P；owner：组长＋legacy。 |
| `AutomationsView.vue` | 物理 SFC 存在，生产路由未 import；本轮检索 `views/components/stores/react/domain` 后，除它和 `AutomationHealth` 自身外未见旧能力消费者。`:65` 读取 projects＋workspace；`:88–102` 仍有创建/发布/规则启用/触发/导入确认及历史表。 | 孤立 UI 仅为历史行为参考。其写按钮不能挂回生产；只读部分、错误/刷新、历史字段要有 React 归档映射，写断言转历史或退役拒绝合同。状态：C/R/H；owner：legacy。 |
| `AutomationHealth.vue` | 唯一非测试 SFC 源码引用是旧 View `:7,100`；组件 `:9–17` 区分未检查/失败/正常、检查与成功时间。 | 正式页面目前不可达；按当前版本匹配的历史诊断映射到归档消费者，不把已停用的 Git/CRON 当作仍在检测。状态：C/P；owner：legacy。 |
| `/template-tasks` | `FE/views/TemplateTasksView.vue:30–34,110–145,149–154` 读取目录、项目、参数；source/document/report 各自启动；需求开发转默认流程；“历史任务”去 `/tasks?type=template`。未读旧 workspace/rules/templates/runs。`BE/service/TemplateTaskService.java:37–48` 从服务端枚举拼目录，不读取 `loopspec_template`。 | 只承接已批准的新目录任务，不能当旧模板 CRUD/规则运行/导入的等价消费者。状态：L；页面与 templateTaskStore/controller owner：legacy；Document/Source 分支协议接口 owner：workflow。归档映射另属 P/C。 |
| 新模板任务 controller/store | `FE/stores/templateTaskStore.ts:9–24` 保存 requestKey＋已确认 taskId，创建后 Start；`FE/api/client.ts:1700–1705` 调 `/api/template-tasks`；`BE/api/TemplateTaskController.java:23–44` 采用独立 Request/Created。 | 不得将旧 rule/version/run ID 直接当新 templateId/taskId；不能把旧 AUTO_START/REVIEW_REQUIRED 解释成新页面的提交按钮。状态：L；前端 templateTask controller/store owner：legacy；既有后端合同仅只读审查。 |
| `/tasks` 与 Task 详情 | 可查看已经创建且仍存在的普通 Task；新模板历史入口筛选模板来源。旧 run 的 `taskId` 可为 null，`draftId` 也可为 null；旧自动化创建的是原普通 draft/task，见 `BE/service/AutomationService.java:274–317`。 | 仅有有效 taskId 的记录可以复用 Task 详情深链；不得把 `/tasks?type=template` 当作覆盖未确认、失败入场、SKIPPED 或未绑定 Task 的旧 run。状态：L/C/P；owner：任务模块＋legacy。 |

该结论是静态 import/call 链盘点，未声称本轮浏览器证明。旧 client 方法仍在已打包 API 对象中，不代表页面实际调用。没有在正式消费者中找到旧调用不构成删除后端、数据或测试的授权。

## 当前公开 API：9 个历史 GET 与 10 个退役操作

API 基础路径由 [AutomationController](../../../src/main/java/io/opencode/loopper/api/AutomationController.java) `:20` 定为 `/api/automations`。frontend client 基础路径负责补 `/api`；下表写完整 HTTP 路径，避免把 SPA `/automations` 和 REST 混为一谈。

### 历史读取与导出

所有以下 GET 在 Controller 中直接读 Service，不经过 `requireLocalUi`。这是 Controller 层证据，不是对全服务部署、远端访问或额外权限的授权。九项均为 C：后端兼容读 owner 保留；legacy 作者接 React 消费者；目前 UI 无等价入口。

| 当前 GET | 实际返回/注意事项 | 源码、client 与待证实 |
| --- | --- | --- |
| `/api/automations/templates` | TemplateView 列表，含全部不可变 versions；不是新内置 catalog | `Controller:26`；`BE/service/LoopSpecTemplateService.java:51–58`；`FE/api/client.ts:1820`。归档需覆盖 ACTIVE/ARCHIVED、空列表、多版本、失败后刷新。 |
| `/api/automations/workspace` | templates/rules/runs＋服务端 serverTime；非分页聚合，不能臆称数据库事务一致快照 | `Controller:27–29,84–86`；`FE/api/client.ts:1835–1839`；旧 View `:65`。需给原 run/health/版本独立身份，刷新结果清旧告警且拒迟到作用域。 |
| `/api/automations/templates/{id}` | 单个历史模板＋版本 | `Controller:34`；`LoopSpecTemplateService:50,118`。当前 client 未提供该独立方法，旧 View 不读取；若归档按需调用，补纯 TS adapter 与真实响应测试，不新增后端能力。 |
| `/api/automations/templates/{id}/versions` | 版本倒序，保留 spec、hash、immutable、autoStartApproved、createdAt | `Controller:38`；`LoopSpecTemplateService:58,119`；`BE/persistence/LoopperTaskMapper.java:503`。当前 client 无独立列表方法；版本号不能重排或升级 schema。 |
| `/api/automations/templates/{id}/export` | 返回最新版本原 `spec_json`，不是全部版本/模板/规则；无版本时拒绝 | `Controller:47–48`；`LoopSpecTemplateService:103–107`；Mapper `:503` 倒序。当前 client 无该独立方法；不得与整工作区导出混用。 |
| `/api/automations/templates/export` | WorkspaceExport `formatVersion=1`，全部模板及版本、规则绑定/triggerConfig；不导出运行历史/health/secret/hash | `Controller:49–50`；`BE/service/AutomationService.java:117–125,468–478`；`FE/api/client.ts:1833`。原按钮在死 View `:71,99`。需真实 HTTP JSON/下载/秘密排除断言；不能称完整数据库备份。 |
| `/api/automations/rules` | RuleView：project/version 绑定、triggerType/config、state、approvalMode、updatedAt/version、可选当前 health；无明文 token 或 token hash | `Controller:52`；`AutomationService:109–111,431,465`；client `:1834`。需四种 trigger、两种 approval、停用后历史信息和缺失 health 不伪装成功。 |
| `/api/automations/rules/{id}/runs` | 指定规则所有 run；先核查 rule 存在 | `Controller:56`；`AutomationService:114`；`BE/persistence/AutomationMapper.java:21`。当前 client 没有独立方法；不可混入另一个规则的 run。 |
| `/api/automations/runs` | RunFeed：完整 runs＋serverTime，按 detectedAt/id 倒序 | `Controller:57`；`AutomationService:115,482`；`BE/persistence/ReadModelMapper.java:372–373`；client `:1844–1847`。需未绑定 Task、历史终态、证据/错误、空数组与服务端时间。 |

**导出边界：**整工作区导出的 WorkspaceRule 只有 id/name/projectId/templateVersionId/triggerType/triggerConfig，**没有** rule state、approvalMode、version、health、lastObservedHead、token hash，也没有 runs；模板版本导出含 spec/hash/approval，不含 DTO 的 immutable 字段。`AutomationService:119–125,471–478` 是实际字段证据。保留现有导出格式，不添加秘密、不假称可以重建全部旧数据库；数据库历史保留是另一门槛。

### 退役写入与触发

`AutomationController:77–82` 的 `requireLocalUi` 在缺/错误本地标识时抛 `LOCAL_UI_HEADER_REQUIRED`；**即使标识是 `1`，仍立刻抛 `LEGACY_AUTOMATION_RETIRED`**。`:31–59` 的九项操作均先走该方法；`:65` 的 webhook 无条件退役。`BE/api/ApiExceptionHandler.java:25–26,51–54` 把 Conflict 映射为 HTTP 409 Problem Detail。下述 409 指合法可绑定输入经过权限判断后的结果；非法 JSON/enum 可先返回 400，不把绑定失败误称退役成功。

| 操作（8 POST、2 PUT） | 孤立 UI/client/历史内部 owner | 当前准入与必须保留的边界 |
| --- | --- | --- |
| POST `/templates` | 旧 View `:66,88`；client `:1821`；`LoopSpecTemplateService:39–47` | R。不可迁出“创建自定义模板”。合法请求带 localUI 仍409，Service/SQL无副作用；模板读/版本保留另属C/H。 |
| PUT `/templates/{id}` | client `:1822` 有 name/description/state/version；旧 View **没有**编辑、归档/恢复按钮；Service `:60–75` 持 CAS/状态迁移 | R/H。保留已归档状态与原CAS合同；不能把“模板CRUD”写成当前完整 UI。无 DELETE API、无旧删除按钮；不能据React迁移新增删除。 |
| POST `/templates/{id}/versions` | 旧 View `:67,91`；client `:1823`；Service `:78–87` | R/H。版本发布/允许自动开始已退役；保留精确 spec/hash/不可变与既有授权标志，不变成新 catalog 的版本编辑。 |
| POST `/templates/import/preview` | 旧 View `:69,99`；client `:1824`；`AutomationService:128–138` | R/H。**preview 本身也公开退役**，不能只禁 confirm 后继续提供可执行 preview。历史解析/校验/无写断言保留为内部合同。 |
| POST `/templates/import/{previewId}/confirm` | 旧 View `:70,99`；client `:1825–1831`；Service `:141–166` | R/H。禁止重新开放导入创建、映射新版template任务或自动重试；过期/单次消费/原子写/新secret只属历史内部语义。 |
| POST `/rules` | 旧 View `:56–68,95`；client `:1841`；Service `:70–85` | R/H。原 create 强制 DISABLED＋REVIEW_REQUIRED，不接受调用者启用/自动开始；该防护与一时secret的历史测试保留，但生产当前不会成功签发。 |
| PUT `/rules/{id}` | 旧 View `:72,100`；client `:1842`；Service `:88–106,396–413` | R/H。启用、停用、AUTO_START切换与编辑均退役；旧version/CAS、不可变版本审批边界保留，不把按钮换到新页面。 |
| POST `/rules/{id}/trigger` | 旧 View `:73,100` 仅 MANUAL显示；client `:1843`；Service `:169,264–317` | R/H。旧MANUAL立即触发退役；新TemplateTasks手动提交是L，无旧rule关联/幂等键，不能称等价搬迁。 |
| POST `/runs/{id}/confirm` | 旧 View `:74,102` 仅 REVIEW_REQUIRED显示；client `:1848`；Service `:183–209` | R/H。**已有REVIEW_REQUIRED run也不能由此公开确认生成Task**；仅保留状态与证据读。新任务确认/Start不消费旧draft/run，不能把旧待确认映射成新待开始。 |
| POST `/webhooks/{ruleId}/{token}` | `Controller:61–65`；Service `:171–180`；旧创建/导入响应含一次性 token | R/H。旧token、loopback地址、deliveryId均不能绕退役；不增加新Webhook设置/重发/轮换按钮，不从hash反推或显示旧secret。 |

当前已有 `BT/api/AutomationControllerTest.java:28–43` 覆盖带/不带localUI的 template create、409及无Service交互；`:47–60` 覆盖旧Webhook token仍不能派发。**未见该测试把其余八条操作逐条参数化覆盖**：需补合法input下全10条拒绝及无行/任务/草稿/secret变化，不能把两项代表测试说成全端点已经动态通过。

## DTO、持久化与兼容读写 owner

实际 Controller 返回的是 Service records，不是只凭 `FeatureContracts.*Dto` 推测；FeatureContracts `:57–139` 仍定义对应请求/兼容形状。client normalizer 则是展示投影，不能把它当持久化重写格式。

| 对象/字段 | 当前兼容语义与读写 owner | 静态证据、准入与缺项 |
| --- | --- | --- |
| `loopspec_template` | id/name/description、ACTIVE/ARCHIVED、createdAt/updatedAt/version；内部创建/更新经lifecycle＋CAS。公开只读，历史状态不能重置 | SQL [V14](../../../src/main/resources/db/migration/V14__template_automation.sql) `:1–9`；`BE/persistence/LoopperTaskMapper.java:492–499`；`LoopSpecTemplateService:39–75,141–142`。C/H；frontend `types/domain.ts:1087–1094` 当前不投影createdAt，归档若需要时间必须从真实DTO补读，不能臆造。 |
| `loopspec_template_version` | template_id外键；version_number/hash各自唯一；spec_json、immutable=1、auto_start_approved、createdAt。UPDATE与DELETE被DB触发器拒绝；冻结v1/v2不可变，不以迁移升级配置 | V14 `:11–40`；Mapper `:500–504`；Service `:79–87,109–122,143–145`；frontend `types/domain.ts:1076–1084`、`api/client.ts:1400–1429`。C/H；归档和导出保留精确版本/hash；normalizer的parseLoopSpec不是原spec_json持久化来源。 |
| `automation_rule` | project FK、精确template_version FK（RESTRICT）、四种trigger、两种state/approval、trigger_config_json、hash、last_observed_head、时间/version；内部Service/Mapper具CAS，公开写退役 | V14 `:42–85`；`BE/persistence/AutomationMapper.java:9–17`；`AutomationService:72–106,396–413,465`。C/R/H；GET规则不包含hash/lastHead/createdAt，归档不得假称全部SQL字段可经现GET读。 |
| `automation_run` | 独立id、rule、trigger、唯一idempotency_key、七种state、可空draft/task、evidence和检测/开始/结束时间；V15追加version。rule删除会cascade；Task/draft删除使绑定SET NULL，不等于run可以删除 | V14 `:87–102`；[V15](../../../src/main/resources/db/migration/V15__lifecycle_state_machine.sql) `:22`；Mapper `:18–32`；`AutomationService:432,467,482`；`FE/types/domain.ts:1140–1157`。C/H；API RunView不返回持久化idempotencyKey/version，不能制造新命令身份。 |
| run写与恢复 | `AutomationRunPersistence:23–36` 用REQUIRES_NEW＋lifecycle持久化检测/失败证据及CAS；应用启动`StartupRecoveryCoordinator:33–39`→`AutomationService:230`→poll；定时poll `:212–219` **只对既有run对账，不调用Git/CRON触发** | `AutomationService:321–329` 根据Task映射run状态；Mapper `:28–32` 只读带taskId的活动run；`ReadModelMapper:372–376` 批量读历史run/versions。H；迁移UI只读，不拥有后台任务或另起浏览器调度；不能删除server恢复owner。 |
| `automation_poll_health` | rule_id/rule_version、CHECKED/FAILED、检查/成功时间、失败次数、安全错误。独立健康投影，不改rule状态、不是run成功；GET只显示与当前rule.version匹配的行 | [V76](../../../src/main/resources/db/migration/V76__read_polling_health.sql) `:1–14`；`BE/persistence/AutomationPollHealthMapper.java:9–35`；`AutomationService:109–111,222–226,431`；`AutomationPollHealthService:16–30`；frontend `types/domain.ts:1097–1104`/`client.ts:1331–1341`。C/H；V77推进rule.version后旧health行仍保留但读投影可能消失，不能假称现API包含所有过期诊断。 |
| V77退役升级 | 原ENABLED→DISABLED且version+1，保留规则/run/版本数据；新`template_task_run`是独立合同，不把旧rows迁成新Task | [V77](../../../src/main/resources/db/migration/V77__builtin_template_tasks.sql) `:61–63`；`BT/persistence/TemplateTaskMigrationTest.java:15–40`。H/R；升级测试保留旧Task模式/FK，明确旧rule version7→8。不能编辑旧迁移、删表/旧数据或将此例当全部旧run/health保留验证。 |
| lifecycle与历史权限 | template归档/恢复，rule启停，run检测→评审/队列/运行/终态机器仍注册；AUTO_START在Service与DB同时要求精确不可变版本已批准 | `BE/lifecycle/LifecycleRegistry.java:511–537`；V14 `:58–82`；`AutomationService:410–413`。H；枚举和状态机存续不等于HTTP重新授权，UI中文标签仍区分规则approval与run状态（`FE/utils/displayLabels.ts:31,40–42`）。 |

健康“恢复”边界需单列：当前 `poll()` 不再执行Git/CRON；它可记录对账失败，但检索生产调用未见 `AutomationPollHealthService.success()` 调用。因此旧fixture将FAILED换成CHECKED只证明展示消费新读结果可以清旧告警，**不证明当前后台会恢复旧Git检测或自动产生CHECKED**。按真实DTO验证缺失、失败、历史CHECKED/新读变化，不引入新检测能力。

既有 run 对账还有一个**仅静态发现、待聚焦证实**的差异：`AutomationService:436–442` 只把 SUCCEEDED 映射为run成功，FAILED/CANCELLED为失败，QUEUED为队列，其余包括 COMPLETED/SUPERSEDED 都落RUNNING；而 `BE/domain/TaskState.java:18–19` 认定 COMPLETED/SUPERSEDED 已终态。`AutomationServiceIntegrationTest:49–66` 只验证活动run筛选/幂等键查找，没有实际poll后这两种Task的run状态断言。后续兼容测试设计应列这些终态，明确历史预期后再决定是否需要独立后端修复；本轮没有运行复现，不把它称React迁移回归或已经修复，不因此自动修改Java状态映射。

## 历史能力逐项去向：不可等价合并的部分

| 原能力 | 当前保留/退役事实 | TemplateTasks内必要呈现建议与准入状态 |
| --- | --- | --- |
| 模板 C/R/U/D、发布版本 | GET/导出保留；C/U/发布公开退役；D从未在该Controller/旧UI提供。内部archive/restore有CAS，但旧View无对应按钮 | C/P：按需历史模板列表/版本详情/导出，展示冻结spec/hash/批准标记；不新增修改、发布、恢复或删除命令。旧模板不是服务端内置catalog项。 |
| MANUAL | 原立即触发绑定rule＋版本，生成原draft/run；现409 | R/H：历史规则/触发类型/运行记录可读，退役说明可见。L：新目录模板的显式开始保持自己的requestKey/taskId协议，不能映射旧rule立即触发。 |
| AUTO_START | 是旧rule的approvalMode，不是triggerType；原必须批准精确不可变版本；现不能启用 | C/R/H：展示历史审批标志/模式，明确规则已退役；不提供自动开始开关。不把新模板Start按钮、Designer自动模式或新流程自动执行当等价消费者。 |
| REVIEW_REQUIRED | 既是rule审批模式，也有run状态；原run通过confirmReview才确认draft/开始Task；现该confirm也409 | C/R/H：保留历史待确认状态、draft/task是否存在及证据；没有批准的新恢复动作，不展示“确认生成任务”按钮或擅自转入新TemplateTask。 |
| CRON/时区、GIT_HEAD_CHANGED | persisted配置/lastHead仍在；原cron expression兼容`cron`键（Service `:250–254,418–425`）；当前poll不触发、不探测Git | C/R/H：只读历史trigger/config/健康/更新时间，不能新增调度器。frontend optional branch不能证明当前backend按branch检测（Service `:427`对Git归一为空配置）。需要旧`cron`键读投影夹具；现client `:1364`要求expression，不能宣称所有历史配置已验证兼容。 |
| import preview→confirm | 内部preview全payload验证且不写，10分钟内存preview；confirm复核、单次consume、重新分配版本、强制DISABLED/REVIEW_REQUIRED；两个公开endpoint均退役 | R/H：历史格式/安全测试归档到纯TS兼容parser、serialization或后端历史Service测试；不提供能写数据的导入入口。需要记录数据流/断言去向后才能移除死View的preview/confirm UI测试。 |
| WEBHOOK与一次性secret | 内部Service创建/导入返回明文一次，存SHA-256；list/export不返回token/hash；loopback/token/deliveryId保护仅为内部历史合同。HTTP现在均拒绝派发/签发 | C/R/H：历史trigger标记及退役状态可见；不展示或恢复旧secret、不设置新Webhook、不给URL带明文secret、不自动轮换。RuleMutation DTO与秘密排除断言保留；模仿一次性弹窗不能称当前支持。 |
| workspace/单模板导出 | 两种现GET仍保留，内容范围不同；整工作区不是完整DB备份 | C/P：历史归档显式导出动作由一个read owner调用原GET，验证格式、范围和secret排除；不以此替代run/health数据保留。 |
| 原run历史/失败/队列/恢复 | 七状态、可空Task/draft和失败evidence保留；已有绑定Task后台对账继续，未绑定REVIEW_REQUIRED无公开确认 | C/H/P：只读归档必须覆盖全部run，不只新模板Task列表。有效Task可链接现详情；已删Task/空绑定显示历史状态，不造新Task。保持错误→刷新新读投影清旧告警，未确立当前实现的恢复动作不得添加。 |

### 归档呈现方案与单一 owner（待 W0 结清）

建议在既定 TemplateTasks 信息架构内提供用户主动打开的“历史模板与自动化记录”只读入口，而不是恢复旧自动化工作台。可采用归档 Drawer/独立局部面板，但**当前没有这个入口，方案未实现**；不新建业务能力或擅自增加路由。默认目录仍只读server catalog、填写参数，执行记录仍去Tasks。原功能合同 `:444` 及 `frontend/e2e/template-tasks.spec.ts:95–109` 禁止模板默认页面加载执行历史；归档run的展示位置、按需加载触发与该条合同的明确区分必须由组长在W0记录，不能先把旧历史表塞入默认目录再宣称等价。

必要数据流是：用户显式打开归档 → 一个纯TS历史读取 controller → 现有9个GET的已批准子集 → React只读DTO/版本与规则归属检查 → 显式刷新/导出/有效Task深链。没有可执行旧写命令，没有浏览器cron/Git探测，没有第二个server恢复owner。controller按archive实例与request generation拒绝卸载/切记录后迟到读回，关闭后撤销订阅/监听/下载URL；此清理要求是未来实现门槛，不是当前旧View已有证明。

职责不改变总波次：**W0**组长＋legacy明确归档入口、逐断言去向和本文件C/P缺项；**W1**legacy提取只读DTO/parser/read controller与一份snapshot owner，并持有 templateTaskStore 的新模板创建controller；**W3**legacy实现 TemplateTasks React 页面及邻接归档消费者，workflow提供既定 Document/Source run/store 的分支协议接口、任务模块保留Task详情owner；**W6**组长原子换根/路由后删除已映射的孤立Vue View、VTU夹具；**W7**验证作者完成相邻测试、当前恢复入口及全站门禁。TemplateTasksView、templateTaskStore 和 newpage/controller 由 legacy 同一作者持有；新旧页不得各自创建第二套命令队列或订阅，workflow不能再分出一套模板确认/开始writer。历史只读归档模块不取得新模板创建controller的写权限。

## 测试行为账本：现有断言及生产开发前门禁

| 现有证据 | 实际证明的层次 | 保留/迁移去向与待补门槛 |
| --- | --- | --- |
| `FE/views/AutomationsView.spec.ts:38–41` | VTU直接挂孤立View，API被mock，loading等待 | C/P：实际React归档读未完成/失败/显式重试；不能只保留一段静态loading文字。 |
| `FE/views/AutomationsView.spec.ts:44–59` | mock create返回一时secret、只有MANUAL按钮调用trigger | R/H：历史按钮逻辑留归档索引；当前HTTP拒绝/无签发＋parser秘密排除要有行为去向，不能把mock成功搬进正式UI。 |
| `FE/views/AutomationsView.spec.ts:62–76` | mock rule更新带version、AUTO_START版授权、run确认 | R/H/C：SQL/Service精确版本批准与当前全写409；React归档保留旧approval/run状态区别，不暴露退役命令。新的目录confirm/start不是此case替代。 |
| `FE/views/AutomationsView.spec.ts:79–100` | mock preview先于confirm、import返回一时token、导出既有JSON | R/H/C：公开preview/confirm拒绝；内部无写预览/confirm/hash/secret；React实际GET导出。原case每条断言拆分到这些owner并记录后才可删除VTU测试。 |
| `FE/components/AutomationHealth.spec.ts:5–14` | props驱动未检查/FAILED/CHECKED，恢复值清旧告警 | C/P：纯TS health投影＋实际React归档更新；rule enablement、run success、health三者独立，缺health不伪装成功。 |
| `FE/api/client.spec.ts:729–743,746–773,1275–1281` | CRON wire往返、fetch mock一时secret、健康投影；不是实HTTP Service许可 | H/C：保留不依赖Vue的parser/wire/秘密排除测试；添加真实GET response形状。不能拿mock create通过声称服务器允许创建。 |
| `frontend/e2e/read-consistency.spec.ts:32–49` | 测试访问已redirect路径但期待旧health组件；伪造server workspace失败→成功，属于原11中旧入口失配1项 | C/P/R：拆成redirect/deep-link无旧写入＋正式归档真实端点的新读清告警，保持失败次数/原因、成功清旧错误断言。不得只删case或改成一个redirect断言；不得恢复Git轮询来让旧fixture绿。本轮未重跑，仍是待迁移/待验证。 |
| `BT/api/AutomationControllerTest.java:28–60` | 实Controller＋mockService；template create退役/权限、Webhook不可派发 | R：扩成全部10操作的合法输入拒绝矩阵，读/写分别断言；保留verifyNoInteractions，不只是找错误文字。 |
| `BT/api/FeatureContractSerializationTest.java:42–72` | 兼容DTO triggerType/config roundtrip、一时token只在mutation不在rule | H/C：继续纯后端兼容序列化测试；补实际Controller GET records与frontend decoder合同（它们不是同一个record class）。 |
| `BT/service/AutomationServiceIntegrationTest.java:49–66,70–85,265–300` | 500历史run中活动对账选择＋key查询；退役poll无新run、无Git进程、无head观察、CRON不派发 | H/R：server历史对账owner保留，UI不能重新派发；未来真实服务门禁继续验证，当前文件存在不算本轮通过。 |
| 同文件 `:89–110,114–133,154–174,203–234` | 直接内部Service的默认停用/审批、loopback/token/dedup、失败持久化、冻结v1、confirm失败、AUTOMATION入队来源 | H：保留历史兼容测试；**这些测试绕Controller，不能被认定HTTP写入当前支持**。业务/数据库删除需要逐项移交，不能以Service方法未被新UI调用直接删。 |
| 同文件 `:136–150,178–199,238–261` | preview无写、hash/owner/重复ID校验、confirm后才写、importfresh token、不导出token/hash | H/R/C：历史格式/内部导入防护、公开退役、当前安全导出分层保留；新UI不增导入功能。未見现case覆盖preview过期/重复confirm动态断言，列待验证而非宣称已覆盖。 |
| `BT/persistence/FeatureMigrationTest.java:328–340` | 数据库AUTO_START准确审批主体、模板不可改、变更绑定不可绕授权 | H：SQL触发器/FK/不可变断言保留；Vue退出无权删表或删这些防护。 |
| `BT/persistence/ReadPollingHealthMigrationTest.java:12–42` | V75→V76不改规则权威/版本；health独立、级联、索引 | H：这是V76阶段证据，不与V77停用/version+1矛盾；V77后旧health revision过滤另补read projection。 |
| `BT/persistence/TemplateTaskMigrationTest.java:15–40` | V76→最新保留旧Task模式/FK、rule停用/version+1 | H/R：另补有实际versions/hash、各approval、旧run/task/draft与health的升级保留夹具；现测试无old run/health内容证明，不能夸大。 |
| `BT/config/StartupRecoveryCoordinatorTest.java:22–32`；`BT/web/SpaFallbackControllerTest.java:37–43` | startup调用historical恢复、server fallback接深链 | H/L：server恢复调用保留；新ReactRouter真实redirect是额外验收，不把HTML fallback当React页面行为。 |
| `FE/stores/templateTaskStore.spec.ts:10–28`；`FE/views/TemplateTasksView.spec.ts:31–40`；`frontend/e2e/template-tasks.spec.ts:95–109` | 新TemplateTask key/taskId重试、需求开发转默认流程、目录不下载历史 | L：legacy持有TemplateTasks页面/store/controller，按workflow专题提供的Document/Source分支合同对接；这些断言不能替代Automation rule/run/import/secret合同，归档呈现不能放宽目录无历史请求断言。 |

生产开发准入必须记录并关闭以下事项，不能以“未来整合”口头替代：

1. **D1 归档入口和数据范围（阻塞，组长＋legacy）：**明确TemplateTasks中按需历史模板/规则/健康/全部run及两种导出的可达消费者与位置；与目录不载历史合同一致。现在无消费者；任务历史不完整、单导出不完整。确认方案后再开始对应生产工作包。
2. **D2 旧写退役矩阵（已定边界，验证待补，后端兼容owner＋验证作者）：**全部10个操作、localUI/合法旧token、无Service/行/Task/secret副作用的测试设计落位；旧REVIEW_REQUIRED不重新确认。不得绕Controller或改用新模板POST来保留旧按钮。
3. **D3 数据保留和DTO差异（阻塞删除，后端兼容owner＋legacy）：**设计冻结spec/hash、v1/v2、旧rule/run/task/draft/health及V77版本行为的完整升级夹具；GET遗漏字段、过期health过滤、旧cron键、有限导出必须明确，不新增字段/权限填补假等价。已有Task终态到run的COMPLETED/SUPERSEDED静态差异另列待聚焦证实，不能用“对账保留”概括成终态行为已全部正确。
4. **D4 每条旧前端断言去向（阻塞Vue退出，legacy＋验证作者）：**死View的create/approval/confirm/preview/secret断言有H/R归属，读/刷新/导出/health有真实React归档消费者；旧失败1项分开deep-link与读恢复，禁止drop/skip换绿。对应设计与验证计划须在生产迁移前结清，实际结果在W3/W7回填。
5. **D5 同一owner与恢复语义（阻塞实现，组长＋模块作者）：**历史只读controller不取得新模板写权限，路由/关闭/晚到读回失效；server对账仍唯一owner。不存在批准的未知旧写重试/自动恢复或secret重签发，不提出新业务恢复按钮。

本文件已经明确现行边界和必要映射，但D1/D3/D4/D5尚无当前等价UI/实现证据；**不能宣称Automations兼容整合已通过或据此删除旧数据/合同/测试**。组长应在W0结清具体消费者、行为去向和工作包，之后按获批映射实施。

## 其它设计报告原句与本轮修订要求

本轮不修改其它文件。下表保留本轮盘点读到的**修订前原句**及确切歧义，供组长统一修订并链接本文件；组长已回执关键事实同步到 README/core/validation/product，本表不宣称这些原句在当前文档中仍未修复。全体报告的最终一致性由非作者交叉复核，不能用一条同步回执代替复核。

| 修订前文档/锚点 | 原句或措辞 | 必须明确的修正 |
| --- | --- | --- |
| [README](README.md) `:168` | “template/version/rule/run/import-preview-confirm/API仍存在”；“高级模板与自动化”的当前消费者/归档映射；“授权、导入确认断言保留” | 区分当前9GET/安全导出与10退役操作。改为历史只读归档入口的待定/待实现映射；授权/导入确认/secret断言属于内部历史防护＋公开退役，不要求复活写入。当前没有等价消费者，开发准入须先结清。 |
| [core-ui-inventory](core-ui-inventory.md) `:40` 路由行 | “不能借迁移删除…导入确认功能” | “导入确认”当前公开退役，应保留历史防护/拒绝合同而非可执行UI；旧模板/规则/run/安全导出/数据读兼容必须有映射，不能直接删。 |
| 同文档 `:139` 第5项 | “…API仍实际存在”；旧spec“明确保留MANUAL…AUTO_START…REVIEW_REQUIRED…导入…secret”；“兼容读写” | API存在不等于写可用，spec是直接挂死View的mock历史逻辑。改为“兼容读、退役写拒绝、历史内部合同”三层，引用Controller/合同/V77及本表，不写成现行用户能力。 |
| [validation-and-ppt-design](validation-and-ppt-design.md) `:99` | “将仍适用的失败→服务端恢复→清旧告警行为映射到正式模板/任务宿主页”；“若旧AutomationHealth独立合同仍需维护” | 指定历史诊断读投影的实际归档消费者，当前缺项；不可把新模板执行失败或普通Task恢复偷换为旧rule health，亦不可声称Git/CRON检测继续恢复。保留具体FAILED次数/原因→更新读投影清错断言、另测redirect，不新挂旧工作台。 |
| 同文档 `:180` route矩阵 | “W0新增正式redirect与恢复合同对映” | 需链接9GET/10退役及历史归档行为矩阵；redirect之外的旧health、模板版本、run、导出/数据保护门槛不能隐去。 |

其它报告当前 `/automations` redirect 的路由统计与31records/27mountedpages/28physicalviews不受本发现改变；死View依然计入物理SFC技术债。README总表W0/W6、workflow专题仅保留redirect的描述成立，但不能作为业务映射已经完成的证据。

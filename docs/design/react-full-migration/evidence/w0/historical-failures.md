# W0 原11项历史失败验证台账

当前基线为 `008542f0bf02dc1c1b76f1e75429aab8452b797d`，验证作者为既有 `/root/react_ppt_canvas`。原启动记录为 `gpt-6.1-sol / xhigh`；本轮工具不能实时读取平台模型配置。本文件及相邻 JSON 由该作者持有，生产实现、后端、依赖配置和新 UI 不在授权范围。

## 当前状态

本轮已在008542f0四份测试原样不变的情况下取得 **before：0通过／11失败，exit1**；最小测试纠正后取得 **after原11：10通过／1失败**。完整四文件增加1项redirect负控，共 **20项：19通过／1失败，零skip、零flaky，exit1**。其它原有8项全部通过。现有纯health合同另运行 **2通过／0失败，exit0**，不计为UI恢复等价通过。唯一剩余FAIL是正式历史health消费者缺失，原可见恢复断言保留，**生产准入尚未闭合**。

上述为已经冻结的历史实际运行总数。最新用户指示取消窄屏设计／适配验收，仅接受桌面范围：**当前范围15项＝14 PASS／1 FAIL；已跑390px另5项＝5 PASS，仅作为 OUT_OF_SCOPE 历史证据**。原11中当前桌面6项＝5 PASS／1 FAIL，窄屏历史5项＝5 PASS。原始log/JSON、截图、sourcehash、全部测试及断言不删不改，不重跑W0，也不把历史窄屏通过继续当作当前设计门禁。

真实浏览器为 `/usr/bin/chromium`，使用原Playwright配置由单worker自动启动41773 Vite；浏览器运行后服务已退出。具体版本、各次命令／退出码／时间、11条首错、trace附件、before／after源码SHA-256及结果文件hash见相邻[JSON台账](historical-failures.json)。原环境日志和完整源文件副本保存在 `/workspace/react-full-w0-evidence`，不提交到仓库。历史c26bf3bf记录仍保留，未拿它替代本轮before。

| ID | 当前测试文件／场景 | 原失败数 | 历史首错与当前源码证据 | 本轮 before／after |
| --- | --- | --- | --- | --- |
| HF-DB-1440、HF-DB-390 | `frontend/e2e/database-progress.spec.ts`／数据库分区配置与真实步骤投影 | 2 | before等待旧“主机”；`DatabaseConnectionDrawer.vue:51,78,80` 要求 JDBC URL／用户名。只改真实输入，追加test的POST/localUI和DTO检查；after证明旧probe失效及后续Task批次。`STALE_EXPECTATION`。 | FAIL／PASS，共2 |
| HF-DOC-1440、HF-DOC-390 | `frontend/e2e/document-template-tasks.spec.ts`／文档评审上传重试、刷新与按需报告 | 2 | before业务断言已完成后出现`/private/tmp`截图ENOENT；仅加TestInfo参数并改为outputPath。截图及全部业务断言仍执行。`FIXTURE_ENV`。 | FAIL／PASS，共2 |
| HF-AUTOMATIONS | `frontend/e2e/read-consistency.spec.ts`／自动化页面显示检测失败并在服务端恢复后清除旧告警 | 1 | before／after均旧health元素不存在；`router/index.ts:25`正式redirect后无消费者。复合根因为`STALE_EXPECTATION`（细分`STALE_ROUTE_EXPECTATION`）＋`COMPATIBILITY_CONSUMER_GAP`，不能以redirect绿掩盖当前历史消费可达性债务。 | FAIL／FAIL，共1 |
| HF-ROLE-spdb-1440/390、HF-ROLE-tech-blue-1440/390、HF-ROLE-github-white-1440/390 | `frontend/e2e/roles.spec.ts`／角色权限预估可读且布局不重叠 | 6 | before旧文案失配；依据`RoleManagementView.vue:552`改为精确完整现文案，保留CONFIG_ONLY且complete=false fixture、limitations、工具来源、布局及溢出。全部三皮肤／两宽度after通过。`STALE_EXPECTATION`。 | FAIL／PASS，共6 |

## 实际执行与最小纠正

1. 先对四文件原grep运行11项，129.9秒，固定 `historical-before.log`、`historical-before-results.json`、11项trace与`historical-before-source/`。运行前10份源码hash与首次计划完全一致，四份测试没有改动；before没有重试。命令为 `PLAYWRIGHT_CHROME_EXECUTABLE=/usr/bin/chromium PLAYWRIGHT_JSON_OUTPUT_NAME=/workspace/react-full-w0-evidence/historical-before-results.json ./node_modules/.bin/playwright test database-progress.spec.ts document-template-tasks.spec.ts read-consistency.spec.ts roles.spec.ts --grep '数据库分区配置|文档评审上传重试|自动化页面显示检测失败|角色权限预估' --workers=1 --output=/workspace/react-full-w0-evidence/historical-before-browser --reporter=list,json`，cwd为frontend，exit1。
2. 固定before后只纠正已证实的测试失配：数据库使用JDBC URL／用户名和实际DTO；角色采用完整精确现文案，没有放宽正则；文档只用`testInfo.outputPath`。没有生产或后端修改，没有skip／drop／降低业务断言。准确根因呈现为8条字段／文案`STALE_EXPECTATION`、2条`FIXTURE_ENV`、1条“路由预期过时＋当前历史消费者缺口”的复合项；JSON保留主分类与次级缺口，不以9条过时预期掩盖产品可达性债务。本轮没有把待建消费者错误升级为已证实的调度器缺陷，也不能声称真实后端没有缺陷。
3. 自动化原可见恢复case所有断言保留并在after继续真实FAIL，注释明确缺少消费者。新增独立case真实访问`/automations?projectId=project`并重载：两次进入`/template-tasks?projectId=project`、正式模板标题可达、没有任何历史自动化API读／写，没有pageerror。未挂载孤立View，未把新模板恢复API当health合同。
4. 全部四文件after运行46.5秒，固定 `historical-after.log`、`historical-after-results.json`、剩余失败trace与`historical-after-source/`。命令与before同四文件，不加grep，输出改为historical-after前缀，exit1；准确20项19PASS／1FAIL。所有原有19项仍被发现和执行，新增redirect1项单列通过。
5. 原`AutomationHealth.spec.ts`1项实际props从失败到CHECKED清错，原`client.spec.ts`的automation detection health1项验证缺失、失败字段和非法状态；聚焦Vitest共2PASS，2.28秒，exit0。使用名称filter，另76项未选中，不新增skip代码，也不将它们计为本轮通过。命令、完整JSON和log为`historical-health*`；这只证明现有组件／解析合同，不证明当前路由有归档UI、不证明生产Git/CRON轮询仍恢复。

分类只使用 `SOURCE_BUG / STALE_EXPECTATION / FIXTURE_ENV / UNREPRODUCED / ENV_BLOCKED / NOT_RUN`。本轮分类以当前实际首错和源码合同为依据；新增redirect与纯health通过不把HF-AUTOMATIONS的after FAIL改成PASS。现有公开GET／退役写／待建归档消费者仍按[兼容映射](../../automations-compatibility-map.md)结清。

## 当前桌面范围与保留的历史窄屏记录

JSON的每条原case及9条相关case已添加 currentScope，保留原before/after结果不重分类。视口来自已执行源码的 setViewportSize；未覆盖时按原Playwright Desktop Chrome默认1280×720，不推测成1440。混合桌面／窄屏测试保留全部桌面行为合同，本次只作证据范围标注。

| 记录／实际场景 | 已跑视口 | after | 当前范围 |
| --- | --- | --- | --- |
| HF-DB-1440 | 1440×1000 | PASS | IN_SCOPE_DESKTOP |
| HF-DB-390 | 390×1000 | PASS | OUT_OF_SCOPE，历史保留 |
| HF-DOC-1440 | 1440×1000 | PASS | IN_SCOPE_DESKTOP |
| HF-DOC-390 | 390×1000 | PASS | OUT_OF_SCOPE，历史保留 |
| HF-AUTOMATIONS | 默认1280×720 | FAIL | IN_SCOPE_DESKTOP，消费者缺口仍阻塞 |
| HF-ROLE-spdb-1440 | 1440×900 | PASS | IN_SCOPE_DESKTOP |
| HF-ROLE-spdb-390 | 390×900 | PASS | OUT_OF_SCOPE，历史保留 |
| HF-ROLE-tech-blue-1440 | 1440×900 | PASS | IN_SCOPE_DESKTOP |
| HF-ROLE-tech-blue-390 | 390×900 | PASS | OUT_OF_SCOPE，历史保留 |
| HF-ROLE-github-white-1440 | 1440×900 | PASS | IN_SCOPE_DESKTOP |
| HF-ROLE-github-white-390 | 390×900 | PASS | OUT_OF_SCOPE，历史保留 |
| RELATED-1 旧任务筛选迟到响应隔离 | 默认1280×720 | PASS | IN_SCOPE_DESKTOP |
| RELATED-2 新增退役深链／刷新／无历史API请求 | 默认1280×720 | PASS | IN_SCOPE_DESKTOP |
| RELATED-3 角色深链／模板／历史／差异／重载 | 默认1280×720 | PASS | IN_SCOPE_DESKTOP |
| RELATED-4 ZIP失败保留与409再校验发布 | 默认1280×720 | PASS | IN_SCOPE_DESKTOP |
| RELATED-5 角色离线重试／空结果／未选状态 | 默认1280×720 | PASS | IN_SCOPE_DESKTOP |
| RELATED-6 桌面双栏独立滚动／分页／折叠键盘 | 1440×900 | PASS | IN_SCOPE_DESKTOP |
| RELATED-7 spdb 真实ReactFlow／版本入口 | 1440×1000 | PASS | IN_SCOPE_DESKTOP |
| RELATED-8 tech-blue 真实ReactFlow／版本入口 | 1440×1000 | PASS | IN_SCOPE_DESKTOP |
| RELATED-9 github-white 真实ReactFlow／版本入口 | 1440×1000 | PASS | IN_SCOPE_DESKTOP |

实际分层为默认1280×720：6项5 PASS／1 FAIL；1440×900：4项全PASS；1440×1000：5项全PASS；历史390×900：3项全PASS；历史390×1000：2项全PASS。纯health两项无视口，仍是独立合同证明。没有新增窄屏设计／截图门槛。

## 必须保留的后续行为及React映射

| 入口 | 后续行为门槛 | 后续React归属 |
| --- | --- | --- |
| `/databases`、`/tasks/:id` | 实际类型／驱动profile，JDBC URL、账号、允许范围和test请求；test=1/save=0，改输入即清probe；Task12/14和85%、Session2/4与桌面布局。已跑窄屏无溢出仅历史OUT_OF_SCOPE。 | W2数据库页controller与W4任务权威进度／Session投影；不从fixture制造业务成功。 |
| `/template-tasks`、`/template-tasks/document-runs/:id` | File上传首次丢响应后明确重投同key共2次POST；冻结原文按需1次、总体报告按需1次、实际下载、刷新无额外create及无溢出。 | W3 legacy模块同一template写owner／Document分支接口，正式React上传与报告宿主保留全部断言。 |
| `/automations`、历史health | 正式redirect和无退役写；健康失败次数／原因、服务端新读投影清错的纯合同；真实可见消费者缺项保持阻塞。 | W0历史读／退役写映射；W3获批后正式只读归档消费者。当前TemplateTasks不能替代该合同。 |
| `/roles`、`/settings/roles` | CONFIG_ONLY且complete=false不冒运行授权；limitations、声明／服务端必需工具、桌面三皮肤、桌面无横溢出／不重叠；相关历史、ZIP重试、409版本和滚动键盘行为。已跑390px三皮肤仅历史OUT_OF_SCOPE。 | W2角色页与纯TS controller，角色版本／权限边界不变。 |

JSON逐项保留原title、历史证据、本轮before／after、分类、命令／exit、源码hash和未覆盖部分。上述通过均为模拟API的真实DOM／浏览器或jsdom证据，不证明真实数据库连接、Java持久化、模型执行或File解析；本轮没有全站E2E、构建或发布。HF-AUTOMATIONS可见健康恢复仍FAIL，后续获批消费者和行为迁移必须补齐才能关闭该合同及最终零失败门禁。

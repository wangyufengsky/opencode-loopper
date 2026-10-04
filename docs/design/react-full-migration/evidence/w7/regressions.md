# W7 首次失败与最小修复记录

基准 `1e9161e06c01b5aa76b6a2b19789ac8379423195`。以下是诊断阶段证据；全站最终通过只取最后一次统一冻结批次。原始 JSON、trace、失败截图与命令保留在 `/workspace/react-full-w7-evidence`，不将环境日志或私人记录加入仓库。

## 真实迁移回归

| 模块 | 首次失败及原合同 | 最小修复与保护 | 非作者证据 |
| --- | --- | --- | --- |
| Database | A 原两个连接 probe：第二行失败后返回第一行，结果/错误串行；root `db-skip-before` 新负控红 | 页面只缓存已存在、未编辑行的 id/version 结果/错误。原 ticket+alive 校验阻迟到写回；新版本不复用旧缓存；不隐式重测、不改保存协议 | B `root-fixes-independent` 精确2 PASS（与下项合计），其余32过滤不计通过；A原8数据库桌面合同随后通过 |
| App键盘 | root `db-skip-before` 焦点负控红：跳过导航 href# 改变 React scope，原 main 焦点丢失 | skip-link 防默认 hash 导航，仅聚焦当前 main；同 URL/history/owner/File/key/body 继续持有 | B同2 PASS；root `db-skip-after` 相邻34 PASS（作者） |
| Role桌面 | root `roles-third` 原独立滚动、固定分页/折叠详情红 | 仅 object.role 的两栏局部样式；工具/权限 details保留；选中上下文遵守hidden；无controller/API变化 | B逐diff只读复核；组长原10桌面candidate PASS，不冒B执行 |
| Task | A原终止任务仍显示系统继续执行 | 权威 FAILED＋等待决策投影恢复原“任务已终止，不会再创建新会话”；不以断SSE当停止 | B相邻17/17实跑；原baseline桌面通过 |
| Knowledge | B `knowledge-prepared-before` 1 RED：POST返回PREPARED后没有GET，accepted永远阻断 | 仅读原conversation，question/id/answers/version精确追上ANSWERED才收尾；PREPARED、错误、旧版本保持BLOCK；POST恒1 | C实际选中1 PASS，其他18过滤不计；其后C三个B模块spec42 PASS |
| Knowledge引用 | 原1440/1280 citation被sources左栏遮挡 | 左栏展开保留实际布局空间；右侧citation保持原overlay，聊天bbox/scroll原断言不减 | B18原Knowledge桌面candidate PASS；C页面diff与合同复核 |
| Catalog | 原相邻筛选导致表单移动386.125px、旧评分/源任务说明和历史过滤入口消失 | 表单局部align-self:start；原V1评分说明/源模板任务说明恢复；history恢复/tasks?type=template | B原三皮肤高度＜1px与消费者合同真实通过；C相邻Catalog页面独复 |
| PPT | BRIEFING资料入口、插入Escape关闭、重新打开后显式保存和dirty手动完成可用性不等价 | 恢复原入口；只关闭自有插入菜单；保持原显式save；propertyDirty时完成按钮禁用，原handler保护/CAS/autosave不重写 | B原PPT桌面及活动2类×3循环；C PPT页面独复 |
| Designer | 原completed＋TASK_START_REQUESTED任务交接、当前终端错误banner缺失 | 合法读取同taskId并安全handoff；当前权威错误持续可见、历史system折叠保留 | A controller17、page13及原附件浏览器1分别实跑；不混为一次31 |
| Workflow内置只读 | root原builtin键盘ArrowRight位置从143到167 | 仅移除页面movable强开覆盖，不改ReactFlow底层；zoom/pan仍可用 | C永久负控1 RED→page9绿；A同9独复；root原browser绿 |
| Requirement预览 | root原header返回缺失、另存模板关闭后父画布实例已卸载 | 真实PageLink恢复；主场景hidden/inert保持同父实例/草稿，子预览独立清理；export期间原owner仍锁定 | A需求/controller/StrictNode三spec31 PASS；root实际路由/预览5 PASS |
| NodeRun读取 | C真实StrictMode首GET pending，view重放后仍1GET/loading卡住 | 新读view恢复自己的loading再合法refresh；旧lease ticket仍拒迟到响应，无写入重放 | C永久1 RED→三spec31绿；A同31独复，before/after七源hash一致 |
| Requirement执行布局 | C真实RUNNING节点focus＋ArrowRight，dirty仍false；原保存布局动作缺失 | execution布局记原dirty draft；恢复中央workflow.saveLayout；复用原preparePlanSave，同graph自动跳过结构写。unknown冻结layout原key/body/revision/layoutVersion，accepted旧GET仍BLOCK，仅读追上 | CPage1 RED→关联三spec33 PASS；A同33独复（controller22/Page10/NodeStrict1），八相关源before/after相同；PENDING_START仅源码核对，不冒单独实跑 |

工作流主画布高度原三皮肤1600×1000实测732px低于原750px门槛：仅页面留白减少40px，原750px断言保持，底层画布/坐标/手势未改。

## 旧测试呈现与运输修正

- 旧Vue按钮/输入/标题/原生confirm改为实际React统一语义、选择后详情、Ant确认、真实select与cursor DTO。仍执行原动作、原API输入输出、File字节/顺序、请求身份、次数、CAS和dirty保护；不把新demo当生产页面。
- 首页原装饰图片预期映射到已批准本地semantic SVG与低密度入口；所有原深链入口仍可发现。皮肤弹层采用已冻结elevated token，精确核当前token，不用宽松颜色门槛。
- Role发布CAS校验后原“隐藏不可发布按钮”映射为可发现但disabled，仍断言原publish次数1、新版本3→4、checkbox清空与重新校验；没有删业务谓词。
- Undo尾段诊断曾把原禁用按钮映射为无可用动作，**先**精确核原layout回滚和一次历史。后续非组长工作流合同指出原动作可发现性缺口，最终恢复真实常驻disabled动作及原`toBeDisabled`谓词；不降低撤销深度或坐标阈值。
- Document by-request运输不能在第一POST网络丢失后提前认accepted再要求重POST：原未知核对404与显式同key/body第二POST后200正确模拟。accepted后仍只读，不改业务协议。
- 新增测试的TypeScript `state`推断与RTL不支持的exact选项由作者最小修正；PREPARED/ANSWERED运行DTO字节不变，name以精确正则匹配；typecheck错误单独保留，不算产品红。

## 严格资源账本与工具错误

原已退场ElementPlus Table不能作为RAF归属正控。测试改用显式独立React实例及真实AppRouter退出。只用实际callback闭包、connected实例自有Element、active状态、准确fixture URL及runtime SHA证明该注册的归属；回调名称或空allocation stack不能豁免。退休后复用同callback必须重新证明，未知RAF仍严格失败。没有删除他人监听、全局伪造事件、放宽计数或改依赖私有状态。

CDP证明的Playwright首次主world InjectedScript会注册工具监听：真实allocation stack留存，严格窗口前只做主world读warmup，不在账本中过滤这些监听。B独立3/3 PASS：未知帧、退休同callback、detached observer负控全部仍阻断。

初次受限Chrome EPERM、从错误cwd收集运输资源ENOENT、Node加载浏览器JSON import属性失败均列工具/权限/配置问题；获正常机制权限、从frontend运行及测试侧语义JSON读取后继续，不当产品bug或波动通过。所有主动中断诊断按INTERRUPTED/NOT_RUN记账，不算skip刷绿。最终批次重试0；如果冻结后出现一次失败，必须保留并重新核冻结，不能沿用诊断绿补洞。

## 最后整批候选发现（仍不代替最终冻结批次）

- 原执行节点拖拽 105px／20px 的第二候选发现屏幕 Y 多 36px：拖动结束新插入 dirty 段落使整幅画布下移。将 dirty 提示放入已有状态行，仍可见，保持原精确屏幕阈值，不改画布坐标或撤销层。
- 推送未取得权威读取时、尤其 UNKNOWN 中，不能显示“尚未推送”。原显式 GET null 的空状态仅在安全、已完成读取且没有活动/未知命令时呈现；UNKNOWN 原错误、身份和阻断仍可见。非作者 A 通过真实 DOM＋原 POST 失败路径发现该红，使用同探针复验。
- 空推送表单的打开必须登记普通 dirty，否则关闭可直接 ALLOW：恢复原关闭确认／明确“留下”后原表单及请求输入仍持有；尚未写入时 confirm POST 恒 0，UNKNOWN 不允许这类离开确认。writeback 原 dirty 使用同合同，不把关闭当服务端取消。
- 固定知识交接旧 `/inputs` 夹具缺 requirementId/nodeId，实际 owner 拒绝跨身份输入；仅补真实 version2、需求/节点身份与 planRevision，未降低身份校验。原 2 分块读取、私有信息不显示和结果按需读取均保留。
- 版本分析／复核旧 locator 要求未展开的引用正文先可见，无法找到 summary。先展开实际“源码依据”，再精确断言原路径、24–25行、`validate(input)`、private 禁止和正文读取恒 1。两文件全部四桌面项诊断4/4，不是增加四个新的业务测试。

## 第一冻结整批的补充失败（全部保留，不能沿用前轮绿）

第一冻结 SHA `a8821a377da1577b8b3a94de2c1f1f17e056cb66c4a1f91509c96b27c7efd8b8`：全量 unit 2373/2383、10 FAIL。六个首错为默认时限超时（W5首次模块导入＋Settings五项）；后续单worker仍与多浏览器并行时 Settings 另有六项超时。浏览器全部停止后的原三文件50/50、非作者A50/50通过，Settings/route代码和时限没有修改。这些是运行负载波动证据，不删首次红。

另外四个原 unit 首错：Role首个details定位错区；两Node case在元数据尚未读就绪时点击；PPT恢复 pending＋属性草稿不能查看的真实呈现回归。Role只改明确aria定位；Node仅补真实metadata/readable/loading与原生enabled前置、原retry忙态读到React提交后核验。旧dirty拒导航夹具曾跨用户确认保持async act，导致后续Ant内state污染；同一真实navigation.go在短act内发起、外部等待原Promise，并保Stay/path/draft断言，未修改共享harness或生产导航。

PPT只区分进入只读草稿视图与结束编辑：busy/pending/propertyDirty仍阻结束，archived/historical/active限制维持；readonly字段、保存、删除、自动保存、原pending key/body/revision、同owner/SSE和离开保护均保。原Recovery三名称/断言逐字节未变，实际2/1→3/3；正式Page补一个恢复负控。本组18与138重叠，不累计为156个唯一测试。

第一冻结浏览器：A12/12、B49/49、W6production97/97通过；C完整110/111，其中1项最后截图超过30秒；组长88登记57 PASS、7 timeout FAIL、1 INTERRUPTED、23 NOT_RUN（主动中断自己批次以修复已确定unit问题）。这些都不是最终通过。八项浏览器超时、24个中断/未执行、完整原始trace/JSON及源码均留在原环境。最后需要新的唯一源码冻结、干净安装、先unit后browser限制并发，再全部实际执行；不提高时限、不删除或降低断言。

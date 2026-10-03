# 0717a4a之后：最小设计门禁补充

**供项目经理再次审查，尚未放行生产开发。** 基线`0717a4a5c3f5bf6fb61af8208136d5353e05a941`，仍在`feat/react-full-migration`原工作区。只补现有设计交付与门槛，不重新设计，不添加原型功能，不修改生产实现／依赖，不安装候选框架，仅按最新用户授权发布设计资料，不启动生产开发。

## 1. 单文件审查入口

请打开[review-single.html](prototype/review-single.html)，从导航切换首页、任务列表、新建需求、任务详情、设置，从顶栏切换spdb、科技蓝、GitHub白。页面持续标记“设计原型／模拟数据”；已有筛选、弹层、草稿确认、模拟unknown／409与动效原样保留。

- 单HTML **47,773 bytes**，SHA-256 `9b519f8a3f6d34c1a323f975540323586ff6ee8cd6516c1dd623dfb1c37cd26b`。
- [打包器](prototype/bundle-single.mjs)将原CSS／JS逐字节内联，不压缩、不重写；JS在body DOM后执行，避免inline defer提前执行。CSP用该脚本的精确SHA-256授权，禁止connect／font／object等资源；无外链资源或CDN。打包不修改生产CSP。
- [原多文件原型](prototype/index.html)、原CSS／JS／capture／evidence和[29张PNG](prototype/README.md#桌面五类--三皮肤)全部保留，**34文件逐字节等于0717a4a**。没有拿新采样覆盖原证据或重新设计原型。
- 原型中的“建议”与“查询原回执”文案也原样保留：前者现已获项目经理采纳为默认策略，后者仅是无请求的模拟，不表示公开by-request GET存在。实际恢复按[endpoint合同](controller-interfaces.md#2-回执与恢复按-endpoint-能力区分)。

本Cloud没有可调用的父侧原生网页查看器。以下证明是本地真实Chromium和直接注入HTML的独立检查，**不是父侧已打开／视觉验收通过**。本文件已消除对相对CSS／JS读取的依赖；如果父查看器另有脚本沙箱限制，仍需项目经理实际反馈，不能绕过或推测成功。没有修改既有file协议访问策略。

## 2. 本轮实际验证

[严格检查脚本](prototype/verify-single.mjs)与[结构化证据](prototype/single-evidence.json)最终记录Chromium **151.0.7922.173：112通过／0失败，executionComplete=true**。

| 检查 | 准确结果／范围 |
| --- | --- |
| 原有65断言 | 全部保留并对单HTML重跑：45组合DOM／溢出检查＋20原型交互／隔离检查 |
| 新45组一致性 | 五页×三皮肤×1440／390／320px，main／导航／skin投影一致；原版与内联版截图SHA-256严格相等，零像素容差 |
| 新2项独立性 | 内联CSS／JS字节一致；HTML直接注入空页面，依靠原导航／主题控件切五页三皮肤，0资源请求 |
| 单文件正常浏览器导航 | 52次请求全部为单HTML文档；没有相对CSS／JS／图片／字体请求，0外部请求、0pageerror、0console error |
| 代表状态采样 | 29份本次真实截图buffer记录hash／bytes；仅保存在内存和结构化摘要，不覆盖或新增原29PNG |
| 可复现／源码完整性 | `bundle-single.mjs --check`通过，证据中7源码hash与磁盘一致；原34文件与0717a4a逐字节一致 |
| 说明／语法／diff | 项目说明检查、原型JS与新增MJS语法、文档链接／行号和`git diff --check`通过；具体命令见下 |

失败过程保留说明：首轮双页比对时后台标签截图30s超时，原验收脚本只用单标签；检查器改为显式激活被拍标签，并让致命异常记失败。随后106通过／6像素不同；等待已有有限动效结束后111通过／1像素不同。剩余两控件圆角区域实际读取RGB差分为29像素，未据此推断产品缺陷或免除检查。最终每页先要求连续两张截图hash完全稳定，再跨版本比对，45组严格相等；未改CSS／JS、屏蔽区域、引入像素容差或降低原断言。稳定采样解决本次检查差异，不能据此证明所有设备渲染相同。

```sh
node docs/design/react-full-migration/prototype/bundle-single.mjs --check
node docs/design/react-full-migration/prototype/verify-single.mjs --tools /workspace/opencode-loopper-react/frontend
node --check docs/design/react-full-migration/prototype/bundle-single.mjs
node --check docs/design/react-full-migration/prototype/verify-single.mjs
node scripts/check-project.mjs
git diff --check
```

复用第一阶段已安装的官方Playwright与现有Chromium；正常获准启动浏览器。只暴露原三文件和单HTML的回环预览，检查器结束关闭浏览器／server。没有产品API、File、存储、后台进程、模型调用或公开预览。

## 3. 文档修正与生产前门槛

1. [总报告路由表](README.md#3-基线规模和完整路由责任表)修正`/requirements/new`为名称／说明／项目／流程四字段，创建和原身份回执恢复。实际[New:19](../../../frontend/src/views/WorkflowRequirementNewView.vue#L19)不上传File、不start／调用模型；保留实际templateRevision和query预选。
2. [B1–B9红测／证据台账](w0-evidence-ledger.md)逐项列源码事实、待复现风险、红测场景和后续绿测门槛；**没有任何B项被本轮修复或运行验证**。先结清映射／行为门槛，才可进入W1–W6生产迁移。
3. [Automations兼容映射](automations-compatibility-map.md)区分孤立View／redirect、公开历史GET／导出和已退役写入口；TemplateTasks并非当前等价消费者。归档消费者缺项与旧数据／断言映射仍阻塞准入；不删除历史能力，不复活已退役写入。
4. [统一设计合同](controller-interfaces.md)落实项目经理已决定的默认策略：pending／unknown写入硬阻所有SPA出口直到原身份核对／恢复安全；普通dirty／未发送File确认才放弃；不自动重发／新key。无持久化／by-request能力如实限定，不能承诺跨刷新完整恢复；beforeunload只是浏览器提示。确定receipt仅限定目标handoff；guard取消／false与Promise reject都必须保receipt，恢复只nav/read。

原11历史失败、生产B项、全站304＋新增E2E、unit／typecheck／build／tooling测试体与干净npm ci本轮均未执行／修复；第一阶段204Chromium／1305unit是历史成果，剩余100E2E（含原11）仍未跑。真实后端／模型／File解析未验证。观察关系释放与GC对象仍存活的原采样边界不变。

## 4. 原团队复用与独立复核

组长仍使用项目经理指定的Sol极高设置；复用原三名任务，未spawn／增员／改变模型。三次followup真实成功，历史创建为`gpt-6.1-sol / xhigh`；当前平台只能读name／status，不能声称实时读取模型配置。

| 原任务 | 本轮归属／证据角色 |
| --- | --- |
| `/root/react_flow_workflow` | 唯一新增B1–B9台账；源码／原测试／endpoint恢复证据，未跑生产红测 |
| `/root/react_legacy_canvas` | 唯一新增Automations映射；route／UI／API／后端退役／持久化／测试分层 |
| `/root/react_ppt_canvas` | 非作者只读审单文件／打包与检查器／最终证据／统一合同／两份成员文档，不改作者文件 |
| 组长 | 打包现有原型、执行本地设计检查、统一本轮受影响文档、保护既有成果与集成 |

最终独立复核回执见[交叉审查记录](independent-review.md)。作者自测不算独立审查；本地设计门禁补齐不代表W0已结清或全面开发已获放行。

## 5. 最新授权的发布边界

用户已批准仅将设计文档、原型、29张设计截图及原型检查正常推送到`wangyufengsky/opencode-loopper`的`feat/react-full-migration`。先检查自`a3c692d`以来全部新增修改只有设计资料，非快进分歧停报；不force、不改main、不PR／merge／deploy，也不包含后续生产实现的推送。公开查看主入口是[三皮肤五类页面截图README](prototype/README.md)；GitHub显示Markdown／PNG，单HTML需下载本地打开，不是在线运行的生产页面。实际发布成功与远端SHA以工具结果及最终交付回复为准，不预先声明成功。

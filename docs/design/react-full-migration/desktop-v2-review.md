# 桌面原型非作者复核

结论：本地 HTML/mock 桌面候选的最终实际 Chromium 验证 **205 PASS / 0 FAIL**，生成 **28 张新图**。这不是生产 React/Ant、API、SSE、File 或 W0 业务修复验收。W0 已独立冻结；项目经理的新授权已放行 W1 基础工程，原型普通微调不阻塞 W1。本轮没有窄屏设计或适配验收。

审查者为原任务 `/root/react_ppt_canvas`，沿用原显式指定 `gpt-6.1-sol / xhigh` 的启动记录；当前工具不能实时读取模型配置字段。原型三文件由 workflow 组员编写，中心语义表由 legacy 组员编写，单文件由组长生成；本审查者只写验证脚本、证据、截图和本报告，没有改他们的源码、生产或 W0。

## 实际命令与证据

```sh
node docs/design/react-full-migration/prototype/desktop-v2/verify-desktop.mjs \
  --raw /workspace/react-full-desktop-v2-evidence/final-packaging
```

实际进程 exit 0，`executionComplete=true`；浏览器 Chromium `151.0.7922.173`，正式 Playwright 来自本工作区已装依赖，localhost:41783 为本审查者唯一静态服务。脚本结束关闭 context、浏览器及服务器，端口已归还。没有启动产品/Vite、安装依赖、修改网络策略或调用真实模型。

可提交证据：[验证脚本](prototype/desktop-v2/verify-desktop.mjs)、[完整 JSON 与逐图 SHA-256](prototype/desktop-v2/evidence.json)、[新图目录](prototype/desktop-v2/screenshots/)、[独立单文件](prototype/desktop-v2/review-single.html)。原始过程保存在 `/workspace/react-full-desktop-v2-evidence/`，不将环境日志提交仓库。

| 最终检查 | 实际数目 |
| --- | ---: |
| registry 实际 Lucide 源、公开 alias、完整字段、同名图标、JSON/runtime 深冻结对照 | 1 |
| 5 页 × 3 皮肤 × 2 桌面尺寸：默认主体、语义 DOM/icon、选择/重复/关闭回焦 | 90 |
| Enter/Space/Escape/显式取消、非模态 Tab、展开/收起、skip-link/focus、reduced-motion | 36 |
| Home 仅筛本地已读对象与 List 查询/状态组合 | 6 |
| New 四字段无上传、真实 dirty、SENDING/UNKNOWN 锁定、原创建身份与 accepted handoff | 15 |
| Settings 两组真实编辑、完整 owner 保存/放弃 | 6 |
| Task 等待/停止确认、accepted 仅读、无 key 回答 UNKNOWN 仅读、独立版本冲突 | 12 |
| Home/List/Settings 禁止制造其他域的未知写与 Task 版本 | 3 |
| 单文件精确源码/CSP、30 组精确 DOM/像素对照、document-only、15 组注入零请求 | 33 |
| 旧文件不变、运行期源码冻结、请求/错误账本 | 3 |
| **合计** | **205** |

尺寸为 **1440×960、1280×900**。三皮肤 `spdb / tech-blue / github-white` 使用同一页面脚本与中心 key。120 objects、173 actions、78 本地 Lucide 图形、31 路由元数据核对通过；元数据存在不代表五页之外已实现。已安装 `icons.json` SHA-256 为 `428633667745fc16b264451b5d9bab478e7a648720a7d40c01e756b21346fe97`。

完整账本共 **518 次 GET**，均为白名单本地 HTML/CSS/JS，无外部请求、page error 或 console error。单文件 30 次请求全部是 HTML document；注入模式五页×三皮肤 **0 requests / 0 errors**。没有删除请求、取消别人的资源或用等待把失败变绿。

## 非作者发现与关闭

以下源码问题在实际浏览器前反馈作者，由作者/组长修正；本审查者没有代改：

- New 高级区原来增加附件 input；现 [app.js:166](prototype/desktop-v2/app.js#L166) 只说明真实四字段，无上传。实际主表单和展开区均无 file input。
- Settings 原来只有最后编辑组的 dirty/baseline；现 [discardOwner:205](prototype/desktop-v2/app.js#L205)、[saveOwner:211](prototype/desktop-v2/app.js#L211) 对完整页面草稿处理。实际跨两组编辑、关闭、切皮肤、放弃、保存后再次编辑/放弃均通过。
- 不支持的页面情境原来套用 Task owner/version；现 [supportedScenarios:238](prototype/desktop-v2/app.js#L238) 明确限制。Home/List/Settings 的 unknown 请求不生成 operation/receipt，原生 disabled 选项与真实键盘跳过均验证。
- New recovery 原来可能套用 accepted-read；现 [recoverOperation:224](prototype/desktop-v2/app.js#L224) 保留原创建 body/key，用户显式 retry 后 accepted-nav，reads 恒为 0；handoff 不增加 mutation。Task 无幂等 key 的回答 UNKNOWN 只核对原 task/session/question，不能重复回答 POST。
- 原 dialog 默认焦点 header X、证据动作的名称/glyph 分叉；现 [openDialog:197](prototype/desktop-v2/app.js#L197) 默认具名 `ui.stay`，真实 native modal Tab/Shift+Tab、Escape 和触发器回焦通过。普通动作的 glyph 与语义 key 一致。
- 中心表原来把 navigation `task.openRecovery` 与 server `task.restoreArchive` 都叫“恢复任务”；现分别“打开任务恢复”和“恢复归档任务”。新建需求页/动作同名 glyph 统一，技能统一“技能（Skill）”；相同中文名的严格图标断言保留。

先后实际记录：`first.log` 为 **195 PASS / 4 FAIL**，包括 verifier 未解析公开 `history → rotate-ccw-clock` alias，以及 Playwright `option.isDisabled()` 跟随 label 到 enabled select 的探针错误。独立 `option-diagnostic.log` 保存三页的 `.disabled=true`、`:disabled=true`、feedback 可见、operation/receipt=null。后改为实际原生属性加真实键盘证明，没有忽略图标或 disabled 门槛。

`final.log` 为 **203 PASS / 2 FAIL**：一项是真实同名恢复动作冲突；另一项是 verifier 忽略 Settings 合法 dirty 选项，误要求一次 ArrowDown 到 error。最终按合法 dirty→error 序列保留所有未知写/owner 断言，原始失败输出不覆盖。`final-v2.log` 为该内容候选的 **205/0**；最终包装字节又以未改验证脚本完整执行，`final-packaging.log` 为 **205/0**。

## 图像与单文件边界

新图为15默认、5类各一张 selected，以及8张 dirty/unknown/accepted handoff/Settings dirty/Task waiting/conflict/readback/reduced-motion 状态图。所有28张采样前回到 `scrollX=scrollY=0`，等待有限 CSS 动效，连续两张截图 SHA-256 完全一致后写入，没有像素容差、裁剪改图或手改图中文字。30个多文件/单文件组合均精确 DOM 与稳定像素相等。

本审查者实际查看五类默认代表（spdb Home/Task、GitHub List/Settings、科技蓝 New）、五类 GitHub selected 和科技蓝 UNKNOWN 最终图：默认主体没有空详情栏；选中区按需出现；三皮肤的主体/分隔/操作层次可辨。UNKNOWN 图顶部工作区、模拟标记与持续安全状态已完整保留。自动定帧和 DOM 断言不等于人工审美批准或完整 WCAG 审计。

**非阻塞微调**：Home selected 重复两行“项目”，并在普通上下文放入 Projects/KnowledgeHistory 接口实现说明（[app.js:174](prototype/desktop-v2/app.js#L174)）。应在后续普通文案微调移到设计文档、保留用户可理解的对象信息。当前刻意保持冻结，未把此项冒称已修；不阻塞 W1。

最终单文件为 **201034 bytes**，SHA-256 `311779b44cba4ddcff9703533fde01007ab83ffa39d0cb84043c056b9a6862eb`；内联 CSS、registry 和 app 与作者源精确同字节，脚本在 DOM 后按 registry→app 顺序执行，CSP script hash 与精确字节一致，`connect-src/font-src/object-src/base-uri/form-action` 均关闭。没有外部字体或图标 CDN。

暂存质量检查发现原包装第199/200行各遗留两个缩进空格。组长仅将生成器移除外部script标签时的匹配改为含两空格缩进的完整标签，再生成HTML；独立比较证明HTML只删除上述4个空格，其他六份原型／验证源字节全同。原 **201038 bytes／`9362fd73f3b461fa5a01541f1083a1ca63836af22cdd4158ef9671dd7da1c9f6`** 候选证据保留在旧raw，未覆盖历史输出。最终使用原205项脚本再跑，30组精确DOM／稳定像素、30次单HTML请求及15组注入零请求门槛未变；**28张PNG与旧最终候选逐字节相同，变化0**，没有追加场景或重审装饰。W1的37份源码／证据／图片哈希也全同。当前[desktop evidence](prototype/desktop-v2/evidence.json) SHA-256为 `ca670e0b75d5cf037d674ad7071257c78469c03368b0f1b583efb219f02a18f8`；原运行日志保留，临时41783服务已随脚本正常关闭。

旧原型9份源码/证据与旧29张 PNG 合计38文件，运行前后哈希全同；本轮没有覆盖原29图。历史实际 Chromium/mock Knowledge 默认、引用和首阶段 Canvas selected 图仅作信息结构参考，不构成同夹具的新生产 before/after。已实查看的历史文件哈希：

| 历史图 | SHA-256 |
| --- | --- |
| 原 Cloud `frontend/test-results/knowledge-default-1440.png` | `5609d048e24c011c5fa8b199879e4bff1bc8542a908c3501d66b9efdf0ec8a1a` |
| 原 Cloud `frontend/test-results/knowledge-citation-1440.png` | `b2a320185e1819a70bb0890194c8b6155973056104d9d2195fee6cc6879de967` |
| `docs/deliveries/react-canvas-cross-review/screenshots/spdb-planning-selected.png` | `e4832ce10c61408b30e6d7967edfa65b9d50590bebee35fe321079b93e48b1d6` |

## 未覆盖与后续准入

这是本地 HTML/mock，不是 React/Ant 组件渲染，也没有真实后端写回执、权限、409、SSE、File 字节持久化或模型调用。snapshot 为只读观察；交互全部由实际 DOM 鼠标/键盘触发，模拟 mutation/read 计数不能代替网络业务证明。场景切换与完整 document reload 只是夹具开场/重置，不用作原业务离开保护证明；普通 dirty/unknown 检查使用真实导航控件。

没有承诺刷新恢复 File、New receipt GET、Settings CAS 或无 key 重发。没有证明生产 StrictMode/实例监听/RO/RAF/捕获/heap/GC 清理；这些由 W1 实际 React 基础组件及后续各波次单独验收。没有把原 W0 84 合同红或 Automations 当前消费者缺口计为本次通过。窄屏当前 OUT_OF_SCOPE；未删旧历史断言、未补新的窄屏测试。项目经理视觉审查与 W1 正式工程证据分别交付，W2+仍按后续授权推进。

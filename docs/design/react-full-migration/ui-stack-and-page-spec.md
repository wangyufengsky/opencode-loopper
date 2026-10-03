# 第二阶段 UI 栈与页面交互设计

状态：规划／设计候选，等待项目经理阶段审查；没有安装这些候选依赖或实现生产页面。已制作与生产分离的五类交互原型，见[产品翻新](product-redesign.md)和[真实浏览器原型截图](prototype/README.md)，均明确模拟内容。基线 `a3c692d38925206883f2b0a1255479108cfd439e`。正式依赖资料查询于 2026-10-03，版本是在当次查询中取得的候选锁定值，进入开发时再次核验元数据和 peer 兼容，不自动跟随 latest。

## 1. 只选一套基础 UI

**选择 Ant Design React 6.6.5。** 表单、表格、弹窗、抽屉、通知、分页和中文 locale 都由它提供；业务图标继续使用已有本地 Lucide 数据与 [ReactIcon](../../../frontend/src/react/ReactIcon.tsx#L1)。不再同时引入 MUI、Radix/shadcn、Ant Pro、Ant Mobile 或另一套表单／表格库。已验证 React Flow、CodeMirror、Mermaid、ECharts、DOMPurify 继续按各自专长使用。

这是本项目的设计判断：现有应用有任务列表、复杂表单、动态字段、详情对话框和大量管理操作，统一成熟组件比重新拼装每种底层交互更适合本阶段。Ant 是基础能力，产品外观由重新设计的壳、导航、层级、密度、可组合组件和tokens决定，不以默认库样式代替设计。组件库不能替我们实现请求身份、恢复、领域权限，也不能证明全站可访问性或资源释放。

| 候选 | 官方资料与当前事实 | 对本项目的成本与取舍 |
| --- | --- | --- |
| **Ant Design 6.6.5** | [官方介绍](https://ant.design/docs/react/introduce/)、[更新记录](https://ant.design/changelog/)、[MIT](https://raw.githubusercontent.com/ant-design/ant-design/master/LICENSE)；registry 查询发布时间 2026-09-20；peer React／ReactDOM >=18 | 选择。完整 Form/Table/Modal 能覆盖 Element Plus 使用面，中文生态适合现有文案。样式生成及 rc 依赖不是轻量无成本，需要测首屏、焦点和退出资源。 |
| MUI 9.4.0 | [官方概览](https://mui.com/material-ui/getting-started/)、[官方 registry 元数据](https://registry.npmjs.org/@mui%2fmaterial)；2026-08-27，MIT | 成熟备选，但引入另一种设计体系和样式引擎；本轮不混用。 |
| Radix Dialog 1.1.23／自行组合 UI | [官方介绍](https://www.radix-ui.com/primitives/docs/overview/introduction)、[registry](https://registry.npmjs.org/@radix-ui%2freact-dialog)；2026-07-24，MIT | 原语有价值，但表格、完整表单和视觉系统仍要另行组合；在 187 个 SFC 的迁移范围中增加组合与验收工作。本轮不选。 |

维护情况以本次官方发布与资料为依据，不承诺第三方未来支持年限。保留第三方版权和许可声明；候选均未发现需付费才能使用本次基础功能的条件，不使用商业专属表格或组件。

## 2. 路由、状态、图标与工具链决策

| 能力 | 设计选择 | 兼容性与限制 |
| --- | --- | --- |
| React | 保留现有锁定 React／ReactDOM 19.3.0 | 不把本轮迁移绑到 React 主版本升级；现有画布行为继续复用。 |
| 最终路由 | **React Router 7.18.4，Data 模式**；一个 `createBrowserRouter`／`RouterProvider` | [官方 npm 7.18.4 元数据](https://registry.npmjs.org/react-router/7.18.4) 为 MIT、Node >=20、React >=18，2026-09-15 发布。兼容 [正式 Node 22.14.0／npm 10.9.2](../../../pom.xml#L32)。同日发布的当前 8.4.0 要求 Node >=22.22.0；不在本轮顺带升级正式工具链。实施前按 7.18.4 固定源码确认 API，不将 latest 文档的新 API 生搬到旧版本。 |
| 状态 | **Zustand 5.0.15 vanilla 工厂＋纯 TS controller**，React 用 selector 消费 | [官方 README](https://github.com/pmndrs/zustand/blob/main/README.md)、[registry](https://registry.npmjs.org/zustand/5.0.15)、[MIT](https://raw.githubusercontent.com/pmndrs/zustand/main/LICENSE)。vanilla 有 getState/setState/subscribe，可以先替换状态容器而保持同一命令 owner；它本身不提供幂等、恢复或 SSE 管理。 |
| 图标 | 复用本地 `@iconify-json/lucide` 与 ReactIcon；迁移 Vue Icon 使用点 | 不依赖 CDN，不新装 lucide-react 全集，也不在业务页面同时采用第二图标集合。Ant 内部默认图标是其实现依赖，不增加业务图标体系。 |
| 表单 | Ant Form 管理可见字段与局部验证；业务 controller 管理持久草稿、revision、pending | 不另引入 React Hook Form／Zod。现有 DTO/parser/领域验证继续是契约；校验通过也不代表服务端接受。 |
| 数据读取 | 原 api/client、规范化 DTO、按 scope 的读取 controller | 不引入自动 mutation 重试、自动 refetch 重新提交或第二缓存权威。若未来需要数据缓存，另作有证据的维护任务。 |
| 构建／测试 | 保留 Vite、Vitest、Playwright、TypeScript；最后只留下 React 插件及普通 TS 配置 | 最终 `vue-tsc` 改普通 `tsc -b`，去 `@vue/tsconfig` 和 `.vue` include／声明；不是本轮执行的修改。 |

React 组件测试继续复用已锁定 `@testing-library/react 16.3.3` 与现有 jsdom/Vitest；不为本轮原型另装测试框架。交互检查用既有正式 Playwright 与本地 Chromium。独立原型不是 Ant 的包体、焦点或生产 React 集成证明。

官方路由[安装说明](https://reactrouter.com/start/data/installation)要求 router 在 React 树外创建；[useBlocker](https://reactrouter.com/api/hooks/useBlocker)只管 SPA 导航，不替代浏览器刷新／关闭守卫。最终用 Data router 的公开阻断接口；不引入实验 HistoryRouter，也不让 loader/action 发业务 mutation。纯读初始化仍由 scope controller 去重管理。

## 3. 包体与资源预算

官方 registry 的 `dist.unpackedSize`：Ant 6.6.5 为 **48,957,741 bytes**，Zustand 为 **95,173 bytes**；这是整个安装包的解压体积，包含多个入口／类型等，**不是生产 JS、gzip 或网络传输体积**。本轮没有安装、打包候选，不能给 Ant 在本项目中的最终增量或性能改善结论。

已有最终入口约 1,664.23 kB／gzip 454.99 kB，来源于[前阶段构建记录](../../deliveries/react-canvas-all-cleanup/resize-observer-patch.md)，只能作为比较基线。开发后记录总 JS／CSS、入口、UI chunk、route chunk、图标数据、Mermaid／CodeMirror 重资源的 raw/gzip 大小及首次路由加载；按路由懒加载，避免全局 eager import 全表格／日期／图示资源。Ant 提供 ES modules tree shaking，但不能据此预报实际体积。[官方模块化说明](https://ant.design/docs/react/introduce/)

W1 设立五类代表页的正式实现实测比较表；本轮设计原型不是候选库打包结果。最终同时记录移除 Element Plus/Vue 后的净变化。原有 >500 kB 警告保留，不通过提高警告阈值消除证据。首次交互和详情长日志输入不得因 UI 渲染产生阻塞；具体数值预算由 W1 同机实测确定，后续波次超过该已记录预算就阻断，并说明必要的功能增量。

## 4. 三皮肤只认一份 tokens

纯配置 [types](../../../frontend/src/themes/types.ts#L2)、[compile](../../../frontend/src/themes/compile.ts#L1)、registry 与三个配置继续复用；[state.ts](../../../frontend/src/themes/state.ts#L1) 的 Vue 响应容器改为外部状态，但保留保存键 `loopper.skin`、跨标签同步、存储拒绝兜底和首屏注入。

| 皮肤 | 当前配置事实 | 列表、编辑表单、详情的设计呈现 |
| --- | --- | --- |
| spdb | [配置](../../../frontend/src/themes/spdb.ts#L5)：浅色；primary `#001e8c`；surface `#f3f6fc`；正文 `#1c2745` | 白色工作表面，深蓝单一主要动作，边框区分分组；红色保留危险／错误含义，避免把每个卡片当强调色。 |
| tech-blue | [配置](../../../frontend/src/themes/techBlue.ts#L7)：深色；canvas `#070b14`；surface `#0d1424`；正文 `#e6edf8` | 暗色低装饰表面、蓝色主要动作；仅焦点与关键状态保留光感。表格行、弹窗和编辑区不堆叠大面积辉光。 |
| github-white | [配置](../../../frontend/src/themes/githubWhite.ts#L5)：浅色；primary `#1f883d`；surface `#f6f8fa`；正文 `#1f2328` | 中性灰分隔、绿色主要动作、蓝色链接；密度与另外两皮肤完全一致，功能和层级不随皮肤变化。 |

以下是统一适配规范，不是新的颜色散落到页面：

| 现有语义 | Ant theme／组件适配 |
| --- | --- |
| colors.primary / link / text / secondary | colorPrimary / colorLink / colorText / colorTextSecondary |
| canvas / surface / elevated / hover | 明确覆盖 colorBgLayout / colorBgContainer / colorBgElevated 与各组件 hover token；不假设默认算法自动等于原 palette |
| border / borderMuted / success / warning / danger | colorBorder / colorBorderSecondary / colorSuccess / colorWarning / colorError |
| fonts.ui / radii / shadows | fontFamily、有限解析的数值 radius、Card/Modal/Button 组件 token；禁止任意 CSS 值转 number |
| primaryButton 的三种状态与前景 | 通过唯一 UiButton 的语义样式适配已有变量；保留科技蓝半透明背景，不能简单只改 colorPrimary 丢失原状态 |

ConfigProvider 始终有 theme 对象和稳定树位置；配对的 Ant App 保留 DOM 容器，通知／modal 用 `App.useApp()`，避免静态调用丢主题上下文。[官方 theme](https://ant.design/docs/react/customize-theme/)、[App 与 CSS 变量](https://ant.design/components/app/)

弹层主题继承与局部作用域在 W1 必须实测，不假设 `document.body` 上的 portal 自动获得任意祖先 CSS。页面已有全局 `html[data-skin]` tokens 可复用，Ant 的 portal container、provider 与 popup key 在一个主题适配层统一。切换皮肤不重挂载表单、画布或命令 owner；不清空 File、选中态、草稿、CodeMirror 光标。CodeMirror/Mermaid 的原安全渲染与主题代次保护继续使用。

普通文本目标对比度 >=4.5:1；图形／焦点边界 >=3:1；只有实际测试通过才写已达标。暗色 muted 不自动用于重要说明或表单错误。状态同时有中文和图形，不只靠颜色。运动遵守 prefers-reduced-motion，不下载外部字体。

## 5. 公共壳与控件契约

桌面导航宽 224px、页面内容 padding 24px、标题 24px、正文 14px；普通列表最大内容宽 1440px，编辑表单最大 1040px；画布页面继续占用完整可用宽度，不套普通内容 max-width。960px 以下折叠导航，640px 以下单列，390px／320px 是最小专项尺寸。这里是初始设计参数，开发前 W1 以五类代表页真浏览器检查；不要把数字写进主题配置或复制到各页。

壳保留跳到主内容、工作／创作与资料／管理与设置三组导航、当前激活项目、知识库最后位置和深链。移动导航使用命名dialog、背景inert、焦点闭环；Escape返回触发按钮。页面转换后聚焦标题／主内容，用户在弹窗关闭时回到触发控件。刷新／后退／同路由id变化都经过对应资源scope与守卫。

| 统一控件边界 | 必须保持的行为 |
| --- | --- |
| UiButton / CommandButton | type=button 默认，明确 submit，中文名称，pending 禁止重复；禁用说明不依赖仅 hover tooltip；键盘可达。成功仅在权威回执后提示。 |
| ConfirmDialog / DomainDialog | 标题、描述、确定／取消、focus trap 与回焦点；pending/unknown/File 所有者定义关闭政策。危险确认默认聚焦取消。 |
| DataTable | rowKey=稳定业务身份，受控筛选／分页／选择；表格行更新不丢选择，跨页批量范围明确；窄屏卡片保留全部动作，或仅表格容器横向滚动。 |
| FormSection / Field | 显式 label，required 和 error 有关联；第一错误聚焦与摘要；未知字段、DTO limits 不因 UI 控件默认值被清空；动态列表有稳定 key。 |
| StatusBadge / ErrorPanel | 复用 displayLabels 和权威 waitingReason/status 投影；历史审计和当前告警分开，不把全部错误历史展示成当前阻断。 |
| Code／Markdown／Chart | 保持已验证 CodeMirror、DOMPurify、Mermaid 与 ECharts 所有权；封装 React 生命周期，不创建另一份领域状态。 |

Ant Modal 的公开参数包括 `focusable.trap`、`focusable.focusTriggerAfterClose`、`keyboard`、`mask.closable`、`destroyOnHidden`；使用这些接口同时实测 Tab／Shift-Tab／Escape／嵌套弹层，不依赖默认值或“组件库已经无障碍”的宣称。[官方 Modal](https://ant.design/components/modal/)

`destroyOnHidden` 对可恢复的业务表单不默认打开。Dialog/Form 分工：Form 的值只投影 owner draft；打开读取基线，编辑更新 draft；确定调用一个命令，409 保留输入并展示差异；unknown 保留原 key 和 File。关闭按钮、遮罩、Escape、路由、beforeunload 共用 owner 的政策，不能用 UI 返回 false 和页面守卫两套判断。

## 6. 代表列表页规范：任务／项目列表

以 [TasksView](../../../frontend/src/views/TasksView.vue)、[ProjectsView](../../../frontend/src/views/ProjectsView.vue) 为基准，完整动作见 core 清单。

```text
导航 | 标题：任务                 [主要动作]
     | 搜索  项目  状态   更多筛选
     | 名称／摘要 | 当前状态 | 项目 | 更新时间 | 操作
     | …稳定身份的结果行，空／加载／错误独立…
     | 已选范围说明                         分页
```

默认只展示高频筛选和一项主要动作；次要设置放明确命名菜单，不能把权限／恢复阻断埋进菜单。列表空态提供合适创建入口，读取失败保留筛选条件并能重试；“暂无结果”和错误不同。Task 的 PENDING_START/READY 等状态来自服务端，行内动作与详情统一 controller 决定，不能推断开始／继续许可。

390px 采用卡片行：名称、状态、项目和时间可换行，主动作与“更多”保持可达；排序／筛选用带焦点管理的面板，分页顺序和深链状态不丢。项目路径正文可折行，无 nowrap。三皮肤差异由表面与强调色提供，结构相同。

## 7. 代表编辑表单规范：新建需求／复杂参数

代表表单是 [RequirementNew](../../../frontend/src/views/WorkflowRequirementNewView.vue#L46)，与设置页分别设计：名称／目标先，项目／流程后，版本约定按需披露，创建结果在固定区域，不新增此入口原本没有的 File 或模型执行字段。四字段、query、实际版本和原回执恢复见[before→after](product-redesign.md#2-五类页面-before--after)。[SettingsView](../../../frontend/src/views/SettingsView.vue) 与任务参数／LoopSpec 编辑沿用同一 Field/FormSection 规范，仍保留各自复杂字段。

```text
标题：设置                  [保存] [恢复当前基线]
分组导航 | 基本配置（高频字段）
         | 模型／会话（状态与来源明确）
         | 高级参数（默认折叠，可检索）
         | 校验摘要／保存状态／版本冲突
```

桌面两列字段按控件底部对齐，label 允许换行；窄屏单列。分组导航在手机变水平 tabs 或顺序标题，不需要安装第二套移动 UI。保存按钮不因离开高级折叠分组而丢字段；server snapshot 与 dirty draft 单独保留。读取迟到不覆盖编辑；有revision/expectedVersion的接口按既有CAS处理。AppSettings只有原settings PUT，没有CAS version，本轮不新增后端版本协议；保存失败保留草稿，冲突设计示例应使用实际有版本的页面。numeric 输入只在提交时规范化，不在中间空值／负号时无声复位；保留原 min/max 与领域 Bean Validation 合同。凭据字段保留原掩码与权限政策，不写截图或文档里的真实值。

全局 Enter 不自动提交带 CodeMirror／JSON／多行文本的复杂表单；普通输入的提交快捷键在可见提示下实现。危险重置先说明作用范围与可恢复性。长表单粘性操作栏不能覆盖输入／错误，键盘 focus scroll 保持可见。

## 8. 代表任务详情规范

以 [TaskDetailView](../../../frontend/src/views/TaskDetailView.vue) 为唯一业务基准，不用换框架重新解释状态。

```text
标题／项目／权威状态                  允许的当前动作
当前阻断与恢复（只有当前告警）
阶段 React 图（可定位与缩放，完整宽度）
实施 Todo 独立行（无待回答问题时）
运行输出／待回答问题       | 摘要／制品／质量信息
历史审计、版本、会话细节（按需展开）
```

桌面主要内容约 2/3，辅助栏约 1/3；窄屏辅助栏进入正常文档流。Todo 和问题遵守现有规则：无问题时 Todo 占输出上方独立行，有问题时问题入口优先；不固定覆盖输出，不用 Todo 完成数冒充阶段百分比。阶段图仍复用现有 React Flow／自有 viewport owner，回到长流程末节点保持 Tab 定位。

开始、继续、暂停、停止、重试、回答、验收、发布／恢复的显示由同一领域 action availability 决定；菜单不能隐藏当前必须回答的问题。停止状态要有服务端证明，未知反馈继续阻断。日志“跟随最新”与用户手动滚动分开，读取增量不抢输入焦点；问题回答保留草稿与原身份。详情 id 切换先阻断危险离开，再关旧 task stream/timers，迟到回执只能回到原 scope。

三皮肤：spdb 使用深蓝阶段焦点与中性审计；tech-blue 使用低装饰暗色日志与可读错误；github-white 使用中性边框和绿色动作。各皮肤的文字、命令范围、键盘顺序和窄屏可达性完全一致。

## 9. 设计验收与项目经理门禁

本轮已提供首页、列表、表单、任务详情和设置的[独立本地原型与截图](prototype/README.md)，有三皮肤、窄屏和模拟 unknown/409/reduced-motion 状态。原型由 HTML/CSS/JS 构成，未接生产React/Ant或业务API；不是本轮迁移实现。不能把既有画布图片或模拟反馈冒称新页面业务已经完成。W1 开发后每皮肤必须提供这五类正式 React 页的1440px／390px真浏览器状态，并增加320px、弹窗键盘、错误、pending和资源退出验证。

项目经理本轮审查：主要 UI 栈、五类翻新信息结构／入口、组件层次、动效与原型证据，以及波次和单一owner设计。功能等价与新呈现各自过门槛，明确发现的原合同歧义先有处理记录。普通 token／组件API适配由开发组完成；大规模开发在该规划设计报告通过之后开始。

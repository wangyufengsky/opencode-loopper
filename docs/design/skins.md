# 皮肤配置与扩展

产品行为由[设计合同](../design-contract.md#可配置皮肤)持有；本页说明实现接口与新增配置方法。改造前清单、顺序和验收目标见[实施计划](skins-plan.md)。

## 使用

主页右上角“皮肤”选择科技蓝或 GitHub 白，立即全站生效。默认科技蓝；选择保存在当前浏览器的同源 `localStorage`，键为 `loopper.skin`。刷新、深层链接、新开同源标签恢复选择；已打开同源标签同步切换。清空存储或已删除的主题回到科技蓝。存储被禁止时当前页面仍可正常切换，下次访问使用默认值。

## 配置结构

配置入口在 `frontend/src/themes/`，没有外部网络依赖，也不写入业务数据库。

| 文件 | 职责 |
| --- | --- |
| `types.ts` | `SkinDefinition`：稳定的皮肤配置接口 |
| `techBlue.ts`、`githubWhite.ts` | 两套独立皮肤；标签、明暗模式、颜色、字体、圆角、阴影、按钮和图片呈现 |
| `registry.ts` | 皮肤注册表、默认值、保存键和未知值兜底 |
| `shades.ts` | 历史细微色阶的语义映射；只有科技蓝需要保留原色 |
| `appearance.ts` | 可覆盖的渐变／阴影声明目录及科技蓝原始外观 |
| `renderers.ts` | CodeMirror 与 Mermaid 的语义颜色及历史色阶 |
| `compile.ts` | 同一配置编译 CSS 和首屏初始化代码，供 Vite 开发与正式构建共用 |
| `state.ts` | 当前皮肤、应用选择和标签页同步 |
| `mermaid.ts` | 串行执行全局 Mermaid 初始化与渲染，保证主题快照一致 |

`colors` 提供画布、表面、浮层、悬停、边框、正文、次级文本、链接、主要操作和状态色。基础色要求六位十六进制字符串；编译器生成对应 CSS 变量和需要透明度的 RGB 通道。`primaryButton` 定义普通／悬停／按下三种状态的前景、背景和边框。状态成功、待处理、危险的含义固定，不随主题改变。

`fonts`、`radii`、`shadows` 配置字体、常用圆角和阴影。`appearance` 按目录中的名称覆盖特定背景／渐变／阴影；未指定的声明采用共享语义配色。`homeArtwork` 指定 `frontend/src/assets/` 中的 3:2 主页图片文件名，由 Vite 打包为带哈希的本地资源，换肤时同步切换；科技蓝保留原轨道图，GitHub 白使用独立浅色协作插画。`artworkDisplay` 控制图片是否展示，`decorationOpacity` 控制指标光晕。布局尺寸、滚动、响应式断点、业务进度与结构性圆形／胶囊形状保持在组件内。

科技蓝使用 `shades` 保留历史色阶，组件仍通过统一变量访问。新皮肤一般不需要 `shades`；全部历史色阶默认映射到 `colors` 的语义色，可按需覆盖个别色阶或渲染器色阶。新增页面应优先使用 `--color-*`、`--radius-*`、`--shadow-*`、`--font-*`，不继续增加历史色阶。局部状态变量（如 `--metric-accent`）在组件内组合语义颜色，不能放入根级外观声明，否则 CSS 会在根节点提前解析局部变量。

## 添加第三套皮肤

1. 在 `frontend/src/themes/` 新建配置文件，导出符合 `SkinDefinition` 的对象。可复用一套基础配置，再提供自己的 `id`、中文 `label`、完整配色和视觉参数。
2. 在 `registry.ts` 的 `skins` 数组注册。ID 使用稳定的小写字母／数字／连字符，不能与已有 ID 重复。选择器自动显示该配置，页面不增加主题 ID 判断。
3. 如果需要不同渐变或阴影，在 `appearance` 覆盖已有名称；拼错的名称会让构建失败。一般无需复制科技蓝的历史色阶。
4. 运行主题配置／编辑器／Markdown 聚焦测试，再用 `frontend/e2e/fixtures/skins.html` 检查控件、弹层、文档、代码和流程图，并验证真实页面。
5. 按仓库交付流程重新构建 JAR。首期配置随应用构建生效，不提供任意外部 CSS 上传或管理员在线编辑入口。

## 首屏与运行时

Vite 在 HTML 头部注入同源内置主题 CSS 和短同步初始化代码，在应用挂载前恢复选择，避免浅色用户先看到深色页面。主题标记位于 `html[data-skin]`，因此传送到 `body` 的 Element Plus 弹层同样继承主题。运行时只改变主题标记和浏览器 `theme-color`，不重挂载应用、路由或表单。

CodeMirror 通过配置隔间更新明暗模式，样式引用配置变量，保留文档和光标。Mermaid 采用配置快照串行渲染；已有图保留源文本，主题变化后重新绘制，过期结果不覆盖新图。HTML／SVG 仍经过 DOMPurify，安全配置与错误清理不变。

## 验证

- `src/themes/skins.spec.ts`：配置扩展、保存／恢复／兜底、跨标签同步、浅色对比度和源码颜色约束。
- `src/components/CodeMergeEditor.spec.ts`：切换明暗模式时编辑器实例、内容和光标保持。
- `src/components/MarkdownDocument.spec.ts`：图形重绘、多个实例串行、过期结果以及原有安全与折叠行为。
- `e2e/skins.spec.ts`：主页切换、刷新、同源标签、页面导航、三种宽度、控件／弹层／代码／真实 Mermaid。
- `e2e/home.spec.ts`、`e2e/app-shell.spec.ts`：保留科技蓝基线、导航和键盘行为。

新增配色参考 GitHub 官方 [Primer Primitives](https://primer.style/product/primitives/)，继续使用项目已有 Vue／Element Plus，不引入第二套交互组件库。

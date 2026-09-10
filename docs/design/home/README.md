# 主页设计

采用内置 imagegen 生成视觉稿与最终轨道环插图。视觉稿是本次主页的布局参考，现有 dark-first tokens 与产品合同继续约束实现。稿中自动添加的搜索框与无对应功能的装饰文字不进入产品。

- 视觉稿：`concept.png`
- 实际页面插图：[home-orbit.png](../../../frontend/src/assets/home-orbit.png)
- 配色：沿用深蓝背景、青色/蓝色主色与少量紫色。
- 参考：[Linear Team Home](https://linear.app/docs/default-team-pages) 的常用入口组织；[Vercel 导航更新](https://vercel.com/changelog/dashboard-navigation-redesign-rollout) 的开发流程优先导航。
- 主页文字与跳转均由 Vue 渲染，图片仅用于装饰。图片不依赖远程服务。

## 视觉稿提示词（内置 imagegen）

Use case: ui-mockup. Asset type: high fidelity desktop home screen design reference for OpenCode Loopper, a local AI software development console. Design a refined production-ready 1440px desktop UI. Existing design tokens must be preserved: canvas #070B14, sidebar #0D1424, cards #121C30, borders #21304B, primary text #E6EDF8, secondary #9AA8BD, blue #3B82F6, cyan #22D3EE, restrained violet #8B5CF6. Existing 248px sidebar with orbit logo and Chinese navigation 主页 / 项目 / 设计与执行规范 / 历史设计 / 任务 / 待处理中心 / 质量与用量 / 模板与自动化 and system 运行环境 / 工具 / 设置. Main header 主页. Hero: elegant large Chinese headline 从一个想法，到可验证的交付。 small subtitle 将需求、设计、执行与验收，连接在同一个工作区。 two real-looking buttons 开始设计 and 查看任务. Right half of hero has exquisite 3D brushed metallic interlocking orbital loops cyan-blue rim lighting, tiny restrained violet details, architectural technical feel, dark background, not gaudy neon. Below hero a section 工作区入口 with 4 navigation cards 项目 / 设计与执行规范 / 任务 / 待处理中心 with outline Lucide style icons, brief one-line descriptions and arrows. Bottom compact links 历史设计 / 质量与用量 / 模板与自动化 plus 运行环境 / 工具 / 设置. Balanced whitespace, 10px card corners, crisp fine borders, desktop developer console inspired by clarity of Linear and Vercel but original. No fake statistics, no progress numbers, no online success indicator. Render full UI directly, no monitor/device frame, no watermark.

## 实际插图提示词（内置 imagegen，以视觉稿为参考）

Use case: style-transfer. Asset type: final raster hero illustration for the OpenCode Loopper home page. Use the previous generated UI mockup only as a visual reference for its right-hand metallic orbital sculpture. Generate ONLY the isolated artwork, no UI whatsoever. Three sophisticated interlocking brushed titanium orbital loops, cyan-blue edge illumination and tiny subtle violet details, a couple small metallic satellite spheres on thin elliptical paths, sparse fine technical grid. Composition: wide 1536x1024 landscape; sculpture centered with comfortable padding on all sides; occupies 70 percent width, on uniform near-black navy #070B14 backdrop that fades seamlessly to solid #070B14 at all four outer edges. Polished physically believable 3D product render, refined engineering technology, understated visual, crisp metal surfaces. All background grid lines fade before the image edges. No letters, no numbers, no labels, no watermark, no UI, no buttons, no cards, no border. Preserve the design reference's understated deep navy/cyan metallic style.

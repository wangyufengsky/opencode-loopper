# spdb风设计记录

皮肤名称：`spdb风`，配置 ID：`spdb`。默认采用本皮肤；历史有效的手动选择保留，无选择、非法值或存储不可用时回到 spdb。

## 依据与视觉

参考[浦发银行官网](https://www.spdb.com.cn/)在 2026-09-22 的蓝、红、白组合。官网 [site_sy.css](https://www.spdb.com.cn/images/site_sy.css) 包含 `#001e8c`、`#e6001e`，[global_v1.0.1.css](https://www.spdb.com.cn/images/global_v1.0.1.css) 包含 `#000073`、`#004790`。这是官网风格参考，不作为正式品牌色标准声明。Loopper 用深蓝主操作、红色点缀、白底和浅蓝灰面板；正文、次级文本与语义状态色为可读性调整，成功／警告／错误仍保持语义。

图片结合浦发银行外滩 12 号建筑意象、上海天际线与 Loopper 的设计规范、任务分支、双评审、交付证据和迭代回环。建筑关联参考[浦发银行官方外滩 12 号介绍](https://news.spdb.com.cn/about_spd/xwdt_1632/202309/t20230912_1118244.shtml)。图片为风格化原创插画，不是建筑实景照片。

最终素材：`frontend/src/assets/home-spdb.png`，1536 × 1024 PNG，使用内置 imagegen，以既有 GitHub 白图片作为画风参考生成；随应用离线打包。

## 完整生成提示词

```text
Use case: stylized-concept
Asset type: OpenCode Loopper homepage illustration for a new "spdb风" skin inspired by Shanghai Pudong Development Bank.
Primary request: Create an ORIGINAL bank-related developer-workspace hero illustration, visibly associated with SPD Bank and Shanghai, while keeping Loopper's design-to-verified-delivery workflow.
Style reference: supplied image is a reference for refined isometric white ceramic/paper forms, calm white backdrop, clean edges and soft shadows. Redesign the composition and subject; do not simply recolor it.
Scene/subject: an elegant miniature of SPD Bank's historic Bund No.12 building, recognizable neoclassical stone facade with a large central dome and tall columns, as the central anchor. A discreet small navy plaque on the facade reads exactly "SPD BANK". Behind it a very light, sparse Shanghai Pudong skyline silhouette with the Oriental Pearl tower and Shanghai Tower establishes the city. Around the building, a single flowing dark-blue and red ribbon path forms a development loop connecting three restrained small floating cards: a specification blueprint/checklist card; a code and branch card; two matching compact shield-check tiles side by side with a small document stack below, representing independent double review and verified evidence.
Composition: landscape 1536x1024 (3:2), seamless pure white #ffffff background. LEFTMOST 30% is empty pure white for page copy. ALL main forms fit inside x=35% to 89%, y=20% to 80%, generous safe margins. Main building dominates; workflow cards are secondary and sparse, not dense. Visually legible at 600x400 pixels.
Palette: SPD Bank-inspired deep navy #001e8c and #000073, refined blue #004790, small crimson #e6001e accents, white stone and pale blue-gray; green only as tiny verification check details, not dominant. Avoid gold.
Mood: trustworthy, measured, contemporary financial technology; polished matte miniature, gentle daylight, no neon.
Text: only "SPD BANK" on the small facade plaque. No other labels, numbers, slogans or watermark. No full user interface screenshot, no bank-login fields, no credit-card details, no money piles, no people or robots. No full logo recreation required.
```

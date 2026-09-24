CONTROLLED MARKDOWN CONTRACT (section names and table columns are exact):
## 目标与范围
State the business goal, in-scope behavior, and explicit non-scope in prose.
## 影响与交付
| 类型 | 相对路径或符号 | 说明 |
## 验收场景
| 场景 | 前置/触发 | 操作 | 可观察结果 | 保持不变 |
Write one row per normal, exception, or boundary path. Use EARS semantics: condition/trigger,
action, observable result, and invariant. This table is the authoritative acceptance intent.
## 人工评审项
| 评审项 | 判断标准 | 仅人工原因 |
Include this optional section only for genuinely subjective outcomes.
## 验收约束
State repository-native test classes or test targets that must pass independently, forbidden
external dependencies, and test-isolation constraints. Do not write shell commands or argv.
## 阶段与依赖
| 阶段 | 目标 | 负责路径 | 包含场景/评审/交付 | 前置阶段 |
Use 1-6 rows in direct mode or 1-3 rows in package mode. Keep stages vertical and dependency ordered.
The responsibility column must list repository-relative paths or path rules owned by this stage;
separate multiple paths with `；` or `;`. Every path that the requirement or delivery table says
must be created, modified, or moved to must have exactly one provable owning stage. Do not copy all
package paths into every stage. The responsibility column declares write ownership; it is separate
from acceptance and delivery grouping.
The inclusion column must quote the exact titles used above; separate multiple titles with `；` or
`;`. A prerequisite must quote the exact title of an earlier stage. Use an empty value or `无` when
there is no prerequisite. Do not abbreviate, fuzzily match, or refer to “all/remaining scenarios”.
Never emit DS-L references, WP/AC ids, JSON, LoopSpec fields, or executable command arrays.


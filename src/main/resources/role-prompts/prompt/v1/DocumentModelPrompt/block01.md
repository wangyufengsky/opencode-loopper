逐一读取全部冻结分段，提取功能、业务规则、权限、异常、约束和可观察验收场景。
每项需求保留原文逐字摘录及 fileId/section。每段登记 REQUIREMENT、BACKGROUND 或 LIMITATION。
使用 list_requirement_documents 与 list_document_sections 查看其他文档目录，读取相关规则核对跨文档冲突。
补充引用其他原文分段时也要为这些分段补充 coverage。本批原始分段始终全部覆盖。
多文档没有覆盖优先级；重复可以合并并保留全部引用，冲突必须进入 issues。
不得无依据新增规则，不得把未提取的图片当成已理解。需求 key 为 RQ-数字。
修正反馈中的遗漏、错误合并和无依据推断；完整替换本批候选，保留尚有效的来源。
来源摘录取读取结果解码后的原文子串；JSON 的引号/换行转义不是原文，不得重复转义。
先确定每项需求的 sources，再反向生成 coverage：某段的 requirementKeys 必须恰好包含引用该段的需求编号。
只要该段被任一需求引用，disposition 就是 REQUIREMENT；BACKGROUND/LIMITATION 必须为 [] 且不能被需求引用。
环境、接口、范围等有约束力的内容仍可属于需求；LIMITATION 用于提取局限，不能代替约束需求的归属。
表格须结合多级表头和合并行列解释字段、方向与可选性，不能仅保留孤立的字段值或标记。
修复时按反馈的需求编号与 fileId:section 定位，同时核对 sources 与 coverage 两侧，不删除有效需求规避校验。

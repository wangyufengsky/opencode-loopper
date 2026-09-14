# AZX0 模拟需求样本

`AZX0接口附言优化_模拟需规.docx` 是根据两张用户提供的需规截图制作的简化测试文档，共 3 页，包含目录、分级标题、术语表、合并表头、接口表、4 条功能需求和 8 个验收用例。原始截图不纳入仓库。

本样本沿用附言传递场景；字段拼写、可选标记定义、空值和空白字符处理、本地环境与验收用例属于明确标注的模拟约定，不是对原需规的完整还原。流程以文字表达，不含流程图或图片。后续可上传此 DOCX 测试需求开发或需求代码评审。

## 本次检查

2026-09-14，直接编译并调用当前源码 `AssistDocumentParser`，使用已有 0.4.23 完整 JAR 中的依赖，在临时目录独立执行，不启动服务或模型。

- 解析器版本：`ASSIST_DOCUMENT_V1`。
- 提取 15 个分段；探针输出共 2775 字符（含探针添加的分段标签）。
- 检查了主要章节、字段名、服务 ID、R1 至 R4、T1 至 T8 的文字存在性，均通过。这仅是提取检查，不是模型语义完整性验收。
- 实际复现合并表头结构丢失：第一行提取为 4 列，后续行是 5 列，不能判为表格结构解析通过。
- 使用文档渲染器检查全部 3 页，中文、表格和分页正常。渲染进程通过临时 fontconfig 配置读取系统中文字体，未安装或修改系统字体。
- 没有执行真实 Provider 分析、开发任务、代码评审或文中业务测试用例。

合并表头当前输出示例：

```text
| 名称 | 标识 | 出现要求 | 备注 |
| --- | --- | --- | --- |
|  |  | UP | AC |  |
| 付款方附言 | payerComments | O |  | 可选字符串 |
```

本次临时探针、原始输出及排版检查位于 `/private/tmp/azx0-doc-qa/`；该临时目录不作为长期测试依赖。后续测试应以本目录 DOCX 的 R1 至 R4 和 T1 至 T8 为基准，并单独验证合并表头关系。

## 表格优化与 Luna 效果测试

后续 `ASSIST_DOCUMENT_V2` 将合并表格保留为带逻辑坐标与 rowspan/colspan 的惰性 HTML 表示。样本原文件保持不变；`DocxTableRepresentationTest` 直接读取本文件，检查名称跨两行、出现要求跨两列，以及 UP/AC/O 的准确列位置。

真实模型测试入口为 `scripts/qualify-document-luna.py`，Java 桥接器为 `scripts/qualification/DocumentRequirementLunaProbe.java`。先运行后端聚焦编译，将生产 classes 与依赖固定到独立目录，再用 `javac -cp <冻结classpath> -d <探针classes> scripts/qualification/DocumentRequirementLunaProbe.java` 编译探针。

```bash
python3 scripts/test-qualify-document-luna.py
python3 scripts/qualify-document-luna.py --document docs/samples/azx0-requirement/AZX0接口附言优化_模拟需规.docx --output <新的证据目录> --classpath <探针及冻结生产classpath> --java <Java21绝对路径> --codex <Codex绝对路径>
```

测试固定 Luna medium、已有 ChatGPT 订阅、只读工具、独立提取和复核 Session；每 Session 最多 600 秒、4 次候选提交，工具调用设置 200 次准入上限。它使用生产解析器、角色提示、候选 Schema 与需求引用/覆盖验证器，通过受控 MCP 按段读取原文。样本的 15 个分段在这个效果探针中合并为一个作用域，未模拟生产分批、数据库、HTTP 生命周期及 OpenCode Provider；这些证据不得混称产品端到端验收。

语义验收以文内 R1–R4、T1–T8 为人工清单，另核对 UP/AC/O 表头关系、模拟约定、未无依据新增规则及未把目录当作业务需求。MCP ACCEPTED 仅表示候选结构与引用通过；独立复核和人工语义判断另列。真实运行结果及失败记录见对应版本交付记录。

## 原文直读静态评审样例

`review-fixture/` 是三个合成 Java 文件，仅用于静态阅读。已知缺陷：`payerComments` 为空字符串时仍写入 `RsrvFld1`；正确处理应保留纯空格并排除缺失、null 和空字符串。序列化、实际 AZX0 发送／响应边界与测试源码不在该样例中，因此不能判断传输字符一致性或声称测试已通过。不要把这些文件用于真实交易。

真实效果入口为 `scripts/qualify-document-direct-luna.py`，使用生产解析、提示、候选 Schema、原文／代码引用校验器与隔离读取适配器；不是生产数据库／HTTP 编排替代物。0.4.27 的一次修正后试跑仍未通过四次候选提交校验，详见[交付记录](../../deliveries/0.4.27.md)，不能作为 Luna 已完成两角色评审的证明。

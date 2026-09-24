import { nativeToolLabel, pptToolLabel } from './displayLabels'

const descriptions: Record<string, string> = {
  "gitlab_project_context": "读取项目 GitLab 仓库信息",
  "gitlab_list_issues": "列出项目议题",
  "gitlab_list_merge_requests": "列出项目合并请求",
  "gitlab_list_pipelines": "列出项目流水线",
  "gitlab_read_issue": "读取议题详情与讨论",
  "gitlab_read_merge_request": "读取合并请求详情与讨论",
  "gitlab_read_merge_request_diff": "读取合并请求代码差异",
  "*": "全部工具",
  "external_directory": "访问项目目录之外的文件",
  "question": "向用户提问",
  "todowrite": "更新工作计划",
  "todoread": "读取工作计划",
  "patch": "修改文件补丁",
  "webfetch": "读取网页",
  "skill": "使用技能",
  "loopper_knowledge_research": "知识库作用域",
  "describe_submission_contract": "查看候选提交合同",
  "submit_candidate": "提交历史协议候选",
  "submit_source_detailed_design": "提交源码详细设计",
  "submit_source_design_review": "提交源码设计复核",
  "submit_decomposition_plan": "提交工作包拆分计划",
  "submit_acceptance_choice": "提交验收方案选择",
  "submit_package_design": "提交工作包设计",
  "submit_package_design_v2": "提交工作包设计与验收场景",
  "submit_rolling_package_plan": "提交后续工作包计划",
  "submit_reviewer_report": "提交评审报告",
  "submit_project_convention": "提交项目开发规范",
  "submit_judge_decision": "提交验收判断",
  "submit_document_code_assessment": "提交文档代码评估",
  "submit_document_code_review": "提交文档代码复核",
  "submit_document_requirements": "提交文档需求",
  "submit_document_requirement_review": "提交文档需求复核",
  "submit_requirement_code_assessment": "提交需求代码评估",
  "submit_requirement_assessment_review": "提交需求评估复核",
  "submit_template_analysis": "提交模板分析结果",
  "get_source_design_work": "读取源码设计任务",
  "list_source_template_files": "列出来源模板文件",
  "read_source_template_file": "读取来源模板文件",
  "list_source_design_results": "列出源码设计成果",
  "read_source_design_result": "读取源码设计成果",
  "get_source_development_work": "读取源码开发任务",
  "list_source_development_files": "列出源码开发文件",
  "read_source_development_file": "读取源码开发文件",
  "get_document_review_work": "读取文档评审任务",
  "check_document_review_candidate": "检查文档评审候选",
  "get_development_task_guide": "读取开发任务指南",
  "list_requirement_documents": "列出需求文档",
  "list_document_sections": "列出文档章节",
  "read_document_section": "读取文档章节",
  "list_requirement_code": "列出需求相关代码",
  "read_requirement_code": "读取需求相关代码",
  "search_requirement_code": "检索需求相关代码",
  "list_requirement_assessments": "列出需求评估",
  "read_requirement_assessment": "读取需求评估",
  "list_development_requirements": "列出开发需求",
  "read_development_requirement": "读取开发需求",
  "read_development_source": "读取开发来源",
  "list_development_documents": "列出开发文档",
  "list_development_sections": "列出开发文档章节",
  "read_document_resource": "读取文档资源",
  "list_snapshot_review_results": "列出源码快照评审结果",
  "get_snapshot_review_work": "读取源码快照评审任务",
  "get_snapshot_review_groups": "读取源码评审分组",
  "list_snapshot_review_code": "列出源码快照文件",
  "read_snapshot_review_code": "读取源码快照代码",
  "search_snapshot_review_code": "检索源码快照代码",
  "read_snapshot_review_result": "读取源码快照评审结果",
  "list_database_connections": "列出当前项目授权的只读数据库连接",
  "inspect_database_schema": "浏览允许的表、字段、索引和外键",
  "query_database_readonly": "单条只读 SELECT／WITH",
  "inspect_document": "读取文档目录及 SHA",
  "read_document": "按 section（从 0 开始）读取文档",
  "generate_word": "仅为冻结阶段明确要求的 DOCX 生成 Word",
  "get_execution_context": "读取当前阶段权威合同、附件与证据目录",
  "get_failure_evidence": "读取本阶段最近完成尝试或指定 attemptId 的验证事实",
  "read_task_evidence": "读取本任务 evidence:、verification: 或 call: 前缀的证据ID",
  "list_knowledge_sources": "列出当前知识会话冻结授权的代码、文档和数据库",
  "browse_knowledge_source": "分页浏览资料目录",
  "search_knowledge": "有界关键词检索",
  "read_knowledge_source": "读取代码行片段或文档 section",
  "inspect_knowledge_git": "只读查看项目 Git 仓库及分支",
  "list_knowledge_git_authors": "分页查询项目范围的作者姓名和邮箱，同名时请向用户澄清",
  "search_knowledge_git_commits": "查询作者、日期和路径的提交。since/until 必须含时区且结束不包含",
  "read_knowledge_git_commit": "读取已查询完整 commit SHA 的项目内差异",
  "read_knowledge_git_file": "读取 commit SHA 中项目内文件",
  "blame_knowledge_git_lines": "查询指定提交文件行的最后修改者，不等同于问题引入者",
  "gitlab_list_pipeline_jobs": "按 pipelineId 分页读取 Job 状态",
  "gitlab_read_job_log": "按 jobId 保存有界日志前缀快照",
  "list_test_failures": "分页列出已保存报告中的失败用例",
  "read_test_failure": "按 failureId 读取失败断言、异常栈和来源",
  "search_evidence": "在当前授权快照中按字面关键词搜索，范围不完整时明确返回",
  "search_project_knowledge": "统一检索项目资料"
}

export function stableToolName(name: string): string {
  return name.replace(/^role-preview-internal_assist_/, '@loopper-assist/')
    .replace(/^role-preview-internal_/, '@loopper-internal/')
}

export function roleToolDescription(name: string): string {
  const stable = stableToolName(name)
  const tool = stable.slice(stable.lastIndexOf('/') + 1)
  if (tool === '*') return stable.startsWith('@loopper-assist/') ? '全部辅助 MCP 工具' : descriptions['*']!
  if (tool.startsWith('ppt_')) return pptToolLabel(tool)
  return descriptions[tool] ?? nativeToolLabel(tool) ?? '外部工具（未提供中文说明）'
}

export function workflowForSlot(slot: string): { name: string; position: string } {
  if (slot.startsWith('PPT')) return { name: 'PPT 制作', position: '需求沟通 → 方案设计 → 制作与导出' }
  if (slot.startsWith('KNOWLEDGE')) return { name: '知识库问答', position: '问题分析 → 资料检索与读取 → 来源核对与回答' }
  if (slot.startsWith('SOURCE_')) return { name: '源码详细设计', position: slot.includes('REVIEW') ? '详细设计之后 · 独立复核' : '来源分析之后 · 编写详细设计' }
  if (slot.startsWith('DOCUMENT_') || slot.startsWith('REQUIREMENT_CODE') || slot.startsWith('REQUIREMENT_ASSESSMENT')) return { name: '文档需求与代码评估', position: slot.includes('REVIEW') ? '生成结果之后 · 独立复核' : '文档读取之后 · 需求整理或代码评估' }
  if (slot.startsWith('SNAPSHOT')) return { name: '源码快照评审', position: '固定源码版本之后 · 分组审查与结果提交' }
  if (slot.startsWith('TEMPLATE')) return { name: '模板任务', position: '模板输入之后 · 分析并形成执行候选' }
  if (slot.startsWith('ROUTER')) return { name: '任务设计', position: '流程入口 · 判断任务类型' }
  if (slot.startsWith('DECOMPOSER')) return { name: '工作包规划', position: '需求确认之后 · 拆分工作包与依赖' }
  if (slot.startsWith('ROLLING')) return { name: '滚动工作包', position: '前序工作完成之后 · 规划下一批工作' }
  if (slot.startsWith('PACKAGE')) return { name: '工作包设计', position: '工作包拆分之后 · 细化阶段与验收场景' }
  if (slot.startsWith('ACCEPTANCE') || slot.startsWith('COMPILER')) return { name: '设计编译', position: '方案形成之后 · 验收消歧与执行准备' }
  if (slot.startsWith('JUDGE')) return { name: '任务执行与验收', position: slot.includes('RISK') ? '实现与验证之后 · 风险独立评审' : slot.includes('REQUIREMENT') ? '实现与验证之后 · 需求独立评审' : '实现与验证之后 · 验收判断' }
  if (slot.startsWith('REVIEWER')) return { name: '分析评审', position: '分析报告形成之后 · 评审并提出修正建议' }
  if (slot.startsWith('PROJECT_CONVENTION')) return { name: '项目规范', position: '项目分析之后 · 整理开发约定' }
  if (slot === 'IMPLEMENTATION') return { name: '任务执行与验收', position: '阶段开始之后 · 实现与测试，随后进入验收' }
  if (slot.includes('COMMIT_MESSAGE')) return { name: '任务交付', position: '形成 Git 差异之后 · 拟写提交说明' }
  if (slot.includes('MERGE_ADVISOR')) return { name: '本地同步', position: '发现文件冲突之后 · 提供合并建议' }
  if (slot.includes('REQUIREMENT')) return { name: '任务设计', position: '流程前期 · 澄清需求与确认目标' }
  if (slot.includes('DESIGNER')) return { name: '任务设计', position: '需求明确之后 · 讨论阶段方案与验收目标' }
  if (slot.startsWith('MACHINE_FINALIZER')) return { name: '规划与设计', position: '角色输出之后 · 整理已有结果' }
  if (slot === 'ACCOUNTING_COMMAND') return { name: '故事统计', position: '设计或开发关键节点 · 记录统计回执' }
  return { name: '项目辅助', position: '流程辅助阶段 · 只读分析与建议' }
}

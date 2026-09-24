import { workflowForSlot } from './rolePresentation'
export interface WorkflowStep { label: string; current: boolean }
export function roleWorkflow(slot: string): { name: string; steps: WorkflowStep[] } {
  const name = workflowForSlot(slot).name
  const flow = (labels: string[], active: number[]) => ({ name, steps: labels.map((label, i) => ({ label, current: active.includes(i) })) })
  if (slot.startsWith('PPT')) return flow(['需求沟通', '方案设计', '制作与导出'], [0, 1, 2])
  if (slot.startsWith('KNOWLEDGE')) return flow(['用户提问', '检索与阅读', '来源核对与回答'], [1, 2])
  if (slot.startsWith('SOURCE_')) return flow(['来源分析', '编写详细设计', '独立复核'], [slot.includes('REVIEW') ? 2 : 1])
  if (slot.startsWith('DOCUMENT_') || slot.startsWith('REQUIREMENT_CODE') || slot.startsWith('REQUIREMENT_ASSESSMENT')) return flow(['文档读取', '需求整理与代码评估', '独立复核'], [slot.includes('REVIEW') ? 2 : 1])
  if (slot.startsWith('SNAPSHOT')) return flow(['固定源码版本', '分组审查', '汇总结果'], [1, 2])
  if (slot.startsWith('TEMPLATE')) return flow(['提供模板输入', '分析与生成候选', '确认执行'], [1])
  if (slot.startsWith('ROUTER')) return flow(['输入需求', '判断任务类型', '进入设计流程'], [1])
  if (slot.startsWith('DECOMPOSER')) return flow(['确认需求', '拆分工作包与依赖', '工作包设计'], [1])
  if (slot.startsWith('ROLLING')) return flow(['前序工作完成', '规划后续工作包', '确认下一批'], [1])
  if (slot.startsWith('PACKAGE')) return flow(['拆分工作包', '细化阶段与验收', '确认设计'], [1])
  if (slot.startsWith('ACCEPTANCE') || slot.startsWith('COMPILER')) return flow(['形成设计方案', '验收消歧与编译', '执行准备'], [1])
  if (slot.startsWith('JUDGE')) return flow(['实现与验证', slot.includes('RISK') ? '风险独立评审' : slot.includes('REQUIREMENT') ? '需求独立评审' : '验收判断', '汇总与后续处理'], [1])
  if (slot.startsWith('REVIEWER')) return flow(['形成分析报告', '评审与修正建议', '确认或修订'], [1])
  if (slot.startsWith('PROJECT_CONVENTION')) return flow(['项目分析', '整理开发约定', '预览与应用'], [1])
  if (slot === 'IMPLEMENTATION') return flow(['阶段开始', '实现与测试', '独立验收'], [1])
  if (slot.includes('COMMIT_MESSAGE')) return flow(['形成 Git 差异', '拟写提交说明', '预览与确认'], [1])
  if (slot.includes('MERGE_ADVISOR')) return flow(['发现文件冲突', '提供合并建议', '人工处理'], [1])
  if (slot.includes('REQUIREMENT')) return flow(['提出目标', '澄清与确认需求', '方案设计'], [1])
  if (slot.includes('DESIGNER')) return flow(['明确需求', '讨论方案与验收', '确认设计'], [1])
  if (slot.startsWith('MACHINE_FINALIZER')) return flow(['角色输出', '整理已有结果', '继续检查与确认'], [1])
  if (slot === 'ACCOUNTING_COMMAND') return flow(['设计或开发关键节点', '记录故事统计', '返回操作回执'], [1])
  return flow(['提供项目材料', '只读分析与建议', '返回结果'], [1])
}

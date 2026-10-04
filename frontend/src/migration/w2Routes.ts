import type { ComponentType } from 'react'
import type { W2PageProps } from '@/pages/w2/shared/types'

/** Production React pages. The W6 application router is the only history owner. */
export const w2PageLoaders: Record<string, () => Promise<ComponentType<W2PageProps>>> = {
  '/': () => import('@/pages/w2/secondary').then(m => m.HomePage),
  '/projects': () => import('@/pages/w2/core').then(m => m.ProjectsPage),
  '/ppt': () => import('@/pages/w2/secondary').then(m => m.PptListPage),
  '/knowledge/history': () => import('@/pages/w2/secondary').then(m => m.KnowledgeHistoryPage),
  '/requirements': () => import('@/pages/w2/workflow').then(m => m.RequirementListPage),
  '/workflows': () => import('@/pages/w2/workflow').then(m => m.WorkflowLibraryPage),
  '/designs': () => import('@/pages/w2/workflow').then(m => m.DesignerHistoryPage),
  '/tasks': () => import('@/pages/w2/core').then(m => m.TasksPage),
  '/insights': () => import('@/pages/w2/secondary').then(m => m.InsightsPage),
  '/runtime': () => import('@/pages/w2/secondary').then(m => m.RuntimePage),
  '/tools': () => import('@/pages/w2/secondary').then(m => m.ToolsPage),
  '/databases': () => import('@/pages/w2/secondary').then(m => m.DatabasePage),
  '/settings': () => import('@/pages/w2/core').then(m => m.SettingsPage),
  '/roles': () => import('@/pages/w2/roles').then(m => m.RoleManagementPage),
}

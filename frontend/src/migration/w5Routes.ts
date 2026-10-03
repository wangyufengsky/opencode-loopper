import type { ComponentType } from 'react'
import type { W2PageProps } from '@/pages/w2/shared/types'

/** The remaining existing creative routes; Vue Router still owns history and designerEntry. */
export const w5RouteRecords = [
  { pattern: '/requirements/new', matches: (path: string) => path === '/requirements/new', load: () => import('@/pages/w5/requirements').then(module => module.NewRequirementPage) },
  { pattern: '/requirements/:id', matches: (path: string) => path !== '/requirements/new' && /^\/requirements\/[^/]+$/.test(path), load: () => import('@/pages/w5/requirements').then(module => module.RequirementPage) },
  { pattern: '/workflows/new', matches: (path: string) => path === '/workflows/new', load: () => import('@/pages/w5/workflow').then(module => module.WorkflowEditorPage) },
  { pattern: '/workflows/:id', matches: (path: string) => path !== '/workflows/new' && /^\/workflows\/[^/]+$/.test(path), load: () => import('@/pages/w5/workflow').then(module => module.WorkflowEditorPage) },
  { pattern: '/designer', matches: (path: string) => path === '/designer', load: () => import('@/pages/w5/designer').then(module => module.DesignerPage) },
] as const

export function w5PageLoader(path: string): (() => Promise<ComponentType<W2PageProps>>) | undefined {
  return w5RouteRecords.find(record => record.matches(path))?.load
}

import type { ComponentType } from 'react'
import type { W2PageProps } from '@/pages/w2/shared/types'

/** Production React pages. The W6 application router is the only history owner. */
export const w3RouteRecords = [
  { pattern: '/template-tasks', matches: (path: string) => path === '/template-tasks', load: () => import('@/pages/w3/templates/catalog').then(module => module.TemplateTasksPage) },
  { pattern: '/template-tasks/document-runs/:id', matches: (path: string) => /^\/template-tasks\/document-runs\/[^/]+$/.test(path), load: () => import('@/pages/w3/templates/runs').then(module => module.DocumentTemplatePage) },
  { pattern: '/template-tasks/source-runs/:id', matches: (path: string) => /^\/template-tasks\/source-runs\/[^/]+$/.test(path), load: () => import('@/pages/w3/templates/runs').then(module => module.SourceTemplatePage) },
  { pattern: '/knowledge/:conversationId?', matches: (path: string) => path === '/knowledge' || /^\/knowledge\/(?!history$)[^/]+$/.test(path), load: () => import('@/pages/w3/knowledge').then(module => module.KnowledgePage) },
  { pattern: '/ppt/:id', matches: (path: string) => /^\/ppt\/[^/]+$/.test(path), load: () => import('@/pages/w3/ppt').then(module => module.PptStudioPage) },
] as const

export function w3PageLoader(path: string): (() => Promise<ComponentType<W2PageProps>>) | undefined {
  return w3RouteRecords.find(record => record.matches(path))?.load
}

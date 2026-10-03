import type { ComponentType } from 'react'
import type { W2PageProps } from '@/pages/w2/shared/types'

/** Only existing W4 page routes. Vue Router still owns history and guards. */
export const w4RouteRecords = [
  { pattern: '/inbox', matches: (path: string) => path === '/inbox', load: () => import('@/pages/w4/inbox').then(module => module.InboxPage) },
  { pattern: '/tasks/:id/design', matches: (path: string) => /^\/tasks\/[^/]+\/design$/.test(path), load: () => import('@/pages/w4/history').then(module => module.TaskDesignHistoryPage) },
  { pattern: '/tasks/:id/recovery', matches: (path: string) => /^\/tasks\/[^/]+\/recovery$/.test(path), load: () => import('@/pages/w4/recovery').then(module => module.RecoveryStudioPage) },
  { pattern: '/tasks/:id', matches: (path: string) => /^\/tasks\/[^/]+$/.test(path), load: () => import('@/pages/w4/task').then(module => module.TaskDetailPage) },
] as const

export function w4PageLoader(path: string): (() => Promise<ComponentType<W2PageProps>>) | undefined {
  return w4RouteRecords.find(record => record.matches(path))?.load
}

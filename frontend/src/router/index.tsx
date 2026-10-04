import { createBrowserRouter, createMemoryRouter, redirect, type RouteObject } from 'react-router'
import type { ComponentType, ReactNode } from 'react'
import type { W2PageProps } from '@/pages/w2/shared/types'
import { ApplicationLayout, RouteScreen } from '@/app/App'
import { ApplicationOwnership, routeFromLocation, targetPath } from '@/app/ownership'
import type { StoryAccountingOwner } from '@/app/storyAccounting'
import { designerEntry } from './designerEntry'
export const applicationRoutes = ['/', '/projects', '/ppt', '/ppt/:id', '/knowledge/history', '/knowledge/:conversationId?', '/designer', '/requirements', '/requirements/new', '/requirements/:id', '/workflows', '/workflows/new', '/workflows/:id', '/designs', '/tasks', '/inbox', '/insights', '/automations', '/template-tasks', '/template-tasks/document-runs/:id', '/template-tasks/source-runs/:id', '/tasks/:id', '/tasks/:id/recovery', '/tasks/:id/design', '/runtime', '/tools', '/databases', '/settings', '/settings/roles', '/roles', '*'] as const
export interface ApplicationRouteOverride { path: string; Component?: ComponentType<W2PageProps>; element?: ReactNode }
export function routeObjects(application: ApplicationOwnership, options: { routes?: ApplicationRouteOverride[]; shell?: boolean; story?: StoryAccountingOwner; requestHash?: (url: URL) => string } = {}): RouteObject[] {
  const children = (options.routes ?? applicationRoutes.map(path => ({ path }))).map((record): RouteObject => ({ path: record.path,
    loader: record.path === '/automations' || record.path === '/settings/roles' || record.path === '*' ? ({ request }) => { const url = new URL(request.url), path = record.path === '/automations' ? '/template-tasks' : record.path === '/settings/roles' ? '/roles' : '/'; return redirect(path + (record.path === '*' ? '' : url.search + (options.requestHash?.(url) ?? url.hash))) }
      : record.path === '/designer' && !('Component' in record && record.Component) ? ({ request }) => { const url = new URL(request.url), result = designerEntry(routeFromLocation({ pathname: url.pathname, search: url.search, hash: url.hash })); return result === true ? null : redirect(targetPath(result)) } : undefined,
    element: <RouteScreen application={application} Component={'element' in record && record.element !== undefined ? () => record.element : 'Component' in record ? record.Component : undefined} />,
  }))
  return [{ element: <ApplicationLayout application={application} shell={options.shell} story={options.story} />, children }]
}
export function createApplicationRouter(application: ApplicationOwnership, options: { initialEntries?: string[]; initialIndex?: number; routes?: ApplicationRouteOverride[]; shell?: boolean; story?: StoryAccountingOwner } = {}) {
  // Fetch Request omits fragments. Read the matching destination from the public
  // router navigation location; initial bootstrap uses its exact history entry.
  const initial = options.initialEntries?.[options.initialIndex ?? options.initialEntries.length - 1] ?? (typeof window === 'undefined' ? '/' : window.location.href)
  let router: ReturnType<typeof createBrowserRouter> | undefined
  const requestHash = (url: URL) => {
    const pending = router?.state.navigation.location
    const destination = pending ?? new URL(initial, 'http://application.local')
    return destination.pathname === url.pathname && destination.search === url.search ? destination.hash : ''
  }
  const records = routeObjects(application, { ...options, requestHash })
  router = options.initialEntries ? createMemoryRouter(records, { initialEntries: options.initialEntries, initialIndex: options.initialIndex }) : createBrowserRouter(records)
  const owned = router
  application.driver = { navigate: (to, settings) => typeof to === 'number' ? owned.navigate(to) : owned.navigate(to, { ...settings, flushSync:true }), location: () => { const { pathname, search, hash } = owned.state.location; return pathname + search + hash } }
  const remember=()=>{const {pathname,search,hash}=owned.state.location;application.locationCommitted(pathname+search+hash)}
  remember();const stopRemember=owned.subscribe(remember)
  application.cleanup.add(stopRemember)
  application.cleanup.add(() => owned.dispose())
  return owned
}

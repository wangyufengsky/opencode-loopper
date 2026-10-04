import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { flushSync } from 'react-dom'
import { RouterProvider } from 'react-router'
import { ApplicationOwnership } from './ownership'
import { createApplicationRouter, type ApplicationRouteOverride } from '@/router'
import { initializeSkin } from '@/themes/state'
import { createStoryAccountingOwner } from './storyAccounting'
import type { TaskApplicationOwner } from '@/stores/taskStore'
const commitReact = (work: () => unknown): undefined => { flushSync(work); return undefined }
export function mountReactApplication(host: HTMLElement, options: { initialEntries?: string[]; initialIndex?: number; routes?: ApplicationRouteOverride[]; shell?: boolean; story?: boolean; taskOwner?: TaskApplicationOwner; strict?: boolean } = {}) {
  const application = new ApplicationOwnership(options.taskOwner)
  const stopSkin = initializeSkin(); application.cleanup.add(stopSkin)
  const story = options.story === false ? undefined : createStoryAccountingOwner()
  if (story) { application.cleanup.add(story.dispose); application.globalGuards.add(story.canLeave); story.start() }
  const router = createApplicationRouter(application, { ...options, story })
  const root = createRoot(host)
  flushSync(() => root.render(options.strict === false ? <RouterProvider router={router} flushSync={commitReact} /> : <StrictMode><RouterProvider router={router} flushSync={commitReact} /></StrictMode>))
  let mounted = true
  return { application, router, story, element: host, unmount(force = false) { if (!mounted) return application.healthy; const healthy = application.dispose(force); if (application.active) return false; mounted = false; flushSync(() => root.unmount()); return healthy } }
}

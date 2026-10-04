import type { mountApplicationHarness } from './applicationHarness'
import { routeFromLocation, targetPath } from '@/app/ownership'
import type { W2Target } from '@/pages/w2/shared/types'
/** Test vocabulary only. Every navigation executes the genuine React Router/application guard. */
export function navigationHarness(page: Awaited<ReturnType<typeof mountApplicationHarness>>) {
  const { application, router } = page
  const hooks = new Set<(to: ReturnType<typeof routeFromLocation>) => unknown | Promise<unknown>>()
  application.navigationInterceptor = async request => {
    const url = new URL(request.destination, 'http://localhost')
    const route = routeFromLocation({ pathname: url.pathname, search: url.search, hash: url.hash })
    for (const hook of hooks) if (await hook(route) === false) return false
    return true
  }
  return {
    get currentRoute() { return { get value() { return routeFromLocation(router.state.location, Object.assign({}, ...router.state.matches.map(match => match.params))) } } },
    push: (to: W2Target) => application.current?.navigation.go(to) ?? router.navigate(targetPath(to)),
    replace: (to: W2Target) => application.current?.navigation.go(to, true) ?? router.navigate(targetPath(to), { replace: true }),
    back: () => { void router.navigate(-1) },
    isReady: async () => { await page.settle() },
    resolve: (to: W2Target) => { const url = new URL(targetPath(to), 'http://localhost'); return routeFromLocation({ pathname: url.pathname, search: url.search, hash: url.hash }) },
    beforeEach: (hook: (to: ReturnType<typeof routeFromLocation>) => unknown | Promise<unknown>) => { hooks.add(hook); return () => { hooks.delete(hook) } },
    onError: (_hook: (error: unknown) => void) => () => undefined,
  }
}

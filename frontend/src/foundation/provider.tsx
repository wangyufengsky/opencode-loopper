import { App, ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type CSSProperties, type ReactNode } from 'react'
import { skinVariables } from '@/themes/compile'
import type { SkinDefinition } from '@/themes/types'
import { foundationTheme } from './theme'
import './foundation.css'

const ContainerContext = createContext<(() => HTMLElement) | null>(null)
export function useFoundationContainer(): () => HTMLElement {
  const container = useContext(ContainerContext)
  if (!container) throw new Error('基础组件必须位于FoundationProvider内')
  return container
}

function useReducedMotion(override?: boolean) {
  const [reduced, setReduced] = useState(() => override ?? (typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches))
  useEffect(() => {
    if (override !== undefined || typeof matchMedia !== 'function') return
    const query = matchMedia('(prefers-reduced-motion: reduce)')
    const change = () => setReduced(query.matches)
    change()
    query.addEventListener('change', change)
    return () => query.removeEventListener('change', change)
  }, [override])
  return override ?? reduced
}

/** Controlled theme host: owns only its media listener and popup DOM, never history or commands. */
export function FoundationProvider({ skin, children, reducedMotion }: {
  skin: SkinDefinition; children: ReactNode; reducedMotion?: boolean
}) {
  const host = useRef<HTMLDivElement>(null)
  const reduced = useReducedMotion(reducedMotion)
  const getContainer = useCallback(() => {
    if (!host.current) throw new Error('基础组件容器尚未挂载或已退休')
    return host.current
  }, [])
  const config = useMemo(() => foundationTheme(skin, reduced), [skin, reduced])
  const variables = useMemo(() => skinVariables(skin) as CSSProperties, [skin])
  return <div ref={host} className="loopper-foundation" data-foundation-skin={skin.id}
    data-reduced-motion={reduced} style={variables}>
    <ContainerContext.Provider value={getContainer}>
      <ConfigProvider locale={zhCN} theme={config} getPopupContainer={getContainer}
        button={{ autoInsertSpace: false }} wave={{ disabled: true }}>
        <App>{children}</App>
      </ConfigProvider>
    </ContainerContext.Provider>
  </div>
}

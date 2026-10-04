import { StrictMode, useEffect } from 'react'
import { act, fireEvent } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mountReactView } from '@/test/reactViewHarness'

const disposers: Array<() => void> = []
afterEach(() => { disposers.splice(0).forEach(dispose => dispose()); document.body.replaceChildren() })

describe('React island ownership and disposal', () => {
  it('StrictMode replay and repeated roots leave one active listener and no implicit commands', () => {
    const command = vi.fn(), received = vi.fn(), setup = vi.fn(), cleanup = vi.fn()
    function View({ label }: { label: string }) {
      useEffect(() => {
        const listener = () => received(label)
        setup()
        window.addEventListener('canvas-review-probe', listener)
        return () => { cleanup(); window.removeEventListener('canvas-review-probe', listener) }
      }, [label])
      return <button onClick={() => command(label)}>{label}</button>
    }
    const host = document.createElement('div'); document.body.append(host)
    for (let index = 0; index < 3; index++) {
      const island = mountReactView(host, StrictMode)
      disposers.push(() => act(() => island.unmount()))
      act(() => island.render({ children: <View label={`draft-${index}`} /> }))
      expect(setup.mock.calls.length - cleanup.mock.calls.length).toBe(1)
      expect(command).toHaveBeenCalledTimes(index)
      received.mockClear()
      window.dispatchEvent(new Event('canvas-review-probe'))
      expect(received.mock.calls).toEqual([[`draft-${index}`]])
      fireEvent.click(host.querySelector('button')!)
      expect(command).toHaveBeenCalledTimes(index + 1)
      act(() => island.unmount())
      expect(setup.mock.calls.length).toBe(cleanup.mock.calls.length)
      received.mockClear()
      window.dispatchEvent(new Event('canvas-review-probe'))
      expect(received).not.toHaveBeenCalled()
      expect(host.childNodes).toHaveLength(0)
    }
    expect(setup.mock.calls.length).toBeGreaterThan(3)
  })

  it('commits new owner callbacks synchronously without remounting the view', () => {
    const first = vi.fn(), next = vi.fn(), cleanup = vi.fn(), mounted = vi.fn()
    function View({ onCommand }: { onCommand(): void }) {
      useEffect(() => { mounted(); return cleanup }, [])
      return <button onClick={onCommand}>执行明确意图</button>
    }
    const host = document.createElement('div'); document.body.append(host)
    const island = mountReactView(host, View)
    disposers.push(() => act(() => island.unmount()))
    act(() => island.render({ onCommand: first }))
    const button = host.querySelector('button')!
    act(() => island.render({ onCommand: next }))
    expect(host.querySelector('button')).toBe(button)
    expect(mounted).toHaveBeenCalledTimes(1)
    expect(cleanup).not.toHaveBeenCalled()
    fireEvent.click(button)
    expect(first).not.toHaveBeenCalled()
    expect(next).toHaveBeenCalledTimes(1)
  })

  it('ignores late renders and repeated disposal after the owner unmounts', () => {
    const mounted = vi.fn(), disposed = vi.fn()
    function View() {
      useEffect(() => { mounted(); return disposed }, [])
      return <div>当前页面</div>
    }
    const host = document.createElement('div'); document.body.append(host)
    const island = mountReactView(host, View)
    disposers.push(() => act(() => island.unmount()))
    act(() => island.render({}))
    act(() => island.unmount())
    act(() => { island.render({}); island.unmount() })
    expect(host.childNodes).toHaveLength(0)
    expect(mounted).toHaveBeenCalledTimes(1)
    expect(disposed).toHaveBeenCalledTimes(1)
  })
})

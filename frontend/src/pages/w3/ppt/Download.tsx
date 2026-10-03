import { useEffect, useRef, useState } from 'react'
import { UiActionButton } from '@/foundation/components'
import { pptApi } from '@/api/ppt'
import type { PptArtifact } from '@/types/ppt'

export function useArtifactDownload(documentId: string) {
  const [busy, setBusy] = useState(false), [error, setError] = useState('')
  const mounted = useRef(false), controller = useRef<AbortController | null>(null), resources = useRef(new Map<string, ReturnType<typeof setTimeout>>())
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; controller.current?.abort(); for (const [url, timer] of resources.current) { clearTimeout(timer); URL.revokeObjectURL(url) }; resources.current.clear() } }, [documentId])
  async function download(artifact: PptArtifact, label?: string) {
    if (controller.current || !mounted.current) return
    const current = new AbortController(); controller.current = current; setBusy(true); setError('')
    try {
      const response = await fetch(pptApi.artifactUrl(documentId, artifact.id), { signal: current.signal })
      if (!response.ok) throw new Error('下载失败')
      const blob = await response.blob()
      if (!blob.size) throw new Error('文件为空')
      if (!mounted.current || controller.current !== current || current.signal.aborted) return
      const url = URL.createObjectURL(blob), anchor = document.createElement('a')
      anchor.href = url; anchor.download = label ?? artifact.name; document.body.appendChild(anchor); anchor.click(); anchor.remove()
      resources.current.set(url, setTimeout(() => { URL.revokeObjectURL(url); resources.current.delete(url) }, 30000))
    } catch (cause) { if (mounted.current && !current.signal.aborted && !(cause instanceof DOMException && cause.name === 'AbortError')) setError('文件暂时无法下载，请重试。已有导出仍会保留。') }
    finally { if (controller.current === current) { controller.current = null; if (mounted.current) setBusy(false) } }
  }
  return { busy, error, download }
}
export function ArtifactDownload({ documentId, artifact, label }: { documentId: string; artifact: PptArtifact; label?: string }) {
  const downloader = useArtifactDownload(documentId)
  return <span className="ppt-artifact-link"><a href={pptApi.artifactUrl(documentId, artifact.id)} download={label ?? artifact.name} aria-busy={downloader.busy} onClick={event => { if (event.button || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return; event.preventDefault(); void downloader.download(artifact, label) }}>{label ?? artifact.name}</a>{downloader.busy && <small role="status">正在下载</small>}{downloader.error && <span role="alert">{downloader.error}<UiActionButton actionKey="ui.retry" target="下载" onAction={() => void downloader.download(artifact, label)} /></span>}</span>
}

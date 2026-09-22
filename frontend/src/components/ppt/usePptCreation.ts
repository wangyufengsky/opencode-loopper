import { computed, ref, watch } from 'vue'
import { pptApi } from '@/api/ppt'
import { ApiError } from '@/api/client'

export interface PptInputFile {
  id: string
  name: string
  size: number
  modified: number
  kind: 'sources' | 'assets'
  file?: File
  uploaded: boolean
  key: string
}

interface CreationDraft {
  prompt: string
  documentId?: string
  creationId?: string
  generationKey?: string
  generationRevision?: number
  files: Omit<PptInputFile, 'file'>[]
}

export function pptTitleFromPrompt(prompt: string): string {
  const topic = prompt.match(
    /主题(?:是|为)?\s*[：:]?\s*(?:[“"'《]([^”"'》\n]+)[”"'》]|([^，,。.!！;；\n]+))/,
  )
  const firstSentence = prompt.trim().split(/[。！？!?\n]/)[0] || '未命名演示'
  const title = (topic?.[1] || topic?.[2] || firstSentence).trim().replace(/\s+/g, ' ')
  return Array.from(title).slice(0, 24).join('')
}

const storageKey = 'loopper.ppt.creation.v2'

export function usePptCreation() {
  const prompt = ref('')
  const files = ref<PptInputFile[]>([])
  const busy = ref(false)
  const detail = ref('')
  const error = ref('')
  const documentId = ref('')
  const creationId = ref('')
  const generationKey = ref('')
  const generationRevision = ref<number | undefined>()
  const locked = computed(() => !!creationId.value)
  const missingFiles = computed(() => files.value.some((file) => !file.uploaded && !file.file))

  try {
    const saved = JSON.parse(sessionStorage.getItem(storageKey) || 'null') as CreationDraft | null
    if (saved) {
      prompt.value = saved.prompt
      documentId.value = saved.documentId || ''
      creationId.value = saved.creationId || ''
      generationKey.value = saved.generationKey || ''
      generationRevision.value = saved.generationRevision
      files.value = saved.files || []
    }
  } catch {
    /* A damaged recovery copy must not block a new request. */
  }

  function persist() {
    const value: CreationDraft = {
      prompt: prompt.value,
      documentId: documentId.value,
      creationId: creationId.value,
      generationKey: generationKey.value,
      generationRevision: generationRevision.value,
      files: files.value.map(({ file: _file, ...metadata }) => metadata),
    }
    try {
      sessionStorage.setItem(storageKey, JSON.stringify(value))
    } catch {
      /* Keep the visible draft. */
    }
  }
  watch([prompt, files], persist, {
    deep: true,
  })

  function addFiles(selected: File[]) {
    error.value = ''
    for (const file of selected) {
      const existing = files.value.find(
        (item) =>
          item.name === file.name && item.size === file.size && item.modified === file.lastModified,
      )
      if (existing) {
        existing.file = file
        continue
      }
      if (locked.value) {
        error.value = '请重新选择原附件，或先打开已创建的作品。'
        continue
      }
      if (file.size > 20 * 1024 * 1024) {
        error.value = '单个附件不能超过 20 MiB，请压缩后再添加。'
        continue
      }
      if (!/\.(md|docx|xlsx|pptx|pdf|png|jpe?g)$/i.test(file.name)) {
        error.value = '支持 Word、Excel、PPT、PDF、Markdown 和 PNG/JPEG 图片。'
        continue
      }
      const kind = /\.(png|jpe?g)$/i.test(file.name) ? 'assets' : 'sources'
      const sources = files.value.filter((item) => item.kind === 'sources')
      if (
        kind === 'sources' &&
        (sources.length >= 10 ||
          sources.reduce((sum, item) => sum + item.size, 0) + file.size > 50 * 1024 * 1024)
      ) {
        error.value = '资料最多 10 份，总计不超过 50 MiB。'
        continue
      }
      files.value.push({
        id: crypto.randomUUID(),
        key: crypto.randomUUID(),
        name: file.name,
        size: file.size,
        modified: file.lastModified,
        kind,
        file,
        uploaded: false,
      })
    }
    persist()
  }

  function removeFile(id: string) {
    if (!locked.value) files.value = files.value.filter((file) => file.id !== id)
  }

  async function prepare(): Promise<{
    id: string
    revision: number
    prompt: string
    key: string
  } | null> {
    if (busy.value || !prompt.value.trim()) return null
    if (missingFiles.value) {
      error.value = '请重新选择标记的原附件，随后可继续生成。'
      return null
    }
    busy.value = true
    error.value = ''
    creationId.value ||= crypto.randomUUID()
    generationKey.value ||= crypto.randomUUID()
    persist()
    try {
      detail.value = '正在创建演示文稿'
      if (!documentId.value) {
        const title = pptTitleFromPrompt(prompt.value)
        const result = await pptApi.create({
          id: creationId.value,
          title,
        })
        documentId.value = result.id
        persist()
      }
      for (const [index, item] of files.value.entries()) {
        if (item.uploaded) continue
        detail.value = `正在读取附件 ${index + 1} / ${files.value.length}`
        const result = await pptApi.upload(documentId.value, item.file!, item.kind, item.key)
        if (result.state !== 'READY')
          throw new Error(result.detail || '附件尚未读取完成，请重试原附件。')
        item.uploaded = true
        persist()
      }
      const document = await pptApi.get(documentId.value)
      generationRevision.value ??= document.revision
      persist()
      return {
        id: document.id,
        revision: generationRevision.value,
        prompt: prompt.value.trim(),
        key: generationKey.value,
      }
    } catch (failure) {
      if (
        !documentId.value &&
        failure instanceof ApiError &&
        failure.status >= 400 &&
        failure.status < 500
      )
        creationId.value = ''
      error.value =
        failure instanceof Error && /[\u4e00-\u9fff]/.test(failure.message)
          ? failure.message
          : '暂时未收到结果，草稿已保留。请重试，作品不会重复创建。'
      persist()
      return null
    } finally {
      busy.value = false
      detail.value = ''
    }
  }

  function accepted() {
    prompt.value = ''
    files.value = []
    documentId.value = ''
    creationId.value = ''
    generationKey.value = ''
    generationRevision.value = undefined
    persist()
  }

  return {
    prompt,
    files,
    busy,
    detail,
    error,
    documentId,
    locked,
    missingFiles,
    addFiles,
    removeFile,
    prepare,
    accepted,
  }
}

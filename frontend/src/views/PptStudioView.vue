<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Icon } from '@iconify/vue'
import { usePptStore } from '@/stores/pptStore'
import { pptApi } from '@/api/ppt'
import {
  newPptElement,
  type PptDeck,
  type PptElement,
  type PptScope,
  type PptSlide,
} from '@/types/domain'
import { pptPhaseLabel, pptElementLabel, pptGenerationLabel } from '@/utils/displayLabels'
import PptCanvas from '@/components/ppt/PptCanvas.vue'
import PptProperties from '@/components/ppt/PptProperties.vue'
import PptSlideProperties from '@/components/ppt/PptSlideProperties.vue'
import PptChat from '@/components/ppt/PptChat.vue'
import PptProgress from '@/components/ppt/PptProgress.vue'
import PptGenerationStatus from '@/components/ppt/PptGenerationStatus.vue'
import PptSlideNavigator from '@/components/ppt/PptSlideNavigator.vue'
import PptEditorToolbar from '@/components/ppt/PptEditorToolbar.vue'
import PptDetailsDialog from '@/components/ppt/PptDetailsDialog.vue'
import PptDownloadControl from '@/components/ppt/PptDownloadControl.vue'
import { usePptPanels } from '@/components/ppt/usePptPanels'
import '@/components/ppt/ppt.css'

const route = useRoute()
const store = usePptStore()
const panels = usePptPanels()
const manual = ref(false)
const moreOpen = ref(false)
const assistantOpen = ref(false)
const details = ref<'sources' | 'history' | 'plan' | null>(null)
const rightTab = ref<'chat' | 'properties'>('chat')
const slideId = ref('')
const elementId = ref('')
const scopeKind = ref<PptScope['kind']>('DOCUMENT')
const propertyDirty = ref(false)
const historical = ref<{
  revision: number
  deck: PptDeck
} | null>(null)
const localError = ref('')
const scene = computed(() => historical.value?.deck || store.deck)
const revision = computed(() => historical.value?.revision ?? store.document?.revision ?? 0)
const slide = computed(
  () =>
    scene.value?.slides.find((value) => value.id === slideId.value) ||
    scene.value?.slides[0] ||
    null,
)
const element = computed(
  () => slide.value?.elements.find((value) => value.id === elementId.value) || null,
)
const editable = computed(() => store.editable && !store.active && !historical.value)
const ready = computed(
  () =>
    ['REVIEW', 'EXPORTED'].includes(store.document?.phase || '') ||
    store.generation?.state === 'COMPLETED' ||
    store.jobs.some((job) => job.kind === 'EXPORT' && job.state === 'COMPLETED'),
)
const scope = computed<PptScope>(() => ({
  kind: scopeKind.value,
  ...(['SLIDE', 'ELEMENT'].includes(scopeKind.value)
    ? {
        slideId: slide.value?.id,
      }
    : {}),
  ...(scopeKind.value === 'ELEMENT'
    ? {
        elementId: element.value?.id,
      }
    : {}),
}))
const scopeLabel = computed(() =>
  scopeKind.value === 'DOCUMENT'
    ? '整份演示文稿'
    : scopeKind.value === 'SLIDE'
      ? slide.value?.title || '当前页面'
      : `${slide.value?.title} · ${pptElementLabel(element.value?.type || 'text')}`,
)
const preview = computed(
  () =>
    store.jobs
      .filter(
        (job) =>
          job.kind === 'PREVIEW' &&
          job.revision <= revision.value &&
          (!historical.value || job.revision === historical.value.revision),
      )
      .flatMap((job) =>
        job.artifacts
          .filter(
            (artifact) =>
              artifact.slideId === slide.value?.id && artifact.mediaType === 'image/png',
          )
          .map((artifact) => ({
            artifact,
            revision: job.revision,
            createdAt: job.createdAt,
          })),
      )
      .sort((a, b) => b.revision - a.revision || b.createdAt.localeCompare(a.createdAt))[0],
)
const index = computed(
  () => scene.value?.slides.findIndex((value) => value.id === slide.value?.id) ?? -1,
)
let ticket = 0

watch(
  () => route.params.id,
  async (value) => {
    const current = ++ticket
    historical.value = null
    localError.value = ''
    elementId.value = ''
    scopeKind.value = 'DOCUMENT'
    manual.value = false
    details.value = null
    rightTab.value = 'chat'
    await store.load(String(value))
    if (current !== ticket) return
    try {
      slideId.value = sessionStorage.getItem(`loopper.ppt.slide.${value}`) || ''
    } catch {
      slideId.value = ''
    }
  },
  {
    immediate: true,
  },
)
watch(element, (value) => {
  if (!value && elementId.value) {
    elementId.value = ''
    if (scopeKind.value === 'ELEMENT') scopeKind.value = 'SLIDE'
  }
})
watch(slideId, (value) => {
  if (store.document)
    try {
      sessionStorage.setItem(`loopper.ppt.slide.${store.document.id}`, value)
    } catch {
      /* Optional position preference. */
    }
})
const timer = setInterval(() => {
  if (store.active || store.jobsActive || store.disconnected) void store.refresh()
}, 4000)
onBeforeUnmount(() => {
  ticket++
  clearInterval(timer)
  store.close()
})

function selectSlide(id: string) {
  slideId.value = id
  elementId.value = ''
  scopeKind.value = 'SLIDE'
  propertyDirty.value = false
}

function selectElement(id: string) {
  elementId.value = id
  scopeKind.value = id ? 'ELEMENT' : 'SLIDE'
  if (manual.value && id) rightTab.value = 'properties'
  propertyDirty.value = false
}

function changeScope(kind: PptScope['kind']) {
  scopeKind.value = kind
  if (kind === 'DOCUMENT') elementId.value = ''
}

function openDetails(value: 'sources' | 'history' | 'plan') {
  details.value = value
  moreOpen.value = false
}
async function patchElement(id: string, patch: Partial<PptElement>, base: number) {
  if (!slide.value || !editable.value) return
  const currentSlide = slide.value.id
  if (
    await store.operations(
      [
        {
          op: 'update_element',
          slideId: currentSlide,
          elementId: id,
          patch,
        },
      ],
      base,
    )
  )
    await store.createJob('PREVIEW', currentSlide)
}
async function addElement(type: string, assetId?: string) {
  if (!slide.value || !editable.value) return
  const created = newPptElement(type)
  if (assetId) created.assetId = assetId
  if (
    await store.operations([
      {
        op: 'add_element',
        slideId: slide.value.id,
        element: created,
      },
    ])
  ) {
    elementId.value = created.id
    scopeKind.value = 'ELEMENT'
    rightTab.value = 'properties'
    await store.createJob('PREVIEW', slide.value?.id)
  }
}
async function insertImage(id: string) {
  if (!ready.value) return
  details.value = null
  manual.value = true
  await addElement('image', id)
}
async function removeElement(id: string) {
  if (!slide.value || !editable.value || !window.confirm('删除这个对象？历史版本仍会保留。')) return
  if (
    await store.operations([
      {
        op: 'remove_element',
        slideId: slide.value.id,
        elementId: id,
      },
    ])
  ) {
    elementId.value = ''
    await store.createJob('PREVIEW', slide.value?.id)
  }
}
async function addSlide() {
  const id = crypto.randomUUID()
  if (
    await store.operations([
      {
        op: 'create_slide',
        slide: {
          id,
          title: `第 ${(store.deck?.slides.length || 0) + 1} 页`,
          section: '',
          notes: '',
          locked: false,
          elements: [],
        },
      },
    ])
  )
    slideId.value = id
}
async function slideAction(
  action: 'duplicate_slide' | 'delete_slide' | 'move_slide',
  target?: number,
) {
  if (!slide.value) return
  if (action === 'delete_slide' && !window.confirm('删除当前页面？历史版本仍会保留。')) return
  await store.operations([
    {
      op: action,
      slideId: slide.value.id,
      index: target,
    },
  ])
  if (!scene.value?.slides.some((item) => item.id === slideId.value))
    slideId.value = scene.value?.slides[0]?.id || ''
}
async function patchSlide(id: string, patch: Partial<PptSlide>, base: number) {
  await store.operations(
    [
      {
        op: 'update_slide',
        slideId: id,
        patch,
      },
    ],
    base,
  )
}
async function theme(value: string) {
  if (
    await store.operations([
      {
        op: 'apply_theme',
        theme: value,
      },
    ])
  )
    await store.createJob('PREVIEW')
}
async function inspectRevision(value: number) {
  if (!store.document) return
  const id = store.document.id
  try {
    const deck = await pptApi.deck(id, value)
    if (id === store.document?.id) {
      historical.value = {
        revision: value,
        deck,
      }
      elementId.value = ''
      manual.value = false
      details.value = null
    }
  } catch {
    localError.value = '历史版本暂时无法读取，请重试。'
  }
}
async function restoreRevision(value: number) {
  if (!window.confirm(`将版本 ${value} 恢复为新版本？当前内容会保留在历史记录中。`)) return
  if (
    await store.action('restore', {
      targetRevision: value,
    })
  ) {
    historical.value = null
    details.value = null
  }
}
async function archive() {
  if (!store.document) return
  if (!store.document.archived && !window.confirm('归档这份作品？可以从作品列表恢复。')) return
  await store.action('archive', {
    archived: !store.document.archived,
  })
  moreOpen.value = false
}
function closePanels() {
  moreOpen.value = false
  assistantOpen.value = false
}
function toggleManual() {
  manual.value = !manual.value
  rightTab.value = manual.value ? 'properties' : 'chat'
  if (manual.value) assistantOpen.value = true
}
function recheck() {
  localError.value = ''
  store.error = ''
  void store.refresh()
}
function locateIssue(slide: string, element: string) {
  selectSlide(slide)
  selectElement(element)
}
</script>

<template>
  <main
    id="main-content"
    class="ppt-page ppt-studio"
    :class="{ 'is-ready': ready, 'is-editing': manual }"
    aria-label="PPT 工作台"
    @keydown.esc="closePanels"
  >
    <header class="ppt-studio-header">
      <RouterLink to="/ppt" class="ppt-back-icon" aria-label="返回作品列表">
        <Icon icon="lucide:arrow-left" />
      </RouterLink>
      <div class="ppt-studio-title">
        <h1>{{ store.document?.title || 'PPT 工作室' }}</h1>
        <p v-if="store.document">
          <span class="ppt-status-dot" />
          {{
            store.document.archived
              ? '已归档'
              : store.active && ready
                ? '正在按你的意见修改'
                : pptPhaseLabel(store.document.phase)
          }}
          <span v-if="store.busy">· 正在保存</span>
          <span v-else-if="propertyDirty">· 修改已保留</span>
        </p>
      </div>
      <div class="ppt-studio-actions">
        <button
          v-if="ready"
          class="ppt-manual-toggle"
          :class="{ active: manual }"
          :aria-pressed="manual"
          :disabled="
            store.active || !!historical || store.document?.archived || (manual && propertyDirty)
          "
          :title="manual && propertyDirty ? '修改保存后即可完成编辑' : undefined"
          @click="toggleManual"
        >
          <Icon :icon="manual ? 'lucide:check' : 'lucide:mouse-pointer-2'" />
          {{ manual ? '完成编辑' : '手动编辑' }}
        </button>
        <PptDownloadControl v-if="ready && !historical" />
        <div class="ppt-more">
          <button
            class="ppt-icon-button"
            aria-label="更多作品操作"
            :aria-expanded="moreOpen"
            @click="moreOpen = !moreOpen"
          >
            <Icon icon="lucide:ellipsis" />
          </button>
          <div v-if="moreOpen" class="ppt-more-menu">
            <button @click="openDetails('sources')">
              <Icon icon="lucide:paperclip" />
              资料与素材
            </button>
            <button @click="openDetails('history')">
              <Icon icon="lucide:history" />
              版本与导出
            </button>
            <button v-if="ready" @click="openDetails('plan')">
              <Icon icon="lucide:notebook-text" />
              制作方案
            </button>
            <button :disabled="store.busy || store.active" @click="archive">
              <Icon icon="lucide:archive" />
              {{ store.document?.archived ? '恢复作品' : '归档作品' }}
            </button>
          </div>
        </div>
      </div>
    </header>
    <div v-if="store.error || localError" role="alert" class="ppt-notice ppt-page-notice">
      {{ store.error || localError }}
      <button :disabled="store.busy" @click="recheck">重新核对</button>
      <button v-if="store.pending" :disabled="store.busy" @click="store.retryPending">
        重试原操作
      </button>
    </div>
    <p v-if="store.disconnected" role="status" class="ppt-connection-notice">
      <Icon icon="lucide:wifi-off" />
      连接暂时中断，正在重新连接。你的输入会保留。
    </p>
    <p
      v-if="store.capabilities && !store.capabilities.renderingAvailable"
      role="alert"
      class="ppt-notice ppt-page-notice"
    >
      {{ store.capabilities.renderingMessage || '当前环境无法预览，请检查运行环境后重试。' }}
    </p>
    <div v-if="store.loading && !store.document" role="status" class="ppt-empty">
      正在打开演示文稿…
    </div>

    <div v-else-if="store.document && !ready" class="ppt-generation-room">
      <PptGenerationStatus />
      <PptChat
        :scope="{ kind: 'DOCUMENT' }"
        scope-label="整份演示文稿"
        :ready="false"
        spacious
        :disabled="store.document.archived"
        @change-scope="changeScope"
      />
    </div>

    <div
      v-else-if="store.document && ready"
      class="ppt-review-workspace"
      :class="{ 'assistant-open': assistantOpen }"
      :style="{ '--ppt-right-width': `${panels.rightWidth.value}px` }"
    >
      <section class="ppt-presentation-panel" aria-label="演示文稿预览">
        <p v-if="historical" class="ppt-notice">
          正在查看历史版本 {{ historical.revision }}
          <button @click="historical = null">返回当前版本</button>
        </p>
        <section
          v-if="store.generation && store.generation.state !== 'COMPLETED'"
          class="ppt-revision-status"
          aria-live="polite"
        >
          <div>
            <strong>{{ pptGenerationLabel(store.generation.state) }}</strong>
            <p v-if="store.generation.detail">{{ store.generation.detail }}</p>
          </div>
          <button
            v-if="store.generation.canResume"
            class="ppt-primary"
            :disabled="store.busy || !!store.pending"
            @click="store.resume"
          >
            <Icon icon="lucide:play" />
            继续制作
          </button>
        </section>
        <PptProgress
          :jobs="store.jobs"
          :revision="store.document.revision"
          :busy="store.busy"
          @history="openDetails('history')"
          @retry="store.retryJob"
        />
        <div class="ppt-presentation-heading">
          <div>
            <span v-if="slide" class="ppt-page-number">
              {{ String(index + 1).padStart(2, '0') }}
              <span>/ {{ scene?.slides.length }}</span>
            </span>
            <h2>{{ slide?.title || '演示文稿' }}</h2>
          </div>
          <div class="ppt-inline">
            <button
              class="ppt-icon-button"
              aria-label="上一页"
              :disabled="index <= 0"
              @click="selectSlide(scene!.slides[index - 1]!.id)"
            >
              <Icon icon="lucide:chevron-left" />
            </button>
            <button
              class="ppt-icon-button"
              aria-label="下一页"
              :disabled="!scene || index >= scene.slides.length - 1"
              @click="selectSlide(scene!.slides[index + 1]!.id)"
            >
              <Icon icon="lucide:chevron-right" />
            </button>
            <button class="ppt-open-assistant" @click="assistantOpen = !assistantOpen">
              <Icon icon="lucide:message-circle" />
              提修改意见
            </button>
          </div>
        </div>
        <div class="ppt-presentation-scroll">
          <PptEditorToolbar
            v-if="manual && scene && slide"
            :deck="scene"
            :slide="slide"
            :element="element"
            :disabled="!editable"
            @add="addElement"
            @image="openDetails('sources')"
            @page="slideAction"
            @theme="theme"
          />
          <div v-if="slide && scene" class="ppt-main-slide">
            <PptCanvas
              v-if="preview || manual"
              :deck="scene"
              :slide="slide"
              :selected="elementId"
              :revision="revision"
              :disabled="!manual || !editable"
              :editing="manual"
              :preview="
                preview ? pptApi.artifactUrl(store.document.id, preview.artifact.id) : undefined
              "
              :preview-revision="preview?.revision"
              @select="selectElement"
              @patch="patchElement"
              @remove="removeElement"
            />
            <div v-else class="ppt-preview-unavailable">
              <Icon icon="lucide:presentation" />
              <h3>
                {{ store.jobsActive ? '预览正在准备' : '这个版本还没有预览' }}
              </h3>
              <button
                :disabled="
                  !!historical ||
                  store.active ||
                  store.document.archived ||
                  store.busy ||
                  store.jobsActive ||
                  !store.capabilities?.renderingAvailable
                "
                @click="store.createJob('PREVIEW')"
              >
                生成预览
              </button>
            </div>
          </div>
          <div v-if="manual" class="ppt-manual-footer">
            <button :disabled="store.busy" @click="store.check">
              <Icon icon="lucide:scan-line" />
              检查排版
            </button>
            <button
              :disabled="!editable || !store.capabilities?.renderingAvailable"
              @click="store.createJob('PREVIEW')"
            >
              更新预览
            </button>
            <small>修改自动保存</small>
          </div>
          <div v-if="store.checkedRevision === store.document.revision" class="ppt-checks">
            <p v-if="!store.issues.length" class="ppt-muted">未发现排版问题。</p>
            <p v-for="(issue, issueIndex) in store.issues" :key="issueIndex" class="ppt-notice">
              <button @click="locateIssue(issue.slideId, issue.elementId)">查看位置</button>
              {{ issue.message }}
            </p>
          </div>
        </div>
        <PptSlideNavigator
          v-if="scene"
          :document-id="store.document.id"
          :deck="scene"
          :jobs="store.jobs"
          :selected="slide?.id || ''"
          :revision="revision"
          :manual="manual"
          :disabled="!editable"
          @select="selectSlide"
          @add="addSlide"
        />
      </section>
      <div
        class="ppt-review-separator"
        role="separator"
        aria-label="调整助手栏宽度"
        aria-orientation="vertical"
        tabindex="0"
        :aria-valuenow="panels.rightWidth.value"
        :aria-valuemin="280"
        :aria-valuemax="520"
        @pointerdown="panels.start($event, 'right')"
        @keydown="panels.keyboard($event, 'right')"
      />
      <aside class="ppt-assistant-panel">
        <div v-if="manual" class="ppt-tabs">
          <button :class="{ active: rightTab === 'chat' }" @click="rightTab = 'chat'">
            修改意见
          </button>
          <button :class="{ active: rightTab === 'properties' }" @click="rightTab = 'properties'">
            对象属性
          </button>
        </div>
        <button
          class="ppt-close-assistant ppt-icon-button"
          aria-label="收起助手"
          @click="assistantOpen = false"
        >
          <Icon icon="lucide:x" />
        </button>
        <PptChat
          v-show="!manual || rightTab === 'chat'"
          :scope="scope"
          :scope-label="scopeLabel"
          :disabled="store.document.archived || !!historical"
          @change-scope="changeScope"
        />
        <PptSlideProperties
          v-if="manual && !element && slide"
          v-show="rightTab === 'properties'"
          :slide="slide"
          :document-id="store.document.id"
          :revision="revision"
          :disabled="!editable"
          @save="patchSlide"
          @dirty="propertyDirty = $event"
        />
        <PptProperties
          v-else-if="manual"
          v-show="rightTab === 'properties'"
          :element="element"
          :document-id="store.document.id"
          :revision="revision"
          :capabilities="store.capabilities"
          :disabled="!editable"
          :slide-locked="slide?.locked"
          @save="patchElement"
          @remove="removeElement"
          @dirty="propertyDirty = $event"
        />
      </aside>
    </div>
    <PptDetailsDialog
      :ready="ready"
      v-model="details"
      @view="inspectRevision"
      @restore="restoreRevision"
      @insert="insertImage"
    />
  </main>
</template>

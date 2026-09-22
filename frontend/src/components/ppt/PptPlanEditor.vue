<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  emptyPptPlan,
  type PptPlan,
  type PptEditablePlan,
  type PptCapabilities,
  type PptSource,
} from '@/types/domain'
import { pptLayoutLabel } from '@/utils/displayLabels'
import PptPlanNarrative from './PptPlanNarrative.vue'
import PptPlanPresentation from './PptPlanPresentation.vue'
import { usePptAutosave } from './usePptAutosave'
const props = defineProps<{
  plan: PptPlan
  revision: number
  documentId?: string
  disabled?: boolean
  directionFrozen?: boolean
  capabilities: PptCapabilities | null
  sources?: PptSource[]
}>()
const emit = defineEmits<{
  save: [plan: PptPlan, revision: number]
  dirty: [value: boolean]
}>()
type Tab = 'brief' | 'narrative' | 'slides' | 'visual' | 'assets' | 'notes' | 'delivery'
const tabs: {
  id: Tab
  title: string
}[] = [
  {
    id: 'brief',
    title: '制作目标',
  },
  {
    id: 'narrative',
    title: '叙事结构',
  },
  {
    id: 'slides',
    title: '页面内容',
  },
  {
    id: 'visual',
    title: '视觉规范',
  },
  {
    id: 'assets',
    title: '图表素材',
  },
  {
    id: 'notes',
    title: '演讲辅助',
  },
  {
    id: 'delivery',
    title: '交付设置',
  },
]

function normalized(original: PptPlan): PptEditablePlan {
  const value = JSON.parse(JSON.stringify(original)) as PptPlan
  return {
    ...emptyPptPlan(),
    ...value,
    brief: {
      ...emptyPptPlan().brief,
      ...value.brief,
    },
    narrative: {
      story: '',
      chapters: [],
      ...value.narrative,
    },
    visualRules: {
      style: '',
      fontFamily: 'Noto Sans CJK SC',
      accentColor: '2563EB',
      density: '适中',
      aspectRatio: '16:9',
      ...value.visualRules,
    },
    assets: {
      requirements: '',
      chartGuidance: '',
      imageGuidance: '',
      ...value.assets,
    },
    delivery: {
      fileName: '',
      targetSoftware: 'WPS / PowerPoint',
      includeNotes: true,
      ...value.delivery,
    },
  }
}
const draft = ref(normalized(emptyPptPlan())),
  baseline = ref(''),
  baseRevision = ref(-1),
  tab = ref<Tab>('brief'),
  form = ref<HTMLFormElement>()
const dirty = computed(() => JSON.stringify(draft.value) !== baseline.value)
const storageKey = () => (props.documentId ? `loopper.ppt.plan.${props.documentId}` : '')

function reload() {
  draft.value = normalized(props.plan || emptyPptPlan())
  baseline.value = JSON.stringify(draft.value)
  baseRevision.value = props.revision
  try {
    if (storageKey()) sessionStorage.removeItem(storageKey())
  } catch {
    /* Optional recovery copy. */
  }
}
// Read the persisted edit before reload clears a previous acknowledged draft.
function initialize() {
  let saved = ''
  try {
    saved = sessionStorage.getItem(storageKey()) || ''
  } catch {
    /* Optional draft. */
  }
  reload()
  if (saved)
    try {
      const value = JSON.parse(saved) as {
        draft: PptPlan
        baseline: string
        revision: number
      }
      draft.value = normalized(value.draft)
      baseline.value = value.baseline
      baseRevision.value = value.revision
    } catch {
      /* Ignore corrupt draft. */
    }
}
watch(() => props.documentId, initialize, {
  immediate: true,
})
watch(
  () => props.revision,
  () => {
    if (
      baseRevision.value < 0 ||
      !dirty.value ||
      JSON.stringify(normalized(props.plan)) === JSON.stringify(draft.value)
    )
      reload()
  },
)
watch(dirty, (value) => emit('dirty', value), {
  immediate: true,
})
watch(
  draft,
  () => {
    if (!storageKey()) return
    try {
      if (dirty.value)
        sessionStorage.setItem(
          storageKey(),
          JSON.stringify({
            draft: draft.value,
            baseline: baseline.value,
            revision: baseRevision.value,
          }),
        )
      else sessionStorage.removeItem(storageKey())
    } catch {
      /* Keep the visible draft. */
    }
  },
  {
    deep: true,
  },
)
const autosave = usePptAutosave(
  () => JSON.stringify(draft.value),
  () => dirty.value,
  () =>
    !props.disabled &&
    baseRevision.value === props.revision &&
    (form.value?.checkValidity() ?? true),
  () => baseRevision.value,
  () => emit('save', JSON.parse(JSON.stringify(draft.value)) as PptPlan, baseRevision.value),
)

function addSlide() {
  draft.value.slides.push({
    id: crypto.randomUUID(),
    title: `第 ${draft.value.slides.length + 1} 页`,
    section: '',
    message: '',
    content: '',
    layout: 'title_content',
    sourceIds: [],
    notes: '',
  })
}

function removeSlide(index: number) {
  if (window.confirm('移除这页设计？历史版本仍会保留。')) draft.value.slides.splice(index, 1)
}

function move(index: number, step: number) {
  const target = index + step
  if (target < 0 || target >= draft.value.slides.length) return
  const [slide] = draft.value.slides.splice(index, 1)
  if (slide) draft.value.slides.splice(target, 0, slide)
}
</script>
<template>
  <section class="ppt-plan" aria-label="制作方案">
    <nav class="ppt-tabs ppt-plan-tabs" aria-label="方案模块">
      <button
        v-for="item in tabs"
        :key="item.id"
        :class="{ active: tab === item.id }"
        @click="tab = item.id"
      >
        {{ item.title }}
      </button>
    </nav>
    <p v-if="dirty && baseRevision !== revision" role="alert" class="ppt-notice">
      方案已有新版本，当前输入已保留，自动保存已暂停。
      <button @click="reload">读取最新方案</button>
    </p>
    <form ref="form" @submit.prevent="autosave.save">
      <fieldset :disabled="disabled">
        <div v-if="tab === 'brief'" class="ppt-plan-module">
          <h2>制作目标</h2>
          <label>
            受众
            <input v-model="draft.brief.audience" placeholder="这份演示文稿讲给谁听" />
          </label>
          <label>
            目的
            <textarea v-model="draft.brief.purpose" rows="3" placeholder="希望观众理解或决定什么" />
          </label>
          <div class="ppt-form-grid">
            <label>
              汇报时长
              <input v-model="draft.brief.duration" placeholder="例如 15 分钟" />
            </label>
            <label>
              预计页数
              <input
                v-model.number="draft.brief.pageCount"
                type="number"
                min="1"
                :max="capabilities?.maxSlides || 100"
              />
            </label>
          </div>
          <label>
            补充要求
            <textarea v-model="draft.brief.requirements" rows="6" />
          </label>
        </div>
        <PptPlanNarrative
          v-else-if="tab === 'narrative'"
          v-model="draft"
          :direction-frozen="directionFrozen"
        />
        <div v-else-if="tab === 'slides'" class="ppt-plan-module">
          <h2>页面内容</h2>
          <article v-for="(slide, index) in draft.slides" :key="slide.id" class="ppt-plan-slide">
            <header class="ppt-section-heading">
              <strong>第 {{ index + 1 }} 页</strong>
              <div class="ppt-inline">
                <button type="button" :disabled="index === 0" @click="move(index, -1)">上移</button>
                <button
                  type="button"
                  :disabled="index === draft.slides.length - 1"
                  @click="move(index, 1)"
                >
                  下移
                </button>
                <button type="button" @click="removeSlide(index)">移除</button>
              </div>
            </header>
            <label>
              标题
              <input v-model="slide.title" required />
            </label>
            <label>
              章节
              <input v-model="slide.section" />
            </label>
            <label>
              核心观点
              <textarea v-model="slide.message" rows="2" />
            </label>
            <label>
              页面内容
              <textarea v-model="slide.content" rows="5" />
            </label>
            <label>
              版式
              <select v-model="slide.layout">
                <option v-for="layout in capabilities?.layouts || []" :key="layout" :value="layout">
                  {{ pptLayoutLabel(layout) }}
                </option>
              </select>
            </label>
          </article>
          <button type="button" @click="addSlide">增加页面设计</button>
        </div>
        <PptPlanPresentation
          v-else
          v-model="draft"
          :tab="tab"
          :capabilities="capabilities"
          :sources="sources || []"
        />
        <footer class="ppt-plan-footer">
          <span>
            {{
              !dirty
                ? '已自动保存'
                : autosave.scheduled.value
                  ? '等待自动保存…'
                  : disabled
                    ? '有未保存修改'
                    : '修改已保留，请核对后保存'
            }}
          </span>
          <button class="ppt-primary" :disabled="!dirty || baseRevision !== revision">
            立即保存
          </button>
        </footer>
      </fieldset>
    </form>
  </section>
</template>

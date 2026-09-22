<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { PptElement, PptCapabilities } from '@/types/domain'
import { pptElementLabel } from '@/utils/displayLabels'
import { usePptAutosave } from './usePptAutosave'
const props = defineProps<{ element: PptElement | null; revision: number; documentId?: string; capabilities: PptCapabilities | null; disabled?: boolean; slideLocked?: boolean }>()
const emit = defineEmits<{ save: [id: string, patch: Partial<PptElement>, revision: number]; remove: [id: string]; dirty: [value: boolean] }>()
const draft = ref<PptElement | null>(null), baseline = ref(''), baseRevision = ref(0), changed = computed(() => !!draft.value && JSON.stringify(draft.value) !== baseline.value)
const tableText = ref(''), categories = ref(''), form = ref<HTMLFormElement>()
const storageKey = () => props.documentId && props.element ? `loopper.ppt.element.${props.documentId}.${props.element.id}` : ''
function reload() { draft.value = props.element ? JSON.parse(JSON.stringify(props.element)) as PptElement : null; baseline.value = JSON.stringify(draft.value); baseRevision.value = props.revision; tableText.value = draft.value?.rows.map(row => row.join('\t')).join('\n') || ''; categories.value = draft.value?.chart?.categories.join('，') || '' }
function initialize() { let saved = ''; try { saved = sessionStorage.getItem(storageKey()) || '' } catch { /* Optional recovery. */ } reload(); if (saved) try { const value = JSON.parse(saved) as { draft: PptElement; baseline: string; revision: number }; if (value.draft.id === props.element?.id) { draft.value = value.draft; baseline.value = value.baseline; baseRevision.value = value.revision; tableText.value = draft.value.rows.map(row => row.join('\t')).join('\n'); categories.value = draft.value.chart?.categories.join('，') || '' } } catch { /* Ignore corrupt local edits. */ } }
watch(() => props.element?.id, initialize, { immediate: true })
watch(() => props.revision, () => { if (!changed.value || JSON.stringify(props.element) === JSON.stringify(draft.value)) reload() })
watch(changed, value => emit('dirty', value), { immediate: true })
watch(draft, () => { if (!storageKey()) return; try { if (changed.value) sessionStorage.setItem(storageKey(), JSON.stringify({ draft: draft.value, baseline: baseline.value, revision: baseRevision.value })); else sessionStorage.removeItem(storageKey()) } catch { /* Keep the visible edit. */ } }, { deep: true })
const autosave = usePptAutosave(() => JSON.stringify(draft.value), () => changed.value, () => !props.disabled && !props.slideLocked && !props.element?.locked && baseRevision.value === props.revision && (form.value?.checkValidity() ?? true), () => baseRevision.value, save)
function save() { if (!draft.value) return; const { id, type: _type, ...patch } = draft.value; emit('save', id, patch, baseRevision.value) }
function table(value: string) { if (draft.value) draft.value.rows = value.split('\n').map(row => row.split('\t')) }
function chartCategories(value: string) { if (draft.value?.chart) draft.value.chart.categories = value.split(/[,，]/).map(part => part.trim()) }
function chartValues(index: number, value: string) { if (draft.value?.chart?.series[index]) draft.value.chart.series[index]!.values = value.split(/[,，]/).map(part => Number(part.trim())) }
</script>
<template>
  <section class="ppt-properties" aria-label="对象属性">
    <template v-if="draft">
      <header class="ppt-section-heading"><strong>{{ pptElementLabel(draft.type) }}</strong><button v-if="!slideLocked" :disabled="disabled" @click="emit('save', draft.id, { locked: !element?.locked }, revision)">{{ element?.locked ? '解锁对象' : '锁定对象' }}</button></header>
      <p v-if="changed && baseRevision !== revision" role="alert" class="ppt-notice">页面已更新，你的输入仍保留。请先核对最新对象再保存。<button @click="reload">读取最新对象</button></p>
      <form ref="form" @submit.prevent="autosave.save">
        <fieldset :disabled="disabled || element?.locked || slideLocked">
          <label v-if="draft.type === 'text'">文字<textarea v-model="draft.text" rows="5" /></label>
          <div class="ppt-form-grid"><label>横向位置<input v-model.number="draft.x" type="number" step="1" /></label><label>纵向位置<input v-model.number="draft.y" type="number" step="1" /></label><label>宽度<input v-model.number="draft.width" type="number" min="1" step="1" /></label><label>高度<input v-model.number="draft.height" type="number" min="1" step="1" /></label></div>
          <template v-if="draft.type === 'text'">
            <label>字体<select v-model="draft.fontFamily"><option v-for="font in capabilities?.fonts || []" :key="font" :value="font">{{ font }}</option></select></label>
            <div class="ppt-form-grid"><label>字号<input v-model.number="draft.fontSize" type="number" min="8" max="144" /></label><label>文字颜色<input v-model="draft.color" pattern="#?[a-fA-F0-9]{6}" placeholder="六位颜色值" /></label></div>
            <label>对齐<select v-model="draft.align"><option value="left">左对齐</option><option value="center">居中</option><option value="right">右对齐</option></select></label>
            <label class="ppt-check"><input v-model="draft.bold" type="checkbox" />加粗</label><label class="ppt-check"><input v-model="draft.bullets" type="checkbox" />项目符号</label>
          </template>
          <label v-if="draft.type === 'image'">图片显示<select v-model="draft.fit"><option value="contain">完整显示</option><option value="cover">填满并裁切</option></select></label>
          <template v-if="draft.type === 'shape' || draft.type === 'line'"><label>形状<select v-model="draft.shape"><option value="rect">矩形</option><option value="roundRect">圆角矩形</option><option value="ellipse">椭圆</option><option value="arrow">箭头</option></select></label><label>填充颜色<input v-model="draft.fill" placeholder="六位颜色值" /></label><label>线条颜色<input v-model="draft.stroke" placeholder="六位颜色值" /></label><label>线宽<input v-model.number="draft.lineWidth" type="number" min="0" step="0.5" /></label></template>
          <label v-if="draft.type === 'table'">表格内容<textarea v-model="tableText" rows="8" @input="table(tableText)" /><small>每行一行数据，列之间使用制表符，可从表格直接粘贴。</small></label>
          <template v-if="draft.type === 'chart' && draft.chart"><label>图表类型<select v-model="draft.chart.type"><option value="bar">柱状图</option><option value="line">折线图</option><option value="pie">饼图</option></select></label><label>分类<textarea v-model="categories" rows="2" @input="chartCategories(categories)" /><small>用逗号分隔。</small></label><div v-for="(series, index) in draft.chart.series" :key="index" class="ppt-chart-series"><label>系列名称<input v-model="series.name" /></label><label>数据<input :value="series.values.join('，')" @change="chartValues(index, ($event.target as HTMLInputElement).value)" /></label><label>颜色<input v-model="series.color" /></label><button type="button" :disabled="draft.chart.series.length <= 1" @click="draft.chart.series.splice(index, 1)">移除系列</button></div><button type="button" @click="draft.chart.series.push({ name: '新系列', values: draft.chart.categories.map(() => 0), color: '2563EB' })">增加系列</button></template>
          <label v-if="!['chart', 'table'].includes(draft.type)">旋转角度<input v-model.number="draft.rotation" type="number" min="-360" max="360" /></label><label class="ppt-check"><input v-model="draft.allowOverlap" type="checkbox" />允许设计重叠</label>
          <small>{{ !changed ? '已自动保存' : autosave.scheduled.value ? '等待自动保存…' : '修改已保留，可重新保存' }}</small><button class="ppt-primary" :disabled="!changed || baseRevision !== revision">立即保存属性</button>
        </fieldset>
      </form>
      <button :disabled="disabled || element?.locked || slideLocked" class="ppt-danger" @click="emit('remove', draft.id)">删除对象</button>
    </template><p v-else class="ppt-empty">选择画布中的对象可编辑属性。</p>
  </section>
</template>

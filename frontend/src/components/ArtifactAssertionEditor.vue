<script setup lang="ts">
import type { LoopVerifierSpec } from '@/types/domain'

defineProps<{ verifier: LoopVerifierSpec }>()
</script>

<template>
  <section class="artifact-assertions" aria-label="制品验收断言">
    <template v-if="verifier.type === 'DOCUMENT_STRUCTURE'">
      <header><strong>文档断言</strong><el-button :disabled="verifier.documentAssertions.length >= 64" @click="verifier.documentAssertions.push({ type: 'TEXT_EXISTS', value: '' })">添加文档断言</el-button></header>
      <div v-for="(assertion, index) in verifier.documentAssertions" :key="index" class="assertion-row">
        <el-select v-model="assertion.type" :aria-label="`文档断言 ${index + 1} 类型`">
          <el-option label="包含标题" value="HEADING_EXISTS" /><el-option label="包含正文" value="TEXT_EXISTS" />
          <el-option label="表格数量" value="TABLE_COUNT" /><el-option label="本地链接有效" value="LOCAL_LINKS_VALID" />
        </el-select>
        <el-input v-if="['HEADING_EXISTS', 'TEXT_EXISTS'].includes(assertion.type)" v-model="assertion.value" :aria-label="`文档断言 ${index + 1} 期望文本`" placeholder="期望文本" />
        <el-input-number v-if="assertion.type === 'HEADING_EXISTS'" v-model="assertion.headingLevel" :min="1" :max="4" :aria-label="`文档断言 ${index + 1} 标题级别`" placeholder="任意级别" />
        <el-input-number v-if="assertion.type === 'TABLE_COUNT'" v-model="assertion.expectedCount" :min="0" :max="10000" :aria-label="`文档断言 ${index + 1} 期望表格数量`" />
        <el-button :aria-label="`删除文档断言 ${index + 1}`" @click="verifier.documentAssertions.splice(index, 1)">删除</el-button>
      </div>
    </template>
    <template v-if="verifier.type === 'TABULAR_DATA'">
      <header><strong>表格断言</strong><el-button :disabled="verifier.tabularAssertions.length >= 64" @click="verifier.tabularAssertions.push({ type: 'EQUIVALENT_TO', sourcePath: '' })">添加表格断言</el-button></header>
      <div v-for="(assertion, index) in verifier.tabularAssertions" :key="index" class="assertion-row">
        <el-select v-model="assertion.type" :aria-label="`表格断言 ${index + 1} 类型`">
          <el-option label="包含工作表" value="SHEET_EXISTS" /><el-option label="行数" value="ROW_COUNT" /><el-option label="列数" value="COLUMN_COUNT" />
          <el-option label="表头相等" value="HEADER_EQUALS" /><el-option label="单元格相等" value="CELL_EQUALS" /><el-option label="与源表等价" value="EQUIVALENT_TO" />
        </el-select>
        <el-input v-if="assertion.type === 'EQUIVALENT_TO'" v-model="assertion.sourcePath" :aria-label="`表格断言 ${index + 1} 源文件路径`" placeholder="源文件相对路径" />
        <template v-else>
          <el-input v-model="assertion.sheet" :aria-label="`表格断言 ${index + 1} 工作表`" placeholder="工作表（默认第一张）" />
          <el-input-number v-if="['ROW_COUNT', 'COLUMN_COUNT'].includes(assertion.type)" v-model="assertion.expectedCount" :min="0" :max="100000" :aria-label="`表格断言 ${index + 1} 期望数量`" />
          <template v-if="assertion.type === 'CELL_EQUALS'">
            <el-input-number v-model="assertion.row" :min="0" :max="100000" :aria-label="`表格断言 ${index + 1} 行号`" placeholder="行号（从 0 开始）" />
            <el-input-number v-model="assertion.column" :min="0" :max="1000" :aria-label="`表格断言 ${index + 1} 列号`" placeholder="列号（从 0 开始）" />
          </template>
          <el-input v-if="['HEADER_EQUALS', 'CELL_EQUALS'].includes(assertion.type)" v-model="assertion.expectedValue" :aria-label="`表格断言 ${index + 1} 期望值`" :placeholder="assertion.type === 'HEADER_EQUALS' ? '列名以 | 分隔' : '期望值'" />
        </template>
        <el-button :aria-label="`删除表格断言 ${index + 1}`" @click="verifier.tabularAssertions.splice(index, 1)">删除</el-button>
      </div>
    </template>
  </section>
</template>

<style scoped>
.artifact-assertions { display: grid; gap: 12px; min-width: 0; }
header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.assertion-row { display: flex; flex-wrap: wrap; gap: 8px; }
.assertion-row > :deep(.el-select), .assertion-row > :deep(.el-input) { flex: 1 1 180px; min-width: 0; }
</style>

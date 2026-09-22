<script setup lang="ts">
import { ref } from 'vue'
import SkinSelector from '@/components/SkinSelector.vue'
import CodeMergeEditor from '@/components/CodeMergeEditor.vue'
import MarkdownDocument from '@/components/MarkdownDocument.vue'
import StatusBadge from '@/components/StatusBadge.vue'
const text = ref('未保存的输入')
const code = ref('public class Skin {\n  // 保留内容和光标\n  String name = "Loopper";\n}')
const dialog = ref(false)
const choice = ref('一')
const markdown = '# 文档预览\n\n**重点文字**、[参考链接](https://example.com)、`行内代码`。\n\n> 引用内容\n\n| 状态 | 说明 |\n| --- | --- |\n| 就绪 | 可开始 |\n\n```java\nString name = "Loopper";\n```\n\n```mermaid\nflowchart LR\nA[需求] --> B[设计] --> C[交付]\n```'
</script>

<template>
<main class="content" style="max-width:1000px">
    <div class="toolbar"><h1>皮肤验收</h1><SkinSelector /></div>
    <section class="card card-pad">
      <div class="toolbar-group" style="flex-wrap:wrap;margin-bottom:16px">
        <el-button>普通操作</el-button><el-button type="primary" @click="dialog=true">打开弹窗</el-button>
        <el-button type="success">成功</el-button><el-button type="warning">警告</el-button><el-button type="danger">危险</el-button><el-button disabled>不可用</el-button>
      </div>
      <el-input v-model="text" aria-label="未保存输入" />
      <el-select v-model="choice" aria-label="示例选项"><el-option label="一" value="一"/><el-option label="二" value="二"/></el-select>
      <div class="toolbar-group" style="margin-top:16px"><StatusBadge status="RUNNING"/><StatusBadge status="SUCCEEDED"/><StatusBadge status="WAITING_INPUT"/><StatusBadge status="FAILED"/></div>
    </section>
    <section class="card card-pad" style="margin-top:20px"><MarkdownDocument :content="markdown" /></section>
    <section class="card" style="height:160px;margin-top:20px"><CodeMergeEditor v-model="code" language="java" :changed-lines="[2]" :conflict-lines="[3]" /></section>
    <el-dialog v-model="dialog" title="皮肤弹窗" width="min(90vw, 480px)"><p>弹窗继承全局皮肤。</p><el-input v-model="text" aria-label="弹窗输入"/><template #footer><el-button @click="dialog=false">关闭</el-button></template></el-dialog>
  </main>
</template>

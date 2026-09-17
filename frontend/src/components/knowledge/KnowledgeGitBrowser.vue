<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { knowledgeApi } from '@/api/knowledge'
import type { KnowledgeContent } from '@/types/domain'
import KnowledgeEvidence from './KnowledgeEvidence.vue'
const props = defineProps<{ project: string; source: string; conversationId?: string }>()
const author = ref(''), query = ref(''), busy = ref(false), error = ref(''), data = ref<KnowledgeContent>(), commit = ref<KnowledgeContent>()
async function load(tool = 'search_knowledge_git_commits', sha?: string, cursor?: string, line?: number) {
  if (busy.value) return
  busy.value = true; error.value = ''
  try {
    const result = await knowledgeApi.git(props.project, props.source, { conversationId: props.conversationId, tool, author: tool === 'search_knowledge_git_commits' ? author.value : undefined, query: tool === 'search_knowledge_git_commits' ? query.value : undefined, commit: sha, cursor, startLine: line })
    if (sha) commit.value = result
    else { data.value = cursor && data.value ? { ...result, items: [...(data.value.items as unknown[] || []), ...(result.items as unknown[] || [])] } : result; commit.value = undefined }
  } catch (failure) { error.value = failure instanceof Error && /[\u4e00-\u9fff]/.test(failure.message) ? failure.message : 'Git 读取失败，请检查项目仓库' }
  finally { busy.value = false }
}
onMounted(() => { void load() })
</script>
<template>
  <section class="knowledge-git-browser" aria-label="Git 来源浏览">
    <form @submit.prevent="load()"><input v-model="author" aria-label="Git 作者" placeholder="作者姓名或邮箱" maxlength="200"><input v-model="query" aria-label="提交关键词" placeholder="提交关键词" maxlength="200"><button :disabled="busy">查询提交</button></form>
    <p v-if="error" role="alert" class="knowledge-notice">{{ error }}</p><p v-if="busy" role="status">正在读取 Git…</p>
    <template v-if="commit"><button @click="commit = undefined">返回提交列表</button><KnowledgeEvidence :body="commit" /><button v-if="Number(commit.nextLine) > 0" :disabled="busy" @click="load('read_knowledge_git_commit', String(commit.commit), undefined, Number(commit.nextLine))">下一段差异</button></template>
    <template v-else-if="data"><p class="knowledge-muted">{{ data.notice }}</p><button v-for="item in (data.items as Record<string, string>[] || [])" :key="item.sha" class="knowledge-git-commit" :disabled="busy" @click="load('read_knowledge_git_commit', item.sha)"><strong>{{ item.subject }}</strong><small>{{ item.author }} · {{ new Date(item.authoredAt!).toLocaleDateString() }} · {{ item.sha?.slice(0, 8) }}</small></button><p v-if="!(data.items as unknown[])?.length" class="knowledge-muted">当前范围没有匹配提交</p><button v-if="data.nextCursor" :disabled="busy" @click="load('search_knowledge_git_commits', undefined, String(data.nextCursor))">更多提交</button></template>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError } from '../api/http'
import { workspaceApi } from '../api/workspace'
import type { TrashedPage } from '../api/workspace'
import { useWorkspace } from '../stores/workspace'

/**
 * 워크스페이스의 휴지통 (docs/09-API.md 11.2). 휴지통에 가기 전에 볼 수 있던 페이지만 보인다. 꺼내기는 그때 FULL 이었던 사람만
 * 할 수 있다. 하위 페이지는 부모를 따라 함께 돌아온다.
 */
const props = defineProps<{ workspaceId: string }>()
const store = useWorkspace()
const router = useRouter()
const pages = ref<TrashedPage[]>([])
const error = ref('')

async function load() {
  pages.value = await workspaceApi.trash(props.workspaceId)
}

async function restore(page: TrashedPage) {
  error.value = ''
  try {
    const restored = await store.restorePage(page.id)
    await router.push(`/w/${props.workspaceId}/p/${restored.id}`)
  } catch (e) {
    error.value = e instanceof ApiError ? e.detail : String(e)
    await load()
  }
}

function when(at: string): string {
  return new Date(at).toLocaleString()
}

onMounted(load)
</script>

<template>
  <h1>휴지통</h1>
  <p v-if="pages.length === 0" class="empty" data-testid="trash-empty">휴지통이 비어 있다</p>
  <ul v-else class="trash" data-testid="trash">
    <li v-for="page in pages" :key="page.id" data-testid="trash-item" :data-page-id="page.id">
      <span class="trash-title" :class="{ untitled: !page.title }">{{ page.title || '제목 없음' }}</span>
      <span class="trash-when">{{ when(page.archivedAt) }}</span>
      <button v-if="page.canRestore" type="button" @click="restore(page)">꺼내기</button>
    </li>
  </ul>
  <p v-if="error" class="error" role="alert">{{ error }}</p>
</template>

<style scoped>
.trash {
  list-style: none;
  padding: 0;
}

.trash li {
  display: flex;
  gap: 12px;
  align-items: center;
  padding: 6px 0;
}

.trash-title {
  flex: 1;
}

.untitled {
  color: #888;
}

.trash-when {
  color: #888;
  font-size: 0.85em;
}
</style>

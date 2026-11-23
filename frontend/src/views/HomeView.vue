<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError, api } from '../api/http'

/**
 * 워크스페이스와 페이지 트리 화면(docs/10-FRONTEND.md)이 생기기 전까지 쓰는 임시 홈. 새 페이지를 만들거나 ID 로 연다. 문서는 곧
 * 페이지라서, 새 문서는 내 첫 워크스페이스(없으면 만든다)의 최상위 페이지로 만든다.
 */
const router = useRouter()
const pageId = ref('')
const error = ref('')

interface Workspace {
  id: string
}

async function create() {
  error.value = ''
  try {
    const workspaces = await api<Workspace[]>('/api/workspaces')
    const workspace =
      workspaces[0] ??
      (await api<Workspace>('/api/workspaces', {
        method: 'POST',
        body: JSON.stringify({ name: '내 워크스페이스' }),
      }))
    const page = await api<{ id: string }>(`/api/workspaces/${workspace.id}/pages`, {
      method: 'POST',
      body: JSON.stringify({}),
    })
    await router.push(`/p/${page.id}`)
  } catch (e) {
    error.value = e instanceof ApiError ? e.detail : String(e)
  }
}

function open() {
  void router.push(`/p/${pageId.value.trim()}`)
}
</script>

<template>
  <h1>문서</h1>
  <p><button type="button" @click="create">새 문서</button></p>
  <p v-if="error" role="alert">{{ error }}</p>
  <form @submit.prevent="open">
    <input v-model="pageId" placeholder="문서 ID" required />
    <button type="submit">열기</button>
  </form>
</template>

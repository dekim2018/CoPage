<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError } from '../api/http'
import type { Workspace } from '../api/workspace'
import { useWorkspace } from '../stores/workspace'

/**
 * 첫 화면. 페이지를 만들 수 있는(OWNER, MEMBER) 워크스페이스가 있으면 그리로 간다. 없으면 게스트로 있는 워크스페이스 목록과
 * 워크스페이스 만들기를 보인다.
 */
const store = useWorkspace()
const router = useRouter()
const guestOnly = ref<Workspace[]>([])
const ready = ref(false)
const name = ref('')
const error = ref('')

onMounted(async () => {
  const workspaces = await store.loadWorkspaces()
  const own = workspaces.find((workspace) => workspace.role !== 'GUEST')
  if (own !== undefined && router.currentRoute.value.query.create === undefined) {
    await router.replace(`/w/${own.id}`)
    return
  }
  guestOnly.value = workspaces.filter((workspace) => workspace.role === 'GUEST')
  ready.value = true
})

async function create() {
  error.value = ''
  try {
    const workspace = await store.create(name.value)
    await router.push(`/w/${workspace.id}`)
  } catch (e) {
    error.value = e instanceof ApiError ? e.detail : String(e)
  }
}
</script>

<template>
  <template v-if="ready">
    <h1>워크스페이스</h1>
    <ul v-if="guestOnly.length > 0">
      <li v-for="workspace in guestOnly" :key="workspace.id">
        <RouterLink :to="`/w/${workspace.id}`">{{ workspace.name }}</RouterLink> (게스트)
      </li>
    </ul>
    <form @submit.prevent="create">
      <input v-model="name" name="workspace-name" placeholder="새 워크스페이스 이름" maxlength="100" required />
      <button type="submit">워크스페이스 만들기</button>
      <p v-if="error" class="error">{{ error }}</p>
    </form>
  </template>
</template>

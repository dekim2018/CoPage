<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageTree from '../components/PageTree.vue'
import { useWorkspace } from '../stores/workspace'

/** 워크스페이스 화면의 틀. 왼쪽 사이드바(워크스페이스 고르기, 페이지 트리)와 오른쪽 내용 (docs/01-PLAN.md 3장). */
const props = defineProps<{ workspaceId: string }>()
const store = useWorkspace()
const route = useRoute()
const router = useRouter()
const loading = computed(() => store.current?.id !== props.workspaceId)
const canCreate = computed(() => store.current !== null && store.current.role !== 'GUEST')

watch(
  () => props.workspaceId,
  async (workspaceId) => {
    await store.open(workspaceId)
  },
  { immediate: true },
)

async function createRoot() {
  const page = await store.createPage(null)
  await router.push(`/w/${props.workspaceId}/p/${page.id}`)
}

function switchTo(event: Event) {
  void router.push(`/w/${(event.target as HTMLSelectElement).value}`)
}
</script>

<template>
  <div class="workspace">
    <aside class="sidebar" data-testid="sidebar">
      <select class="workspace-switch" :value="workspaceId" aria-label="워크스페이스" @change="switchTo">
        <option v-for="workspace in store.workspaces" :key="workspace.id" :value="workspace.id">{{ workspace.name }}</option>
      </select>
      <template v-if="store.current">
        <button v-if="canCreate" type="button" class="new-page" @click="createRoot">새 페이지</button>
        <PageTree :workspace-id="workspaceId" :parent-id="null" />
        <RouterLink v-if="canCreate" :to="`/w/${workspaceId}/members`" class="members-link">멤버</RouterLink>
        <RouterLink to="/" class="members-link">워크스페이스 만들기</RouterLink>
      </template>
    </aside>
    <section class="content">
      <p v-if="!loading && store.current === null" class="error" data-testid="page-error">워크스페이스를 열 수 없다</p>
      <!-- 페이지를 옮겨 다니면 편집기 연결을 새로 연다 -->
      <RouterView v-else :key="route.fullPath" />
    </section>
  </div>
</template>

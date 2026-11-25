<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import NotificationBell from '../components/NotificationBell.vue'
import PageTree from '../components/PageTree.vue'
import SearchBox from '../components/SearchBox.vue'
import { useWorkspace } from '../stores/workspace'

/** 워크스페이스 화면의 틀. 왼쪽 사이드바(워크스페이스 고르기, 페이지 트리)와 오른쪽 내용 (docs/01-PLAN.md 3장). */
const props = defineProps<{ workspaceId: string }>()
const store = useWorkspace()
const route = useRoute()
const router = useRouter()

/**
 * 다른 사람이 페이지를 만들거나 옮기거나 제목을 바꿨다 (W-5, D-46). 받은 부모의 목록과 그 부모가 들어 있는 목록(펼침 단추와
 * 제목)을 다시 읽는다. 읽은 적 없는 목록은 펼칠 때 읽으므로 건드리지 않는다.
 */
function onTree(workspaceId: string, parentIds: string[]) {
  if (store.current?.id !== workspaceId) {
    return
  }
  const reload = new Set<string | null>()
  for (const id of parentIds) {
    if (id === workspaceId) {
      reload.add(null)
      continue
    }
    if (store.childrenOf(id) !== undefined) {
      reload.add(id)
    }
    const node = store.findNode(id)
    if (node !== undefined) {
      reload.add(node.parentId)
    }
  }
  for (const parent of reload) {
    void store.loadChildren(parent)
  }
}
// 마지막으로 열기를 마친 워크스페이스. 열기가 끝났는데 current 가 비었으면 속하지 않았거나 없는 워크스페이스다.
const openedId = ref<string | null>(null)
const notFound = computed(() => openedId.value === props.workspaceId && store.current === null)
const canCreate = computed(() => store.current !== null && store.current.role !== 'GUEST')

watch(
  () => props.workspaceId,
  async (workspaceId) => {
    await store.open(workspaceId)
    openedId.value = workspaceId
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
      <NotificationBell @tree="onTree" />
      <select class="workspace-switch" :value="workspaceId" aria-label="워크스페이스" @change="switchTo">
        <option v-for="workspace in store.workspaces" :key="workspace.id" :value="workspace.id">{{ workspace.name }}</option>
      </select>
      <template v-if="store.current">
        <SearchBox :workspace-id="workspaceId" />
        <section v-if="store.favorites.length > 0" class="shortcuts" data-testid="favorites">
          <h3>즐겨찾기</h3>
          <RouterLink v-for="page in store.favorites" :key="page.id" :to="`/w/${workspaceId}/p/${page.id}`">
            {{ page.title || '제목 없음' }}
          </RouterLink>
        </section>
        <section v-if="store.recent.length > 0" class="shortcuts" data-testid="recent">
          <h3>최근 본 페이지</h3>
          <RouterLink v-for="page in store.recent" :key="page.id" :to="`/w/${workspaceId}/p/${page.id}`">
            {{ page.title || '제목 없음' }}
          </RouterLink>
        </section>
        <button v-if="canCreate" type="button" class="new-page" @click="createRoot">새 페이지</button>
        <PageTree :workspace-id="workspaceId" :parent-id="null" />
        <p v-if="store.moveError" class="error" data-testid="move-error">{{ store.moveError }}</p>
        <RouterLink v-if="canCreate" :to="`/w/${workspaceId}/members`" class="members-link">멤버</RouterLink>
        <RouterLink v-if="canCreate" :to="`/w/${workspaceId}/trash`" class="members-link">휴지통</RouterLink>
        <!-- create 가 없으면 첫 화면이 곧바로 내 워크스페이스로 되돌려 보낸다 -->
        <RouterLink :to="{ path: '/', query: { create: '1' } }" class="members-link">워크스페이스 만들기</RouterLink>
        <RouterLink :to="`/w/${workspaceId}/profile`" class="members-link" data-testid="profile-link">내 프로필</RouterLink>
      </template>
    </aside>
    <section class="content">
      <p v-if="notFound" class="error" data-testid="page-error">워크스페이스를 열 수 없다</p>
      <!-- 페이지를 옮겨 다니면 편집기 연결을 새로 연다 -->
      <RouterView v-else-if="store.current" :key="route.fullPath" />
    </section>
  </div>
</template>

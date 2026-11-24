<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { canEdit } from '../collab/client/role'
import { useWorkspace } from '../stores/workspace'

/** 사이드바의 페이지 트리 한 단계. 펼친 페이지는 자기 자신을 다시 그린다 (8.6: 펼칠 때 한 단계씩 읽는다). */
const props = defineProps<{ workspaceId: string; parentId: string | null }>()
const store = useWorkspace()
const route = useRoute()
const router = useRouter()
const nodes = computed(() => store.childrenOf(props.parentId) ?? [])

async function addChild(parentId: string) {
  const page = await store.createPage(parentId)
  await router.push(`/w/${props.workspaceId}/p/${page.id}`)
}

/**
 * 휴지통으로 보낸다. 휴지통에서 꺼낼 수 있어 묻지 않는다. 열어 둔 페이지면 워크스페이스 첫 화면으로 간다. 하위 페이지를 열어
 * 두었으면 그 편집기는 권한을 다시 계산해 닫힌다.
 */
async function trash(pageId: string) {
  await store.trashPage(pageId, props.parentId)
  if (route.params.pageId === pageId) {
    await router.push(`/w/${props.workspaceId}`)
  }
}
</script>

<template>
  <ul class="tree">
    <li v-for="node in nodes" :key="node.id" data-testid="tree-item" :data-page-id="node.id">
      <div class="tree-row" :class="{ active: route.params.pageId === node.id }">
        <button
          type="button"
          class="tree-toggle"
          :class="{ empty: !node.hasChildren }"
          :aria-label="store.expanded.has(node.id) ? '접기' : '펼치기'"
          @click="store.toggle(node.id)"
        >
          {{ store.expanded.has(node.id) ? '▾' : '▸' }}
        </button>
        <RouterLink :to="`/w/${workspaceId}/p/${node.id}`" class="tree-title" :class="{ untitled: !node.title }">
          {{ node.title || '제목 없음' }}
        </RouterLink>
        <button v-if="canEdit(node.level)" type="button" class="tree-add" title="하위 페이지 만들기" @click="addChild(node.id)">
          +
        </button>
        <button v-if="node.level === 'FULL'" type="button" class="tree-add" title="휴지통으로 보내기" @click="trash(node.id)">
          ×
        </button>
      </div>
      <template v-if="store.expanded.has(node.id)">
        <PageTree :workspace-id="workspaceId" :parent-id="node.id" />
        <!-- hasChildren 은 권한을 보지 않고 세므로(D-12) 펼쳐도 볼 수 있는 하위가 없을 수 있다 -->
        <p v-if="(store.childrenOf(node.id) ?? []).length === 0" class="tree-empty">하위 페이지 없음</p>
      </template>
    </li>
  </ul>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError } from '../api/http'
import { canEdit } from '../collab/client/role'
import { useWorkspace } from '../stores/workspace'
import type { PageNode } from '../api/workspace'

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

/** 끌어 옮기는 페이지 ID 를 싣는 형식 (W-3). */
const PAGE_TYPE = 'application/x-copage-page'

type Zone = 'before' | 'inside' | 'after'
/** 끌고 있는 것이 지금 어느 줄의 어디에 놓이려는지. 줄의 위쪽 4분의 1 은 앞, 아래쪽 4분의 1 은 뒤, 가운데는 안(맨 뒤) */
const target = ref<{ id: string; zone: Zone } | null>(null)

function onDragStart(event: DragEvent, node: PageNode) {
  event.dataTransfer?.setData(PAGE_TYPE, node.id)
  event.dataTransfer?.setData('text/plain', node.title || '제목 없음')
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
  }
}

function onDragOver(event: DragEvent, node: PageNode, index: number) {
  if (!event.dataTransfer?.types.includes(PAGE_TYPE)) {
    return
  }
  event.preventDefault()
  const row = event.currentTarget as HTMLElement
  const ratio = (event.clientY - row.getBoundingClientRect().top) / row.offsetHeight
  let zone: Zone = ratio < 0.25 ? 'before' : ratio > 0.75 ? 'after' : 'inside'
  // 맨 앞에 두는 API 는 없다(afterId 가 없으면 맨 뒤다). 첫 줄의 위쪽은 안으로 넣는다.
  if (zone === 'before' && index === 0) {
    zone = 'inside'
  }
  if (zone === 'inside' && !canEdit(node.level)) {
    zone = 'after'
  }
  target.value = { id: node.id, zone }
}

async function onDrop(event: DragEvent, node: PageNode, index: number) {
  const pageId = event.dataTransfer?.getData(PAGE_TYPE)
  const zone = target.value?.zone
  target.value = null
  if (!pageId || zone === undefined || pageId === node.id) {
    return
  }
  event.preventDefault()
  const [parentId, afterId] =
    zone === 'inside'
      ? [node.id, null]
      : zone === 'after'
        ? [props.parentId, node.id]
        : [props.parentId, nodes.value[index - 1]?.id ?? null]
  if (afterId === pageId) {
    return
  }
  try {
    store.moveError = null
    await store.movePage(pageId, parentId, afterId)
  } catch (e) {
    store.moveError = e instanceof ApiError && e.status === 400 ? '자기 하위로는 옮길 수 없다' : '옮기지 못했다'
  }
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
    <li v-for="(node, index) in nodes" :key="node.id" data-testid="tree-item" :data-page-id="node.id">
      <div
        class="tree-row"
        data-testid="tree-row"
        :class="{
          active: route.params.pageId === node.id,
          [`drop-${target?.zone}`]: target?.id === node.id,
        }"
        :draggable="node.level === 'FULL'"
        @dragstart="onDragStart($event, node)"
        @dragover="onDragOver($event, node, index)"
        @dragleave="target = target?.id === node.id ? null : target"
        @drop="onDrop($event, node, index)"
        @dragend="target = null"
      >
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

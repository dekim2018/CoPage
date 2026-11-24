<script setup lang="ts">
import type { Editor } from '@tiptap/vue-3'
import { onBeforeUnmount, onMounted, ref } from 'vue'
import type { EditorBinding } from '../collab/adapter/editorBinding'

/**
 * 블록 끌어 옮기기 (E-5). 블록 위에 마우스를 올리면 왼쪽에 손잡이가 보인다. 손잡이를 끌어 다른 블록 위에 놓으면, 그 블록의
 * 위쪽 반이면 앞에, 아래쪽 반이면 뒤에 같은 부모 아래로 옮긴다. 자식 블록은 따라간다. 제목 줄은 옮기지 않는다.
 */
const props = defineProps<{ editor: Editor; binding: EditorBinding }>()
const BLOCK_MIME = 'application/x-copage-block'

const handle = ref<{ blockId: string; top: number; left: number } | null>(null)
const indicator = ref<{ top: number; left: number; width: number } | null>(null)
let dragging: string | null = null
let host: HTMLElement | null = null

function blockElement(target: EventTarget | null): HTMLElement | null {
  const root = props.editor.view.dom
  let element = target instanceof HTMLElement ? target : null
  while (element !== null && element.parentElement !== root) {
    element = element.parentElement
  }
  // 첫 줄은 제목(루트 블록)이다.
  if (element === null || element === root.firstElementChild || !element.dataset.blockId) {
    return null
  }
  return element
}

function onMove(event: MouseEvent) {
  if (dragging !== null) {
    return
  }
  const element = blockElement(event.target)
  if (element === null || host === null) {
    return
  }
  const box = element.getBoundingClientRect()
  const base = host.getBoundingClientRect()
  handle.value = { blockId: element.dataset.blockId as string, top: box.top - base.top, left: box.left - base.left - 24 }
}

function onDragStart(event: DragEvent) {
  if (handle.value === null || event.dataTransfer === null) {
    return
  }
  dragging = handle.value.blockId
  event.dataTransfer.effectAllowed = 'move'
  event.dataTransfer.setData(BLOCK_MIME, dragging)
}

function target(event: DragEvent): { element: HTMLElement; after: boolean } | null {
  const element = blockElement(event.target)
  if (element === null) {
    return null
  }
  const box = element.getBoundingClientRect()
  return { element, after: event.clientY > box.top + box.height / 2 }
}

// 편집기(ProseMirror)도 놓기를 받아 글로 넣으려 한다. 블록을 끄는 중이면 먼저 가로챈다.
function onDragOver(event: DragEvent) {
  if (dragging === null || host === null) {
    return
  }
  event.preventDefault()
  event.stopPropagation()
  const place = target(event)
  if (place === null) {
    indicator.value = null
    return
  }
  const box = place.element.getBoundingClientRect()
  const base = host.getBoundingClientRect()
  indicator.value = {
    top: (place.after ? box.bottom : box.top) - base.top,
    left: box.left - base.left,
    width: box.width,
  }
}

function onDrop(event: DragEvent) {
  if (dragging === null) {
    return
  }
  event.preventDefault()
  event.stopPropagation()
  const place = target(event)
  const moving = dragging
  end()
  if (place !== null) {
    props.binding.moveBlock(moving, place.element.dataset.blockId as string, place.after)
  }
}

function end() {
  dragging = null
  indicator.value = null
}

onMounted(() => {
  host = props.editor.view.dom.parentElement
  host?.addEventListener('mousemove', onMove)
  host?.addEventListener('dragover', onDragOver, true)
  host?.addEventListener('drop', onDrop, true)
  host?.addEventListener('dragend', end)
})

onBeforeUnmount(() => {
  host?.removeEventListener('mousemove', onMove)
  host?.removeEventListener('dragover', onDragOver, true)
  host?.removeEventListener('drop', onDrop, true)
  host?.removeEventListener('dragend', end)
})
</script>

<template>
  <Teleport v-if="handle" :to="editor.view.dom.parentElement">
    <span
      class="drag-handle"
      draggable="true"
      title="끌어서 옮기기"
      data-testid="drag-handle"
      :data-block-id="handle.blockId"
      contenteditable="false"
      :style="{ top: `${handle.top}px`, left: `${handle.left}px` }"
      @dragstart="onDragStart"
      >⋮⋮</span
    >
    <span
      v-if="indicator"
      class="drop-line"
      :style="{ top: `${indicator.top}px`, left: `${indicator.left}px`, width: `${indicator.width}px` }"
    />
  </Teleport>
</template>

<style scoped>
.drag-handle {
  position: absolute;
  z-index: 5;
  cursor: grab;
  color: #999;
  user-select: none;
  line-height: 1.6;
}

.drop-line {
  position: absolute;
  z-index: 5;
  height: 2px;
  background: #4f7cff;
  pointer-events: none;
}
</style>

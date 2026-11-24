<script setup lang="ts">
import type { Editor } from '@tiptap/vue-3'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { matchChoices } from '../collab/adapter/blockChoices'
import type { BlockChoice } from '../collab/adapter/blockChoices'
import type { EditorBinding } from '../collab/adapter/editorBinding'

/**
 * 슬래시(/) 메뉴 (E-6). 빈 블록에 "/" 를 치면 블록 종류 목록이 커서 아래에 뜨고, 이어 치는 말로 좁힌다. 위아래 화살표로 고르고
 * Enter 로 바꾼다. Esc 나 다른 곳을 누르면 닫힌다(친 글자는 그대로 남는다).
 */
const props = defineProps<{ editor: Editor; binding: EditorBinding }>()
const state = ref<ReturnType<EditorBinding['slashQuery']>>(null)
const active = ref(0)
// Esc 로 닫은 뒤에는 그 글자가 남아 있어도 다시 열지 않는다. 글이 바뀌면 다시 본다.
const dismissedFor = ref<string | null>(null)
const items = computed<BlockChoice[]>(() => (state.value === null ? [] : matchChoices(state.value.query)))
const open = computed(() => state.value !== null && items.value.length > 0 && dismissedFor.value !== state.value.query)

function refresh() {
  const next = props.binding.slashQuery()
  if (next === null || next.query !== state.value?.query) {
    active.value = 0
  }
  if (next === null) {
    dismissedFor.value = null
  }
  state.value = next
}

function choose(choice: BlockChoice) {
  props.binding.applySlash(choice.type, choice.props)
  state.value = null
}

// 편집기보다 먼저 키를 받아야 Enter 가 블록을 나누지 않는다. 편집기의 부모에서 붙잡는다.
function onKey(event: KeyboardEvent) {
  if (!open.value) {
    return
  }
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    const step = event.key === 'ArrowDown' ? 1 : -1
    active.value = (active.value + step + items.value.length) % items.value.length
  } else if (event.key === 'Enter') {
    const choice = items.value[active.value]
    if (choice !== undefined) {
      choose(choice)
    }
  } else if (event.key === 'Escape') {
    dismissedFor.value = state.value?.query ?? null
  } else {
    return
  }
  event.preventDefault()
  event.stopPropagation()
}

let host: HTMLElement | null = null

onMounted(() => {
  props.editor.on('transaction', refresh)
  props.editor.on('selectionUpdate', refresh)
  host = props.editor.view.dom.parentElement
  host?.addEventListener('keydown', onKey, true)
})

onBeforeUnmount(() => {
  props.editor.off('transaction', refresh)
  props.editor.off('selectionUpdate', refresh)
  host?.removeEventListener('keydown', onKey, true)
})
</script>

<template>
  <ul
    v-if="open && state"
    class="slash-menu"
    role="listbox"
    data-testid="slash-menu"
    :style="{ left: `${state.left}px`, top: `${state.bottom + 4}px` }"
  >
    <li
      v-for="(choice, i) in items"
      :key="choice.label"
      role="option"
      :aria-selected="i === active"
      :class="{ active: i === active }"
      @mousedown.prevent="choose(choice)"
    >
      {{ choice.label }}
    </li>
  </ul>
</template>

<style scoped>
.slash-menu {
  position: fixed;
  z-index: 20;
  min-width: 180px;
  max-height: 280px;
  overflow-y: auto;
  margin: 0;
  padding: 4px 0;
  list-style: none;
  background: #fff;
  border: 1px solid #ddd;
  border-radius: 6px;
  box-shadow: 0 4px 16px rgb(0 0 0 / 12%);
}

.slash-menu li {
  padding: 6px 12px;
  cursor: pointer;
}

.slash-menu li.active {
  background: #eef2ff;
}
</style>

<script setup lang="ts">
import type { Editor } from '@tiptap/vue-3'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { EditorBinding } from '../collab/adapter/editorBinding'

/** 본문 멘션 (C-4). "@" 뒤에 이름을 치면 사람 목록이 뜨고, 고르면 "@이름" 이 멘션 서식으로 들어간다. 서버가 그 사람에게 알린다. */
const props = defineProps<{ editor: Editor; binding: EditorBinding; people: { userId: string; name: string }[] }>()
const state = ref<ReturnType<EditorBinding['mentionQuery']>>(null)
const active = ref(0)
const dismissed = ref<string | null>(null)
const items = computed(() => {
  const q = state.value?.query.toLowerCase() ?? ''
  return props.people.filter((person) => person.name.toLowerCase().includes(q)).slice(0, 8)
})
const open = computed(() => state.value !== null && items.value.length > 0 && dismissed.value !== `${state.value.from}`)

function refresh() {
  const next = props.binding.mentionQuery()
  if (next === null || next.query !== state.value?.query) {
    active.value = 0
  }
  if (next === null) {
    dismissed.value = null
  }
  state.value = next
}

function choose(person: { userId: string; name: string }) {
  if (state.value !== null) {
    props.binding.insertMention(state.value.from, state.value.to, person.userId, person.name)
  }
  state.value = null
}

function onKey(event: KeyboardEvent) {
  if (!open.value) {
    return
  }
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    const step = event.key === 'ArrowDown' ? 1 : -1
    active.value = (active.value + step + items.value.length) % items.value.length
  } else if (event.key === 'Enter') {
    const person = items.value[active.value]
    if (person !== undefined) {
      choose(person)
    }
  } else if (event.key === 'Escape') {
    dismissed.value = `${state.value?.from}`
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
    class="mention-menu"
    role="listbox"
    data-testid="mention-menu"
    :style="{ left: `${state.left}px`, top: `${state.bottom + 4}px` }"
  >
    <li
      v-for="(person, i) in items"
      :key="person.userId"
      role="option"
      :aria-selected="i === active"
      :class="{ active: i === active }"
      @mousedown.prevent="choose(person)"
    >
      {{ person.name }}
    </li>
  </ul>
</template>

<style scoped>
.mention-menu {
  position: fixed;
  z-index: 20;
  min-width: 160px;
  margin: 0;
  padding: 4px 0;
  list-style: none;
  background: #fff;
  border: 1px solid #ddd;
  border-radius: 6px;
  box-shadow: 0 4px 16px rgb(0 0 0 / 12%);
}

.mention-menu li {
  padding: 6px 12px;
  cursor: pointer;
}

.mention-menu li.active {
  background: #eef2ff;
}
</style>

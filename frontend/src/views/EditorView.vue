<script setup lang="ts">
import { Editor, EditorContent } from '@tiptap/vue-3'
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { api } from '../api/http'
import { EditorBinding, keyboardExtension } from '../collab/adapter/editorBinding'
import { schemaExtensions } from '../collab/adapter/schema'
import { CollabClient } from '../collab/client/collabClient'
import type { SyncStatus } from '../collab/client/collabClient'

const props = defineProps<{ pageId: string }>()

const editor = shallowRef<Editor | undefined>(undefined)
const status = ref<SyncStatus>('connecting')
const detail = ref('')
// "저장 중" 은 2초 넘게 이어질 때만 보여 준다 (docs/10-FRONTEND.md 12.5).
const savingSince = ref<number | null>(null)
const now = ref(Date.now())
let timer: ReturnType<typeof setInterval> | null = null
let binding: EditorBinding | null = null
let client: CollabClient | null = null

const label = computed(() => {
  switch (status.value) {
    case 'connecting':
      return '연결 중'
    case 'saved':
      return '저장됨'
    case 'saving':
      return savingSince.value !== null && now.value - savingSince.value > 2000 ? '저장 중' : '저장됨'
    case 'offline':
      return '오프라인'
    case 'error':
      return `연결할 수 없다 (${detail.value})`
  }
  return ''
})

onMounted(() => {
  const instance = new Editor({
    extensions: [...schemaExtensions, keyboardExtension(() => binding)],
    editable: false,
  })
  editor.value = instance
  client = new CollabClient(
    props.pageId,
    async () => (await api<{ ticket: string }>(`/api/docs/${props.pageId}/ws-ticket`, { method: 'POST' })).ticket,
    {
      onRemote: () => {
        instance.setEditable(true)
        binding?.render()
      },
      onStatus: (next, why) => {
        if (next === 'saving' && status.value !== 'saving') {
          savingSince.value = Date.now()
        }
        if (next !== 'saving') {
          savingSince.value = null
        }
        status.value = next
        detail.value = why ?? ''
      },
    },
  )
  binding = new EditorBinding(instance, client)
  client.start()
  timer = setInterval(() => (now.value = Date.now()), 500)
})

onBeforeUnmount(() => {
  client?.stop()
  binding?.destroy()
  editor.value?.destroy()
  if (timer !== null) {
    clearInterval(timer)
  }
})
</script>

<template>
  <p class="status" data-testid="status" :data-status="status">{{ label }}</p>
  <EditorContent :editor="editor" />
</template>

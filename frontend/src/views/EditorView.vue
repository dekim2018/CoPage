<script setup lang="ts">
import { Editor, EditorContent } from '@tiptap/vue-3'
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError, api } from '../api/http'
import { EditorBinding, keyboardExtension } from '../collab/adapter/editorBinding'
import { schemaExtensions } from '../collab/adapter/schema'
import { CollabClient } from '../collab/client/collabClient'
import type { SyncStatus } from '../collab/client/collabClient'
import type { Peer } from '../collab/client/presence'
import { RemoteCursors } from '../collab/adapter/remoteCursors'
import { FatalConnectionError } from '../collab/transport/connection'
import EditorToolbar from '../components/EditorToolbar.vue'

const props = defineProps<{ pageId: string }>()
const router = useRouter()

/**
 * 연결할 때마다 티켓을 받는다. 401(로그인이 끝남), 403(접근 권한 없음), 404 는 다시 시도해도 소용없으니 재접속을 멈춘다. 401 이면
 * 로그인 화면으로 보낸다. 그 밖의 실패(서버에 닿지 못함 등)는 연결이 재접속을 이어 간다.
 */
async function ticket(): Promise<string> {
  try {
    return (await api<{ ticket: string }>(`/api/docs/${props.pageId}/ws-ticket`, { method: 'POST' })).ticket
  } catch (e) {
    if (e instanceof ApiError && [401, 403, 404].includes(e.status)) {
      if (e.status === 401) {
        void router.push({ path: '/login', query: { next: `/p/${props.pageId}` } })
      }
      throw new FatalConnectionError(`${e.status} ${e.detail}`)
    }
    throw e
  }
}

const editor = shallowRef<Editor | undefined>(undefined)
const status = ref<SyncStatus>('connecting')
const detail = ref('')
const peers = shallowRef<readonly Peer[]>([])
// "저장 중" 은 2초 넘게 이어질 때만 보여 준다 (docs/10-FRONTEND.md 12.5).
const savingSince = ref<number | null>(null)
const now = ref(Date.now())
let timer: ReturnType<typeof setInterval> | null = null
let binding: EditorBinding | null = null
const bindingRef = shallowRef<EditorBinding | null>(null)
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
    extensions: [...schemaExtensions, RemoteCursors, keyboardExtension(() => binding)],
    editable: false,
  })
  editor.value = instance
  client = new CollabClient(
    props.pageId,
    ticket,
    {
      onRemote: () => {
        instance.setEditable(true)
        binding?.render()
      },
      onPeers: (next) => {
        peers.value = next
        binding?.setPeers(next)
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
  bindingRef.value = binding
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
  <div class="editor-head">
    <p class="status" data-testid="status" :data-status="status">{{ label }}</p>
    <ul class="peers" data-testid="peers">
      <li v-for="peer in peers" :key="peer.clientId" :style="{ backgroundColor: peer.color }" :title="peer.name">
        {{ peer.name }}
      </li>
    </ul>
  </div>
  <EditorToolbar v-if="editor && bindingRef" :editor="editor" :binding="bindingRef" />
  <EditorContent :editor="editor" />
</template>

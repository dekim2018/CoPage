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
import { canEdit } from '../collab/client/role'
import type { PageRole } from '../collab/client/role'
import { RemoteCursors } from '../collab/adapter/remoteCursors'
import { FatalConnectionError } from '../collab/transport/connection'
import EditorToolbar from '../components/EditorToolbar.vue'
import ShareDialog from '../components/ShareDialog.vue'
import { workspaceApi } from '../api/workspace'
import type { PageDetail } from '../api/workspace'
import { useWorkspace } from '../stores/workspace'

const props = defineProps<{ workspaceId: string; pageId: string }>()
const router = useRouter()
const workspace = useWorkspace()
// 경로(볼 수 있는 조상)와 제한 여부. 연결과 따로 읽는다. 읽지 못해도 편집은 된다.
const pageInfo = ref<PageDetail | null>(null)
const sharing = ref(false)

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
        void router.push({ path: '/login', query: { next: `/w/${props.workspaceId}/p/${props.pageId}` } })
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
// 서버가 준 내 등급. 첫 welcome 전에는 null 이라 편집할 수 없다. 연결 중에 내려가면 role 메시지로 바뀐다 (S-5).
const role = ref<PageRole | null>(null)
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
  // 제목 줄을 고치면 사이드바에도 바로 보인다. 서버는 커밋 뒤 따로 저장한다(D-21).
  instance.on('update', () => {
    workspace.setTitle(props.pageId, instance.state.doc.firstChild?.textContent.trim() ?? '')
  })
  workspaceApi.page(props.pageId).then(
    (page) => (pageInfo.value = page),
    () => (pageInfo.value = null),
  )
  client = new CollabClient(
    props.pageId,
    ticket,
    {
      onRemote: () => {
        instance.setEditable(canEdit(role.value))
        binding?.render()
      },
      onRole: (next) => {
        role.value = next
        instance.setEditable(canEdit(next))
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
  <nav v-if="pageInfo && pageInfo.path.length > 0" class="crumbs" data-testid="crumbs">
    <template v-for="ancestor in pageInfo.path" :key="ancestor.id">
      <RouterLink :to="`/w/${workspaceId}/p/${ancestor.id}`">{{ ancestor.title || '제목 없음' }}</RouterLink>
      <span> / </span>
    </template>
  </nav>
  <div class="editor-head">
    <p class="status" data-testid="status" :data-status="status">{{ label }}</p>
    <p v-if="role !== null && !canEdit(role)" class="readonly" data-testid="readonly">읽기 전용</p>
    <button v-if="role === 'FULL'" type="button" class="share-button" @click="sharing = !sharing">공유</button>
    <ul class="peers" data-testid="peers">
      <li v-for="peer in peers" :key="peer.clientId" :style="{ backgroundColor: peer.color }" :title="peer.name">
        {{ peer.name }}
      </li>
    </ul>
  </div>
  <ShareDialog
    v-if="sharing && pageInfo"
    :page-id="pageId"
    :restricted="pageInfo.restricted"
    @close="sharing = false"
    @restricted-changed="(value) => pageInfo && (pageInfo.restricted = value)"
  />
  <EditorToolbar v-if="editor && bindingRef && canEdit(role)" :editor="editor" :binding="bindingRef" />
  <EditorContent :editor="editor" />
</template>

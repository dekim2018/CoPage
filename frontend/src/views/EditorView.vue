<script setup lang="ts">
import { Editor, EditorContent } from '@tiptap/vue-3'
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError, api } from '../api/http'
import { EditorBinding, keyboardExtension } from '../collab/adapter/editorBinding'
import { schemaExtensions } from '../collab/adapter/schema'
import { CollabClient } from '../collab/client/collabClient'
import { openOfflineStore } from '../collab/client/offlineStore'
import type { LostEdits, SyncStatus } from '../collab/client/collabClient'
import type { Peer } from '../collab/client/presence'
import { canEdit } from '../collab/client/role'
import type { PageRole } from '../collab/client/role'
import { RemoteCursors } from '../collab/adapter/remoteCursors'
import { FatalConnectionError } from '../collab/transport/connection'
import EditorToolbar from '../components/EditorToolbar.vue'
import ShareDialog from '../components/ShareDialog.vue'
import { workspaceApi } from '../api/workspace'
import type { PageDetail } from '../api/workspace'
import { useAuth } from '../stores/auth'
import { useWorkspace } from '../stores/workspace'

/** shareToken 이 있으면 공유 링크로 연 화면이다(D-41). 사이드바, 경로, 공유 단추 없이 그 페이지만 연다. */
const props = defineProps<{ workspaceId: string; pageId: string; shareToken?: string }>()
const router = useRouter()
const workspace = useWorkspace()
const auth = useAuth()
// 경로(볼 수 있는 조상)와 제한 여부. 연결과 따로 읽는다. 읽지 못해도 편집은 된다.
const pageInfo = ref<PageDetail | null>(null)

// 이 탭에서 이 페이지의 조상을 휴지통으로 보냈다. 편집기는 권한을 다시 계산해 닫히므로 오류 화면에 남지 않고 첫 화면으로 간다.
watch(
  () => pageInfo.value?.path.some((ancestor) => workspace.trashed.has(ancestor.id)) ?? false,
  (trashedAbove) => {
    if (trashedAbove) {
      void router.push(`/w/${props.workspaceId}`)
    }
  },
)
// 살리지 못한 내 편집 (DC-3). 닫을 때까지 쌓아 보인다.
const lost = ref<LostEdits[]>([])
const lostTextAll = computed(() => lost.value.flatMap((item) => item.texts).join('\n'))

const LOST_REASONS: Record<string, string> = {
  rejected: '다른 사람의 편집과 겹쳐',
  permission: '권한이 없어',
  input: '허용하지 않는 입력이라',
  empty: '빈 편집이라',
  stale: '오래 연결이 끊겨 있어',
}

async function copyLost() {
  await navigator.clipboard.writeText(lostTextAll.value)
}
const sharing = ref(false)

/**
 * 연결할 때마다 티켓을 받는다. 401(로그인이 끝남), 403(접근 권한 없음), 404 는 다시 시도해도 소용없으니 재접속을 멈춘다. 401 이면
 * 로그인 화면으로 보낸다. 그 밖의 실패(서버에 닿지 못함 등)는 연결이 재접속을 이어 간다.
 */
async function ticket(): Promise<string> {
  const path = props.shareToken
    ? `/api/share/${encodeURIComponent(props.shareToken)}/ws-ticket`
    : `/api/docs/${props.pageId}/ws-ticket`
  try {
    return (await api<{ ticket: string }>(path, { method: 'POST' })).ticket
  } catch (e) {
    if (e instanceof ApiError && [401, 403, 404].includes(e.status)) {
      if (e.status === 401 && !props.shareToken) {
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

// 화면이 열리기 전에 떠났으면 연결을 만들지 않는다(보관소를 여는 동안 기다린다).
let unmounted = false

onMounted(async () => {
  // 보내지 못한 편집을 이 브라우저에 남긴다 (12.4, R-6). 공유 링크로 연 화면은 남기지 않는다.
  const user = auth.user
  const store = user !== null && !props.shareToken ? await openOfflineStore(user.id, props.pageId) : null
  if (unmounted) {
    store?.release()
    return
  }
  const instance = new Editor({
    extensions: [...schemaExtensions, RemoteCursors, keyboardExtension(() => binding)],
    editable: false,
  })
  editor.value = instance
  // 제목 줄을 고치면 사이드바에도 바로 보인다. 서버는 커밋 뒤 따로 저장한다(D-21).
  if (!props.shareToken) {
    instance.on('update', () => {
      workspace.setTitle(props.pageId, instance.state.doc.firstChild?.textContent.trim() ?? '')
    })
    workspaceApi.page(props.pageId).then(
      (page) => (pageInfo.value = page),
      () => (pageInfo.value = null),
    )
  }
  client = new CollabClient(
    props.pageId,
    ticket,
    {
      onRemote: () => {
        instance.setEditable(canEdit(role.value))
        binding?.render()
      },
      onLost: (item) => {
        lost.value = [...lost.value, item]
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
    undefined,
    store,
  )
  binding = new EditorBinding(instance, client)
  bindingRef.value = binding
  client.start()
  timer = setInterval(() => (now.value = Date.now()), 500)
})

onBeforeUnmount(() => {
  unmounted = true
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
      <RouterLink :to="`/w/${workspaceId}/p/${ancestor.id}`">{{
        (workspace.editedTitle(ancestor.id) ?? ancestor.title) || '제목 없음'
      }}</RouterLink>
      <span> / </span>
    </template>
  </nav>
  <div class="editor-head">
    <p class="status" data-testid="status" :data-status="status">{{ label }}</p>
    <p v-if="role !== null && !canEdit(role)" class="readonly" data-testid="readonly">읽기 전용</p>
    <button v-if="role === 'FULL' && !shareToken" type="button" class="share-button" @click="sharing = !sharing">
      공유
    </button>
    <ul class="peers" data-testid="peers">
      <li v-for="peer in peers" :key="peer.clientId" :style="{ backgroundColor: peer.color }" :title="peer.name">
        {{ peer.name }}
      </li>
    </ul>
  </div>
  <aside v-if="lost.length > 0" class="lost" role="status" data-testid="lost">
    <p>
      반영하지 못한 편집이 있다:
      <span v-for="(item, i) in lost" :key="i">{{ LOST_REASONS[item.reason] ?? item.reason }} {{ item.count }}건 </span>
    </p>
    <pre v-if="lostTextAll" data-testid="lost-text">{{ lostTextAll }}</pre>
    <button v-if="lostTextAll" type="button" @click="copyLost">글 복사</button>
    <button type="button" @click="lost = []">닫기</button>
  </aside>
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

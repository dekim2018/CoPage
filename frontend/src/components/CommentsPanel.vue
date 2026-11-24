<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { commentsApi } from '../api/comments'
import type { CommentThread } from '../api/comments'
import { ApiError } from '../api/http'
import type { EditorBinding } from '../collab/adapter/editorBinding'

/**
 * 댓글 (C-1~C-4, D-46). 내 댓글은 고치고 지운다. 전체 권한이 있으면 남의 댓글도 지운다(D-49). 스레드의 마지막 댓글을 지우면
 * 스레드가 사라지고 본문의 댓글 서식을 걷는다.
 * 본문에서 글자를 고르고 "고른 글에 댓글" 을 누르면 스레드를 만들고 그 글자에 댓글 서식을 붙인다. 답글을
 * 달고, 해결하고, 다시 연다. 글에 "@이름" 을 쓰면 그 사람에게 알린다. 다른 사람이 댓글을 바꾸면 서버가 알려 다시 읽는다.
 */
const props = defineProps<{
  docId: string
  binding: EditorBinding
  canComment: boolean
  canDeleteAny: boolean
  myId: string
  people: { userId: string; name: string }[]
}>()
const emit = defineEmits<{ close: [] }>()
const threads = ref<CommentThread[]>([])
const showResolved = ref(false)
const draft = ref<{ from: number; to: number; quote: string } | null>(null)
const body = ref('')
const replies = ref<Record<string, string>>({})
const error = ref('')
const visible = computed(() => threads.value.filter((thread) => showResolved.value || !thread.resolved))

async function load() {
  threads.value = await commentsApi.threads(props.docId)
}

async function run(action: () => Promise<unknown>) {
  error.value = ''
  try {
    await action()
    await load()
  } catch (e) {
    error.value = e instanceof ApiError ? e.detail : String(e)
  }
}

/** 글에서 "@이름" 을 찾아 그 사람들의 ID 로. 이름이 긴 사람부터 맞춘다(겹치는 이름). */
function mentionsIn(text: string): string[] {
  const found = new Set<string>()
  for (const person of [...props.people].sort((a, b) => b.name.length - a.name.length)) {
    if (text.includes(`@${person.name}`)) {
      found.add(person.userId)
    }
  }
  return [...found]
}

// 편집기의 고른 글이 풀리지 않게 누르는 순간(mousedown)에 잡는다.
function startDraft() {
  const selection = props.binding.selectionForComment()
  if (selection === null) {
    error.value = '댓글을 달 글자를 한 블록 안에서 고른다'
    return
  }
  error.value = ''
  draft.value = selection
}

function create() {
  const selection = draft.value
  if (selection === null) {
    return
  }
  void run(async () => {
    const thread = await commentsApi.create(props.docId, selection.quote, body.value, mentionsIn(body.value))
    props.binding.addCommentMark(selection.from, selection.to, thread.id)
    draft.value = null
    body.value = ''
  })
}

function reply(threadId: string) {
  const text = replies.value[threadId] ?? ''
  void run(async () => {
    await commentsApi.reply(threadId, text, mentionsIn(text))
    replies.value[threadId] = ''
  })
}

/** 고치는 중인 댓글과 그 글. 한 번에 하나다. */
const editing = ref<{ id: string; body: string } | null>(null)
/** 지우기를 한 번 더 눌러 확인하는 댓글. */
const confirming = ref<string | null>(null)

function saveEdit() {
  const target = editing.value
  if (target === null) {
    return
  }
  void run(async () => {
    await commentsApi.edit(target.id, target.body, mentionsIn(target.body))
    editing.value = null
  })
}

function remove(threadId: string, commentId: string) {
  if (confirming.value !== commentId) {
    confirming.value = commentId
    return
  }
  confirming.value = null
  void run(async () => {
    const { threadDeleted } = await commentsApi.remove(commentId)
    if (threadDeleted) {
      props.binding.removeCommentMark(threadId)
    }
  })
}

defineExpose({ load })
onMounted(() => run(async () => undefined))
</script>

<template>
  <aside class="comments" data-testid="comments">
    <header>
      <h3>댓글</h3>
      <button type="button" @click="emit('close')">닫기</button>
    </header>
    <button v-if="canComment && draft === null" type="button" data-testid="comment-start" @mousedown.prevent="startDraft">
      고른 글에 댓글
    </button>
    <form v-if="draft" class="draft" @submit.prevent="create">
      <blockquote>{{ draft.quote }}</blockquote>
      <textarea v-model="body" placeholder="댓글 (@이름 으로 알린다)" data-testid="comment-body" required />
      <button type="submit">달기</button>
      <button type="button" @click="draft = null">취소</button>
    </form>
    <label class="resolved-toggle"><input v-model="showResolved" type="checkbox" /> 해결된 댓글도 보기</label>
    <article
      v-for="thread in visible"
      :key="thread.id"
      class="thread"
      :class="{ resolved: thread.resolved }"
      data-testid="comment-thread"
      :data-thread-id="thread.id"
    >
      <blockquote>{{ thread.quote }}</blockquote>
      <p v-if="thread.orphaned" class="orphaned" data-testid="comment-orphaned">원문이 삭제됨</p>
      <div v-for="comment in thread.comments" :key="comment.id" class="comment" data-testid="comment">
        <p v-if="comment.deleted" class="deleted">삭제된 댓글</p>
        <form v-else-if="editing?.id === comment.id" @submit.prevent="saveEdit">
          <textarea v-model="editing.body" data-testid="comment-edit-body" required />
          <button type="submit" data-testid="comment-edit-save">저장</button>
          <button type="button" @click="editing = null">취소</button>
        </form>
        <p v-else>
          <strong>{{ comment.authorName }}</strong> {{ comment.body }}
          <span v-if="comment.editedAt" class="by">(고침)</span>
        </p>
        <span v-if="!comment.deleted && editing?.id !== comment.id && canComment" class="comment-actions">
          <button
            v-if="comment.authorId === myId"
            type="button"
            data-testid="comment-edit"
            @click="editing = { id: comment.id, body: comment.body }"
          >
            고치기
          </button>
          <button
            v-if="comment.authorId === myId || canDeleteAny"
            type="button"
            data-testid="comment-delete"
            @click="remove(thread.id, comment.id)"
          >
            {{ confirming === comment.id ? '정말 지우기' : '지우기' }}
          </button>
        </span>
      </div>
      <form v-if="canComment && !thread.resolved" @submit.prevent="reply(thread.id)">
        <input v-model="replies[thread.id]" placeholder="답글" data-testid="comment-reply" required />
      </form>
      <button
        v-if="canComment"
        type="button"
        @click="run(() => commentsApi.resolve(thread.id, !thread.resolved))"
      >
        {{ thread.resolved ? '다시 열기' : '해결' }}
      </button>
      <span v-if="thread.resolved" class="by">{{ thread.resolvedByName }} 님이 해결</span>
    </article>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
  </aside>
</template>

<style scoped>
.comments {
  border: 1px solid #ddd;
  border-radius: 6px;
  padding: 12px;
  margin: 8px 0;
}

.comments header {
  display: flex;
  justify-content: space-between;
}

blockquote {
  margin: 4px 0;
  padding-left: 8px;
  border-left: 3px solid #f5c518;
  color: #555;
}

.thread {
  border-top: 1px solid #eee;
  padding: 6px 0;
}

.thread.resolved {
  opacity: 0.6;
}

textarea {
  width: 100%;
  min-height: 60px;
}

.orphaned {
  margin: 0;
  font-size: 12px;
  color: #b54708;
}

.comment p {
  margin: 4px 0;
}

.comment .deleted {
  color: #888;
  font-style: italic;
}

.comment-actions button {
  font-size: 12px;
  margin-right: 4px;
}
</style>

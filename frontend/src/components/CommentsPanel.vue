<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { commentsApi } from '../api/comments'
import type { CommentThread } from '../api/comments'
import { ApiError } from '../api/http'
import type { EditorBinding } from '../collab/adapter/editorBinding'

/**
 * 댓글 (C-1~C-4, D-46). 본문에서 글자를 고르고 "고른 글에 댓글" 을 누르면 스레드를 만들고 그 글자에 댓글 서식을 붙인다. 답글을
 * 달고, 해결하고, 다시 연다. 글에 "@이름" 을 쓰면 그 사람에게 알린다. 다른 사람이 댓글을 바꾸면 서버가 알려 다시 읽는다.
 */
const props = defineProps<{
  docId: string
  binding: EditorBinding
  canComment: boolean
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
      <p v-for="comment in thread.comments" :key="comment.id">
        <strong>{{ comment.authorName }}</strong> {{ comment.body }}
      </p>
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
</style>

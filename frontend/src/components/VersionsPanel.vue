<script setup lang="ts">
import { onMounted, ref, shallowRef } from 'vue'
import { ApiError } from '../api/http'
import { versionsApi } from '../api/versions'
import type { VersionInfo } from '../api/versions'
import { decodeDocument } from '../collab/ot/json'
import type { Document } from '../collab/ot/document'

/**
 * 버전 기록 (V-1, V-2, D-44). 목록에서 시점을 고르면 그때의 내용을 읽기 전용으로 보인다. 전체 권한이면 그 시점으로 복원한다.
 * 복원은 지금 문서를 그 시점으로 바꾸는 편집이라 기록에 남고 되돌리기(Ctrl+Z)로 되돌릴 수 있다.
 */
const props = defineProps<{ docId: string; canRestore: boolean; canName: boolean }>()
const emit = defineEmits<{ restore: [document: Document, rev: number]; close: [] }>()
const versions = ref<VersionInfo[]>([])
const selected = shallowRef<{ version: VersionInfo; lines: { type: string; text: string }[]; document: Document } | null>(null)
const confirming = ref(false)
const name = ref('')
const error = ref('')

async function load() {
  versions.value = await versionsApi.list(props.docId)
}

async function run(action: () => Promise<unknown>) {
  error.value = ''
  try {
    await action()
  } catch (e) {
    error.value = e instanceof ApiError ? e.detail : String(e)
  }
}

/** 보이는 블록을 트리 순서로. 미리 보기는 글과 종류만 보인다. */
function lines(document: Document): { type: string; text: string }[] {
  const out: { type: string; text: string }[] = []
  const root = document.get(document.rootId)
  out.push({ type: 'title', text: root?.text.plainText() ?? '' })
  const walk = (parentId: string) => {
    for (const child of document.children(parentId)) {
      if (!child.deleted) {
        out.push({ type: child.type, text: child.text.plainText() })
        walk(child.id)
      }
    }
  }
  walk(document.rootId)
  return out
}

function open(version: VersionInfo) {
  confirming.value = false
  void run(async () => {
    const at = await versionsApi.at(props.docId, version.rev)
    const document = decodeDocument(at.document)
    selected.value = { version, lines: lines(document), document }
  })
}

function restore() {
  if (selected.value === null) {
    return
  }
  emit('restore', selected.value.document, selected.value.version.rev)
  confirming.value = false
  selected.value = null
}

function nameNow() {
  void run(async () => {
    await versionsApi.record(props.docId, 'NAMED', name.value)
    name.value = ''
    await load()
  })
}

function label(version: VersionInfo): string {
  if (version.name) {
    return version.name
  }
  return version.kind === 'AUTO' ? '자동 저장' : version.kind
}

defineExpose({ load })
onMounted(() => run(load))
</script>

<template>
  <aside class="versions" data-testid="versions">
    <header>
      <h3>버전 기록</h3>
      <button type="button" @click="emit('close')">닫기</button>
    </header>
    <form v-if="canName" class="name" @submit.prevent="nameNow">
      <input v-model="name" placeholder="지금 시점에 이름 붙이기" maxlength="100" required />
      <button type="submit">저장</button>
    </form>
    <p v-if="versions.length === 0" class="empty">아직 기록이 없다. 편집이 조용해지면 자동으로 남는다.</p>
    <ul>
      <li
        v-for="version in versions"
        :key="version.id"
        :class="{ active: selected?.version.id === version.id }"
        data-testid="version-item"
        @click="open(version)"
      >
        <strong>{{ label(version) }}</strong>
        <span>{{ new Date(version.createdAt).toLocaleString() }}</span>
        <span v-if="version.editors.length" class="editors">{{ version.editors.join(', ') }}</span>
      </li>
    </ul>
    <section v-if="selected" class="preview" data-testid="version-preview">
      <p v-for="(line, i) in selected.lines" :key="i" :class="line.type">{{ line.text || ' ' }}</p>
      <template v-if="canRestore">
        <button v-if="!confirming" type="button" @click="confirming = true">이 시점으로 복원</button>
        <span v-else>
          지금 내용이 이 시점으로 바뀐다. 다시 되돌릴 수 있다.
          <button type="button" data-testid="confirm-restore" @click="restore">복원</button>
          <button type="button" @click="confirming = false">취소</button>
        </span>
      </template>
    </section>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
  </aside>
</template>

<style scoped>
.versions {
  border: 1px solid #ddd;
  border-radius: 6px;
  padding: 12px;
  margin: 8px 0;
}

.versions header {
  display: flex;
  justify-content: space-between;
}

.versions ul {
  list-style: none;
  padding: 0;
  max-height: 240px;
  overflow-y: auto;
}

.versions li {
  display: flex;
  gap: 8px;
  padding: 4px 6px;
  cursor: pointer;
}

.versions li.active {
  background: #eef2ff;
}

.editors {
  color: #888;
}

.preview {
  border-top: 1px solid #eee;
  margin-top: 8px;
  padding-top: 8px;
}

.preview .title {
  font-weight: bold;
  font-size: 1.2em;
}

.preview .heading {
  font-weight: bold;
}
</style>

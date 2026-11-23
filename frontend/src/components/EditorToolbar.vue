<script setup lang="ts">
import type { Editor } from '@tiptap/vue-3'
import { ref } from 'vue'
import type { BlockType } from '../collab/adapter/blocks'
import type { EditorBinding } from '../collab/adapter/editorBinding'

/** 블록 종류와 글자 서식을 바꾸는 도구 막대 (docs/01-PLAN.md E-1, E-2). 슬래시 메뉴(E-6)는 다음 작업이다. */
const props = defineProps<{ editor: Editor; binding: EditorBinding }>()

interface Choice {
  label: string
  type: BlockType
  props?: Record<string, unknown>
}

const choices: Choice[] = [
  { label: '문단', type: 'paragraph' },
  { label: '제목 1', type: 'heading', props: { level: 1 } },
  { label: '제목 2', type: 'heading', props: { level: 2 } },
  { label: '제목 3', type: 'heading', props: { level: 3 } },
  { label: '글머리 기호 목록', type: 'bulleted' },
  { label: '번호 목록', type: 'numbered' },
  { label: '할 일', type: 'todo' },
  { label: '토글', type: 'toggle' },
  { label: '인용', type: 'quote' },
  { label: '콜아웃', type: 'callout' },
  { label: '코드', type: 'code' },
  { label: '구분선', type: 'divider' },
]

const link = ref('')
const linkError = ref('')

function changeType(event: Event) {
  const choice = choices[Number((event.target as HTMLSelectElement).value)]
  if (choice !== undefined) {
    props.binding.setBlockType(choice.type, choice.props)
  }
  ;(event.target as HTMLSelectElement).value = ''
}

function toggle(mark: 'bold' | 'italic' | 'underline' | 'strike' | 'code') {
  props.editor.chain().focus().toggleMark(mark).run()
}

function applyLink() {
  const href = link.value.trim()
  linkError.value = ''
  if (href === '') {
    props.editor.chain().focus().extendMarkRange('link').unsetLink().run()
    return
  }
  // 서버도 이 셋만 받는다 (docs/07-SECURITY.md 9.6).
  if (!/^(https?:|mailto:)/i.test(href)) {
    linkError.value = 'http, https, mailto 주소만 쓸 수 있다'
    return
  }
  props.editor.chain().focus().extendMarkRange('link').setLink({ href }).run()
  link.value = ''
}
</script>

<template>
  <div class="toolbar" data-testid="toolbar">
    <select aria-label="블록 종류" data-testid="block-type" @change="changeType">
      <option value="">블록 종류</option>
      <option v-for="(choice, i) in choices" :key="choice.label" :value="i">{{ choice.label }}</option>
    </select>
    <button type="button" title="굵게 (Ctrl+B)" data-testid="mark-bold" @mousedown.prevent @click="toggle('bold')"><b>B</b></button>
    <button type="button" title="기울임 (Ctrl+I)" data-testid="mark-italic" @mousedown.prevent @click="toggle('italic')"><i>I</i></button>
    <button type="button" title="밑줄 (Ctrl+U)" data-testid="mark-underline" @mousedown.prevent @click="toggle('underline')"><u>U</u></button>
    <button type="button" title="취소선 (Ctrl+Shift+S)" data-testid="mark-strike" @mousedown.prevent @click="toggle('strike')"><s>S</s></button>
    <button type="button" title="인라인 코드 (Ctrl+E)" data-testid="mark-code" @mousedown.prevent @click="toggle('code')"><code>&lt;/&gt;</code></button>
    <form class="link" @submit.prevent="applyLink">
      <input v-model="link" placeholder="링크 주소 (비우면 지움)" data-testid="link-input" @mousedown.stop />
      <button type="submit" data-testid="link-apply">링크</button>
    </form>
    <span v-if="linkError" class="error">{{ linkError }}</span>
  </div>
</template>

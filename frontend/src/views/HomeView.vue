<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { uuidv7 } from '../collab/client/ids'

/** 워크스페이스와 페이지 트리(3단계)가 생기기 전까지 쓰는 임시 홈. 새 문서를 만들거나 ID 로 연다. */
const router = useRouter()
const pageId = ref('')

function create() {
  void router.push(`/p/${uuidv7()}`)
}

function open() {
  void router.push(`/p/${pageId.value.trim()}`)
}
</script>

<template>
  <h1>문서</h1>
  <p><button type="button" @click="create">새 문서</button></p>
  <form @submit.prevent="open">
    <input v-model="pageId" placeholder="문서 ID" required />
    <button type="submit">열기</button>
  </form>
</template>

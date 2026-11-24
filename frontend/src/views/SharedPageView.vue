<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ApiError } from '../api/http'
import { workspaceApi } from '../api/workspace'
import type { SharedPage } from '../api/workspace'
import EditorView from './EditorView.vue'

/** 공유 링크로 연 페이지 (D-41). 사이드바 없이 그 페이지만 보인다. 링크가 꺼지면 편집기가 닫힌다. */
const props = defineProps<{ token: string }>()
const page = ref<SharedPage | null>(null)
const error = ref('')

onMounted(async () => {
  try {
    page.value = await workspaceApi.openShared(props.token)
  } catch (e) {
    error.value = e instanceof ApiError && e.status === 404 ? '링크가 없거나 만료되었다' : String(e)
  }
})
</script>

<template>
  <main class="shared" data-testid="shared-page">
    <EditorView v-if="page" workspace-id="" :page-id="page.pageId" :share-token="token" />
    <p v-if="error" class="error" data-testid="page-error">{{ error }}</p>
  </main>
</template>

<style scoped>
.shared {
  max-width: 860px;
  margin: 24px auto;
}
</style>

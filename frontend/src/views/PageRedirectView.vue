<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError } from '../api/http'
import { workspaceApi } from '../api/workspace'

/** 예전 주소 /p/:pageId 를 /w/:workspaceId/p/:pageId 로 옮긴다. 공유받은 주소에는 워크스페이스가 없을 수 있다. */
const props = defineProps<{ pageId: string }>()
const router = useRouter()
const error = ref('')

onMounted(async () => {
  try {
    const page = await workspaceApi.page(props.pageId)
    await router.replace(`/w/${page.workspaceId}/p/${page.id}`)
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.status} ${e.detail}` : String(e)
  }
})
</script>

<template>
  <p v-if="error" class="error" data-testid="page-error">페이지를 열 수 없다 ({{ error }})</p>
</template>

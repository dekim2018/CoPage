<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError } from '../api/http'
import { workspaceApi } from '../api/workspace'
import type { InvitePreview } from '../api/workspace'
import { useAuth } from '../stores/auth'
import { useWorkspace } from '../stores/workspace'

/**
 * 초대 링크 (D-40). 로그인하지 않았으면 라우터가 로그인 화면으로 보내고, 로그인이나 가입 뒤 이 화면으로 돌아온다. 초대한 이메일과
 * 로그인한 이메일이 같아야 받을 수 있다.
 */
const props = defineProps<{ token: string }>()
const router = useRouter()
const auth = useAuth()
const workspace = useWorkspace()
const preview = ref<InvitePreview | null>(null)
const error = ref('')
const busy = ref(false)

onMounted(async () => {
  try {
    preview.value = await workspaceApi.previewInvite(props.token)
  } catch (e) {
    error.value = e instanceof ApiError && e.status === 404 ? '초대가 없거나 만료되었다' : String(e)
  }
})

async function accept() {
  busy.value = true
  error.value = ''
  try {
    const { workspaceId } = await workspaceApi.acceptInvite(props.token)
    await workspace.loadWorkspaces()
    await router.push(`/w/${workspaceId}`)
  } catch (e) {
    error.value = e instanceof ApiError ? e.detail : String(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <main class="invite" data-testid="invite">
    <template v-if="preview">
      <h1>{{ preview.workspaceName }}</h1>
      <p>{{ preview.email }} 으로 {{ preview.role }} 초대를 받았다.</p>
      <p v-if="auth.user && auth.user.email !== preview.email" class="error">
        지금 {{ auth.user.email }} 으로 로그인해 있다. 초대받은 이메일로 로그인해야 받을 수 있다.
      </p>
      <button type="button" :disabled="busy" @click="accept">참여하기</button>
    </template>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
  </main>
</template>

<style scoped>
.invite {
  max-width: 480px;
  margin: 64px auto;
}
</style>

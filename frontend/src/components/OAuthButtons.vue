<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'

/**
 * 구글, 깃허브로 계속하기 (A-4, D-53). 서버에 켜진 서비스만 단추를 그린다. 누르면 그 서비스로 갔다가 돌아오고, 서버가 Refresh
 * 쿠키를 준 뒤 첫 화면으로 보낸다. 실패하면 로그인 화면으로 까닭(oauthError)을 실어 돌아온다.
 */
const route = useRoute()
const providers = ref<string[]>([])

const LABELS: Record<string, string> = { google: '구글로 계속', github: '깃허브로 계속' }
const ERRORS: Record<string, string> = {
  'email-not-verified': '그 서비스에서 확인된 이메일을 받지 못했다',
  'account-exists': '그 이메일로 비밀번호 가입한 계정이 있다. 비밀번호로 로그인한다',
  failed: '로그인하지 못했다. 다시 시도한다',
}
const error = typeof route.query.oauthError === 'string' ? (ERRORS[route.query.oauthError] ?? ERRORS.failed) : ''

onMounted(async () => {
  try {
    const response = await fetch('/api/auth/providers')
    providers.value = response.ok ? ((await response.json()) as string[]) : []
  } catch {
    providers.value = []
  }
})
</script>

<template>
  <div v-if="providers.length > 0 || error" class="oauth" data-testid="oauth">
    <a
      v-for="provider in providers"
      :key="provider"
      :href="`/oauth2/authorization/${provider}`"
      class="oauth-button"
      :data-testid="`oauth-${provider}`"
    >
      {{ LABELS[provider] ?? provider }}
    </a>
    <p v-if="error" class="error" data-testid="oauth-error">{{ error }}</p>
  </div>
</template>

<style scoped>
.oauth {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 12px 0;
  max-width: 320px;
}

.oauth-button {
  display: block;
  padding: 8px 12px;
  border: 1px solid #ccc;
  border-radius: 6px;
  text-align: center;
  color: inherit;
  text-decoration: none;
}
</style>

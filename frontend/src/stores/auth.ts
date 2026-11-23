import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api, authApi, refresh, setAccessToken } from '../api/http'
import type { TokenResponse, UserInfo } from '../api/http'

/** 로그인 상태. 새로고침하면 Refresh 쿠키로 다시 로그인한다 (docs/01-PLAN.md A-2). */
export const useAuth = defineStore('auth', () => {
  const user = ref<UserInfo | null>(null)
  const restored = ref(false)

  async function restore(): Promise<void> {
    if (!restored.value) {
      const tokens = await refresh()
      user.value = tokens?.user ?? null
      restored.value = true
    }
  }

  async function login(email: string, password: string): Promise<void> {
    const tokens = await authApi<TokenResponse>('/api/auth/login', { email, password })
    setAccessToken(tokens.accessToken)
    user.value = tokens.user
  }

  async function signup(email: string, password: string, name: string): Promise<void> {
    await authApi('/api/auth/signup', { email, password, name })
    await login(email, password)
  }

  async function logout(): Promise<void> {
    // Authorization 헤더를 붙여 보내야 서버가 지금 Access 토큰도 막는다.
    await api('/api/auth/logout', { method: 'POST' })
    setAccessToken(null)
    user.value = null
  }

  return { user, restored, restore, login, signup, logout }
})

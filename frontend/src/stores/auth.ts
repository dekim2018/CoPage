import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api, authApi, refresh, setAccessToken } from '../api/http'
import { clearOfflineStore } from '../collab/client/offlineStore'
import { useWorkspace } from './workspace'
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

  /** 서버 호출이 실패해도(토큰이 모두 만료되었거나 서버에 닿지 못함) 이 화면에서는 로그아웃한다. */
  async function logout(): Promise<void> {
    try {
      // Authorization 헤더를 붙여 보내야 서버가 지금 Access 토큰도 막는다.
      await api('/api/auth/logout', { method: 'POST' })
    } catch (e) {
      console.warn('서버에 로그아웃을 알리지 못했다', e)
    } finally {
      setAccessToken(null)
      user.value = null
      // 다른 사람이 같은 브라우저로 로그인해도 앞 사람의 트리가 남지 않는다.
      useWorkspace().reset()
      // 공용 PC 에 문서가 남지 않게 브라우저에 남긴 편집도 지운다 (docs/10-FRONTEND.md 12.4).
      await clearOfflineStore()
    }
  }

  return { user, restored, restore, login, signup, logout }
})

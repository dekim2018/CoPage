import { createRouter, createWebHistory } from 'vue-router'
import { useAuth } from './stores/auth'

/*
 * 화면 경로 (docs/01-PLAN.md 3.1). 편집기는 워크스페이스가 생기기 전(3단계)까지 /w/:workspaceId/p/:pageId 대신 /p/:pageId 를
 * 쓴다.
 */
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('./views/LoginView.vue'), meta: { guest: true } },
    { path: '/signup', component: () => import('./views/SignupView.vue'), meta: { guest: true } },
    { path: '/', component: () => import('./views/HomeView.vue') },
    { path: '/p/:pageId', component: () => import('./views/EditorView.vue'), props: true },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuth()
  await auth.restore()
  if (!to.meta.guest && auth.user === null) {
    return { path: '/login', query: { next: to.fullPath } }
  }
  return true
})

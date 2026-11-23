import { createRouter, createWebHistory } from 'vue-router'
import { useAuth } from './stores/auth'

/*
 * 화면 경로 (docs/01-PLAN.md 3.1). 편집기는 워크스페이스 아래 /w/:workspaceId/p/:pageId 다. 예전 주소 /p/:pageId 는 페이지의
 * 워크스페이스를 찾아 옮긴다 (D-22).
 */
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('./views/LoginView.vue'), meta: { guest: true } },
    { path: '/signup', component: () => import('./views/SignupView.vue'), meta: { guest: true } },
    { path: '/', component: () => import('./views/HomeView.vue') },
    { path: '/p/:pageId', component: () => import('./views/PageRedirectView.vue'), props: true },
    {
      path: '/w/:workspaceId',
      component: () => import('./views/WorkspaceView.vue'),
      props: true,
      children: [
        { path: '', component: () => import('./views/WorkspaceHomeView.vue') },
        { path: 'p/:pageId', component: () => import('./views/EditorView.vue'), props: true },
        { path: 'members', component: () => import('./views/MembersView.vue'), props: true },
      ],
    },
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

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
    { path: '/invite/:token', component: () => import('./views/InviteView.vue'), props: true },
    // 공유 링크(D-41). 로그인하지 않아도 연다. 로그인했으면 그 사용자로 연다.
    { path: '/s/:token', component: () => import('./views/SharedPageView.vue'), props: true, meta: { public: true } },
    {
      path: '/w/:workspaceId',
      component: () => import('./views/WorkspaceView.vue'),
      props: true,
      children: [
        { path: '', component: () => import('./views/WorkspaceHomeView.vue') },
        { path: 'p/:pageId', component: () => import('./views/EditorView.vue'), props: true },
        { path: 'members', component: () => import('./views/MembersView.vue'), props: true },
        { path: 'trash', component: () => import('./views/TrashView.vue'), props: true },
        { path: 'profile', component: () => import('./views/ProfileView.vue') },
      ],
    },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuth()
  await auth.restore()
  if (!to.meta.guest && !to.meta.public && auth.user === null) {
    return { path: '/login', query: { next: to.fullPath } }
  }
  return true
})

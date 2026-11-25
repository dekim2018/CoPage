import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// 개발 서버는 /api 와 /ws 를 백엔드로 넘긴다. 브라우저에는 같은 출처로 보여서 Refresh 쿠키(SameSite=Strict)가 그대로 실린다.
const backend = process.env.COPAGE_BACKEND ?? 'http://localhost:8080'

export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api': { target: backend, changeOrigin: false },
      '/ws': { target: backend.replace(/^http/, 'ws'), ws: true },
      // 구글, 깃허브 로그인(A-4). 서비스에 등록하는 돌아오는 주소도 개발 서버의 주소다.
      '/oauth2': { target: backend, changeOrigin: false },
      '/login/oauth2': { target: backend, changeOrigin: false },
    },
  },
})

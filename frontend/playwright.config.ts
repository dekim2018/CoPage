import { defineConfig, devices } from '@playwright/test'

/*
 * 화면 테스트 (docs/11-TEST-PLAN.md 13.1). 백엔드 jar 와 Vite 개발 서버를 띄우고 브라우저 두 개로 같은 문서를 편집한다.
 *
 * 먼저 PostgreSQL 과 Redis 를 띄우고(docker compose up -d) 백엔드 jar 를 만든다(./gradlew :app:bootJar). DB 와 Redis 포트는
 * COPAGE_DB_PORT, COPAGE_REDIS_PORT 로 바꿀 수 있다.
 */
const backendPort = 18081
const webPort = 5174

export default defineConfig({
  testDir: 'e2e',
  timeout: 60_000,
  fullyParallel: false,
  workers: 1,
  reporter: process.env.CI ? 'list' : 'line',
  use: {
    ...devices['Desktop Chrome'],
    baseURL: `http://localhost:${webPort}`,
    trace: 'retain-on-failure',
  },
  webServer: [
    {
      command: `java -jar ../app/build/libs/app-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev --server.port=${backendPort}`,
      // 로그인하지 않았으니 401 이 오면 떠 있는 것이다.
      url: `http://localhost:${backendPort}/api/me`,
      timeout: 120_000,
      reuseExistingServer: !process.env.CI,
      stdout: 'ignore',
      stderr: 'pipe',
    },
    {
      command: `npx vite --port ${webPort} --strictPort`,
      url: `http://localhost:${webPort}`,
      env: { COPAGE_BACKEND: `http://localhost:${backendPort}` },
      timeout: 60_000,
      reuseExistingServer: !process.env.CI,
    },
  ],
})

import { defineConfig } from '@playwright/test'
import base from './playwright.config'

/*
 * 시연 영상 (로드맵 7단계, D-51). 평소 화면 시험과 같은 서버로 demo/ 의 장면 하나를 천천히 돌리며 두 사람의 화면을 녹화한다.
 * 녹화를 좌우로 붙이는 것은 demo/compose.sh 가 한다.
 *
 *   npx playwright test -c playwright.demo.config.ts && demo/compose.sh
 */
export default defineConfig({
  ...base,
  testDir: 'demo',
  timeout: 180_000,
  outputDir: 'demo-results',
})

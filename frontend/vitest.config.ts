import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    include: ['src/**/*.test.ts', 'test/**/*.test.ts'],
    // 적합성 벡터 파일이 커서 읽고 재생하는 데 시간이 걸린다.
    testTimeout: 300_000,
  },
})

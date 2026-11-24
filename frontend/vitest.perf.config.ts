import { defineConfig } from 'vitest/config'

/* 측정 전용 (npm run perf). 시간을 단언하지 않는다. */
export default defineConfig({
  test: {
    include: ['src/**/*.perf.ts'],
    testTimeout: 600_000,
  },
})

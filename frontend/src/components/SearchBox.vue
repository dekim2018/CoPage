<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { workspaceApi } from '../api/workspace'
import type { SearchHit } from '../api/workspace'

/**
 * 사이드바의 검색 (F-1, F-2, D-47). 치기를 잠깐 멈추면 찾는다. 볼 수 있는 페이지만 온다. 결과를 누르면 그 페이지로 간다.
 * 본문은 편집이 멈추고 몇 초 뒤에 색인되므로 방금 친 글은 조금 늦게 찾힌다.
 */
const props = defineProps<{ workspaceId: string }>()
const router = useRouter()
const query = ref('')
const hits = ref<SearchHit[] | null>(null)
const failed = ref(false)
let timer: ReturnType<typeof setTimeout> | undefined
let latest = 0

watch(query, (value) => {
  clearTimeout(timer)
  if (value.trim() === '') {
    hits.value = null
    return
  }
  timer = setTimeout(() => void run(value), 250)
})

watch(
  () => props.workspaceId,
  () => {
    query.value = ''
    hits.value = null
  },
)

async function run(value: string) {
  const ticket = ++latest
  try {
    const found = await workspaceApi.search(props.workspaceId, value.trim())
    if (ticket === latest) {
      hits.value = found
      failed.value = false
    }
  } catch {
    if (ticket === latest) {
      failed.value = true
    }
  }
}

async function go(hit: SearchHit) {
  query.value = ''
  hits.value = null
  await router.push(`/w/${props.workspaceId}/p/${hit.id}`)
}

onBeforeUnmount(() => clearTimeout(timer))
</script>

<template>
  <div class="search">
    <input
      v-model="query"
      type="search"
      class="search-input"
      placeholder="검색"
      aria-label="페이지 검색"
      data-testid="search-input"
      @keydown.esc="query = ''"
    />
    <div v-if="hits !== null || failed" class="search-results" data-testid="search-results">
      <p v-if="failed" class="error">검색하지 못했다</p>
      <p v-else-if="hits && hits.length === 0" class="search-empty">찾은 페이지가 없다</p>
      <button
        v-for="hit in hits ?? []"
        :key="hit.id"
        type="button"
        class="search-hit"
        data-testid="search-result"
        @click="go(hit)"
      >
        <span class="search-title">{{ hit.title || '제목 없음' }}</span>
        <span v-if="hit.snippet" class="search-snippet">{{ hit.snippet }}</span>
      </button>
    </div>
  </div>
</template>

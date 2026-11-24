<script setup lang="ts">
import { computed, ref } from 'vue'
import { ApiError } from '../api/http'
import { CURSOR_COLORS, workspaceApi } from '../api/workspace'
import { useAuth } from '../stores/auth'

/**
 * 내 프로필 (A-3). 이름과 남의 화면에 보일 커서 색을 바꾼다. 바꾼 것은 다음에 여는 편집 연결부터 남에게 보인다. 색을 고르지
 * 않았으면 서버가 사용자 ID 로 정한 색을 쓴다.
 */
const auth = useAuth()
const name = ref(auth.user?.name ?? '')
const color = ref<string | null>(auth.user?.color ?? null)
const message = ref('')
const error = ref('')
const changed = computed(
  () => name.value.trim() !== (auth.user?.name ?? '') || color.value !== (auth.user?.color ?? null),
)

async function save() {
  error.value = ''
  message.value = ''
  const change: { name?: string; color?: string } = {}
  if (name.value.trim() !== auth.user?.name) {
    change.name = name.value.trim()
  }
  if (color.value !== null && color.value !== auth.user?.color) {
    change.color = color.value
  }
  try {
    auth.user = await workspaceApi.updateProfile(change)
    message.value = '저장했다. 다음에 여는 페이지부터 보인다'
  } catch (e) {
    error.value = e instanceof ApiError ? e.detail : '저장하지 못했다'
  }
}
</script>

<template>
  <h1>내 프로필</h1>
  <form class="profile" data-testid="profile" @submit.prevent="save">
    <label>
      이름
      <input v-model="name" maxlength="50" required data-testid="profile-name" />
    </label>
    <fieldset class="colors">
      <legend>커서 색</legend>
      <button
        v-for="option in CURSOR_COLORS"
        :key="option"
        type="button"
        class="swatch"
        :class="{ selected: color === option }"
        :style="{ background: option }"
        :aria-label="option"
        :aria-pressed="color === option"
        data-testid="profile-color"
        @click="color = option"
      />
    </fieldset>
    <button type="submit" :disabled="!changed || name.trim() === ''" data-testid="profile-save">저장</button>
    <p v-if="message" class="saved" data-testid="profile-saved">{{ message }}</p>
    <p v-if="error" class="error">{{ error }}</p>
  </form>
</template>

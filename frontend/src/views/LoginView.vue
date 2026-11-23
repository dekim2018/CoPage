<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError } from '../api/http'
import { useAuth } from '../stores/auth'

const auth = useAuth()
const route = useRoute()
const router = useRouter()
const email = ref('')
const password = ref('')
const error = ref('')

async function submit() {
  error.value = ''
  try {
    await auth.login(email.value, password.value)
    await router.push(typeof route.query.next === 'string' ? route.query.next : '/')
  } catch (e) {
    error.value = e instanceof ApiError ? e.detail : String(e)
  }
}
</script>

<template>
  <h1>로그인</h1>
  <form @submit.prevent="submit">
    <input v-model="email" name="email" type="email" placeholder="이메일" autocomplete="username" required />
    <input v-model="password" name="password" type="password" placeholder="비밀번호" autocomplete="current-password" required />
    <button type="submit">로그인</button>
    <p v-if="error" class="error">{{ error }}</p>
  </form>
  <p><RouterLink :to="{ path: '/signup', query: route.query }">가입하기</RouterLink></p>
</template>

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
const name = ref('')
const error = ref('')

async function submit() {
  error.value = ''
  try {
    await auth.signup(email.value, password.value, name.value)
    await router.push(typeof route.query.next === 'string' ? route.query.next : '/')
  } catch (e) {
    error.value = e instanceof ApiError ? e.detail : String(e)
  }
}
</script>

<template>
  <h1>가입</h1>
  <form @submit.prevent="submit">
    <input v-model="email" name="email" type="email" placeholder="이메일" autocomplete="username" required />
    <input v-model="password" name="password" type="password" placeholder="비밀번호 (8자 이상)" autocomplete="new-password" minlength="8" required />
    <input v-model="name" name="name" placeholder="이름" required />
    <button type="submit">가입</button>
    <p v-if="error" class="error">{{ error }}</p>
  </form>
  <p><RouterLink :to="{ path: '/login', query: route.query }">로그인</RouterLink></p>
</template>

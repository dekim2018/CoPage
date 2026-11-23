<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuth } from './stores/auth'

const auth = useAuth()
const router = useRouter()

async function logout() {
  await auth.logout()
  await router.push('/login')
}
</script>

<template>
  <header class="top">
    <RouterLink to="/" class="brand">CoPage</RouterLink>
    <span v-if="auth.user" class="who">
      {{ auth.user.name }}
      <button type="button" @click="logout">로그아웃</button>
    </span>
  </header>
  <main>
    <RouterView />
  </main>
</template>

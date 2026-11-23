<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ApiError } from '../api/http'
import { workspaceApi } from '../api/workspace'
import type { Grant } from '../api/workspace'
import type { PageRole } from '../collab/client/role'

/**
 * 페이지 공유 (docs/09-API.md 11.3, S-3). 이 페이지의 FULL 만 연다. 이메일로 권한을 주고, 등급을 바꾸고, 회수하고, 상속을
 * 끊는다. 바꾸면 상대의 열린 편집기에도 1초 안에 적용된다(S-5).
 */
const props = defineProps<{ pageId: string; restricted: boolean }>()
const emit = defineEmits<{ close: []; restrictedChanged: [restricted: boolean] }>()
const grants = ref<Grant[]>([])
const email = ref('')
const level = ref<PageRole>('EDIT')
const restricted = ref(props.restricted)
const error = ref('')
const levels: PageRole[] = ['VIEW', 'COMMENT', 'EDIT', 'FULL']

async function load() {
  grants.value = await workspaceApi.grants(props.pageId)
}

async function run(action: () => Promise<unknown>) {
  error.value = ''
  try {
    await action()
    await load()
  } catch (e) {
    error.value = e instanceof ApiError ? e.detail : String(e)
  }
}

function share() {
  void run(async () => {
    await workspaceApi.grantByEmail(props.pageId, email.value, level.value)
    email.value = ''
  })
}

function toggleRestricted() {
  const next = !restricted.value
  void run(async () => {
    await workspaceApi.setRestricted(props.pageId, next)
    restricted.value = next
    emit('restrictedChanged', next)
  })
}

onMounted(() => run(async () => undefined))
</script>

<template>
  <div class="share" role="dialog" aria-label="공유" data-testid="share-dialog">
    <form class="share-add" @submit.prevent="share">
      <input v-model="email" name="share-email" type="email" placeholder="이메일" required />
      <select v-model="level" aria-label="공유 등급">
        <option v-for="option in levels" :key="option" :value="option">{{ option }}</option>
      </select>
      <button type="submit">공유</button>
    </form>
    <ul class="grants">
      <li v-for="grant in grants" :key="grant.userId + (grant.inheritedFrom ?? '')" :data-email="grant.email">
        <span>{{ grant.name }} ({{ grant.email }})</span>
        <template v-if="grant.inheritedFrom === null">
          <select
            :value="grant.level"
            aria-label="등급"
            @change="run(() => workspaceApi.setGrant(pageId, grant.userId, ($event.target as HTMLSelectElement).value as PageRole))"
          >
            <option v-for="option in levels" :key="option" :value="option">{{ option }}</option>
          </select>
          <button type="button" @click="run(() => workspaceApi.revoke(pageId, grant.userId))">회수</button>
        </template>
        <span v-else class="inherited">{{ grant.level }} (상위 페이지에서 물려받음)</span>
      </li>
    </ul>
    <label class="restricted">
      <input type="checkbox" :checked="restricted" @change="toggleRestricted" />
      상위 페이지의 권한을 물려받지 않는다 (제한)
    </label>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <button type="button" @click="emit('close')">닫기</button>
  </div>
</template>

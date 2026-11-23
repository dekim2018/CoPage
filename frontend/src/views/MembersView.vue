<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ApiError } from '../api/http'
import { workspaceApi } from '../api/workspace'
import type { Member, WorkspaceRole } from '../api/workspace'
import { useWorkspace } from '../stores/workspace'

/**
 * 워크스페이스 멤버 (docs/09-API.md 11.2). OWNER 는 가입한 사용자를 이메일로 넣고, 역할을 바꾸고, 내보낸다. 초대 메일(W-1 의
 * invites)은 아직 없다 (D-22).
 */
const props = defineProps<{ workspaceId: string }>()
const store = useWorkspace()
const members = ref<Member[]>([])
const email = ref('')
const role = ref<WorkspaceRole>('MEMBER')
const error = ref('')
const isOwner = computed(() => store.current?.role === 'OWNER')
const roles: WorkspaceRole[] = ['OWNER', 'MEMBER', 'GUEST']

async function load() {
  members.value = await workspaceApi.members(props.workspaceId)
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

function add() {
  void run(async () => {
    await workspaceApi.addMember(props.workspaceId, email.value, role.value)
    email.value = ''
  })
}

onMounted(() => run(async () => undefined))
</script>

<template>
  <h1>멤버</h1>
  <table class="members" data-testid="members">
    <tbody>
      <tr v-for="member in members" :key="member.userId" :data-email="member.email">
        <td>{{ member.name }}</td>
        <td>{{ member.email }}</td>
        <td>
          <select
            v-if="isOwner"
            :value="member.role"
            aria-label="역할"
            @change="run(() => workspaceApi.changeRole(workspaceId, member.userId, ($event.target as HTMLSelectElement).value as WorkspaceRole))"
          >
            <option v-for="option in roles" :key="option" :value="option">{{ option }}</option>
          </select>
          <span v-else>{{ member.role }}</span>
        </td>
        <td>
          <button v-if="isOwner" type="button" @click="run(() => workspaceApi.removeMember(workspaceId, member.userId))">
            내보내기
          </button>
        </td>
      </tr>
    </tbody>
  </table>
  <form v-if="isOwner" @submit.prevent="add">
    <input v-model="email" name="member-email" type="email" placeholder="가입한 사용자의 이메일" required />
    <select v-model="role" aria-label="새 멤버 역할">
      <option value="MEMBER">MEMBER</option>
      <option value="GUEST">GUEST</option>
    </select>
    <button type="submit">넣기</button>
  </form>
  <p v-if="error" class="error" role="alert">{{ error }}</p>
</template>

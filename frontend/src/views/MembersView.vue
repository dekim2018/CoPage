<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ApiError } from '../api/http'
import { workspaceApi } from '../api/workspace'
import type { Member, PendingInvite, WorkspaceRole } from '../api/workspace'
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

const invites = ref<PendingInvite[]>([])
const inviteEmail = ref('')
const inviteRole = ref<WorkspaceRole>('MEMBER')
/** 방금 만든 초대 링크. 토큰은 이때만 알 수 있어 화면을 떠나면 다시 볼 수 없다. */
const inviteLink = ref('')

async function load() {
  members.value = await workspaceApi.members(props.workspaceId)
  if (isOwner.value) {
    invites.value = await workspaceApi.invites(props.workspaceId)
  }
}

/** 초대 링크를 만든다. 메일은 보내지 않는다. OWNER 가 링크를 건넨다(D-40). */
function invite() {
  void run(async () => {
    const created = await workspaceApi.invite(props.workspaceId, inviteEmail.value, inviteRole.value)
    inviteLink.value = `${location.origin}/invite/${created.token}`
    inviteEmail.value = ''
  })
}

function copyLink() {
  void navigator.clipboard?.writeText(inviteLink.value)
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
  <section v-if="isOwner" class="invites">
    <h2>초대</h2>
    <form @submit.prevent="invite">
      <input v-model="inviteEmail" name="invite-email" type="email" placeholder="초대할 이메일" required />
      <select v-model="inviteRole" aria-label="초대 역할">
        <option value="MEMBER">MEMBER</option>
        <option value="GUEST">GUEST</option>
      </select>
      <button type="submit">초대 링크 만들기</button>
    </form>
    <p v-if="inviteLink" class="invite-link">
      이 링크를 초대한 사람에게 건넨다. 그 이메일로 가입한 사람만 받을 수 있고 7일 뒤 만료된다. 지금만 볼 수 있다.
      <input :value="inviteLink" readonly data-testid="invite-link" aria-label="초대 링크" />
      <button type="button" @click="copyLink">복사</button>
    </p>
    <ul data-testid="pending-invites">
      <li v-for="pending in invites" :key="pending.id" :data-email="pending.email">
        {{ pending.email }} ({{ pending.role }})
        <button type="button" @click="run(() => workspaceApi.revokeInvite(workspaceId, pending.id))">취소</button>
      </li>
    </ul>
  </section>
  <p v-if="error" class="error" role="alert">{{ error }}</p>
</template>

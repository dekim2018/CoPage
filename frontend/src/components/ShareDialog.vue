<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ApiError } from '../api/http'
import { workspaceApi } from '../api/workspace'
import type { Grant, ShareLinkSetting } from '../api/workspace'
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

const link = ref<ShareLinkSetting | null>(null)
const linkLevel = ref<PageRole>('VIEW')
// 날짜 입력(YYYY-MM-DD). 비우면 끌 때까지 쓴다.
const linkExpires = ref('')
/** 방금 만든 링크. 토큰은 만들 때만 알 수 있어 창을 닫으면 다시 볼 수 없다(D-41). */
const linkUrl = ref('')
const linkLevels: PageRole[] = ['VIEW', 'COMMENT', 'EDIT']

async function load() {
  grants.value = await workspaceApi.grants(props.pageId)
  link.value = (await workspaceApi.shareLink(props.pageId)) ?? null
  if (link.value !== null) {
    linkLevel.value = link.value.level
    linkExpires.value = link.value.expiresAt?.slice(0, 10) ?? ''
  }
}

/** 그날이 끝날 때까지 쓴다. */
function expiresAt(): string | null {
  return linkExpires.value === '' ? null : new Date(`${linkExpires.value}T23:59:59`).toISOString()
}

function saveLink(regenerate: boolean) {
  void run(async () => {
    const saved = await workspaceApi.saveShareLink(props.pageId, linkLevel.value, expiresAt(), regenerate)
    if (saved.token !== null) {
      linkUrl.value = `${location.origin}/s/${saved.token}`
    }
  })
}

function disableLink() {
  void run(async () => {
    await workspaceApi.disableShareLink(props.pageId)
    linkUrl.value = ''
  })
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
    <section class="share-link" data-testid="share-link">
      <h3>링크 공유</h3>
      <p>링크를 가진 사람은 로그인하지 않아도 이 페이지 하나를 볼 수 있다. 하위 페이지는 보이지 않는다.</p>
      <select v-model="linkLevel" aria-label="링크 등급">
        <option v-for="option in linkLevels" :key="option" :value="option">{{ option }}</option>
      </select>
      <input v-model="linkExpires" type="date" aria-label="링크 만료일" />
      <template v-if="link === null">
        <button type="button" @click="saveLink(false)">링크 만들기</button>
      </template>
      <template v-else>
        <button type="button" @click="saveLink(false)">저장</button>
        <button type="button" @click="saveLink(true)">새 링크 만들기</button>
        <button type="button" @click="disableLink">링크 끄기</button>
      </template>
      <p v-if="linkUrl" class="link-url">
        지금만 볼 수 있다. 새 링크를 만들면 예전 링크는 더 쓸 수 없다.
        <input :value="linkUrl" readonly data-testid="share-link-url" aria-label="공유 링크" />
      </p>
      <p v-else-if="link !== null" class="link-on">링크가 켜져 있다. 주소를 다시 보려면 새 링크를 만든다.</p>
    </section>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <button type="button" @click="emit('close')">닫기</button>
  </div>
</template>

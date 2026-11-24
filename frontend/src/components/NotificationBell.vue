<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { notificationsApi } from '../api/comments'
import type { NotificationItem } from '../api/comments'
import { connectLive } from '../api/live'

/**
 * 알림 (C-4, D-46). 멘션과 답글이 오면 알림 연결로 바로 받아 숫자를 올린다. 누르면 그 페이지로 간다. 같은 연결로 사이드바 트리
 * 변경(W-5)도 받아 tree 로 넘긴다.
 */
const emit = defineEmits<{ tree: [workspaceId: string, parentIds: string[]] }>()
const router = useRouter()
const unread = ref(0)
const items = ref<NotificationItem[]>([])
const open = ref(false)
let disconnect: (() => void) | null = null

const KIND: Record<NotificationItem['kind'], string> = {
  MENTION: '본문에서 멘션',
  COMMENT_MENTION: '댓글에서 멘션',
  REPLY: '답글',
  PAGE_SHARED: '페이지 공유',
}

async function load() {
  try {
    const inbox = await notificationsApi.inbox()
    unread.value = inbox.unread
    items.value = inbox.items
  } catch {
    // 다음 알림 때 다시 읽는다.
  }
}

async function go(item: NotificationItem) {
  open.value = false
  await notificationsApi.read([item.id])
  await load()
  if (item.docId) {
    await router.push(`/p/${item.docId}`)
  }
}

onMounted(() => {
  void load()
  disconnect = connectLive((type, data) => {
    if (type === 'notification') {
      void load()
    } else if (type === 'tree' && data !== null) {
      const tree = data as { workspaceId: string; parentIds: string[] }
      emit('tree', tree.workspaceId, tree.parentIds)
    }
  })
})

onBeforeUnmount(() => disconnect?.())
</script>

<template>
  <div class="bell">
    <button type="button" data-testid="bell" :aria-label="`알림 ${unread}개`" @click="open = !open">
      알림 <span v-if="unread > 0" class="badge" data-testid="bell-count">{{ unread }}</span>
    </button>
    <ul v-if="open" class="inbox" data-testid="inbox">
      <li v-if="items.length === 0">알림이 없다</li>
      <li v-for="item in items" :key="item.id" :class="{ unread: !item.read }" data-testid="inbox-item" @click="go(item)">
        <strong>{{ item.actorName }}</strong> {{ KIND[item.kind] }}: {{ item.preview }}
      </li>
    </ul>
  </div>
</template>

<style scoped>
.bell {
  position: relative;
  margin-bottom: 8px;
}

.badge {
  display: inline-block;
  min-width: 18px;
  padding: 0 4px;
  border-radius: 9px;
  background: #e5484d;
  color: #fff;
  font-size: 0.8em;
}

.inbox {
  position: absolute;
  z-index: 30;
  width: 280px;
  max-height: 320px;
  overflow-y: auto;
  margin: 4px 0;
  padding: 4px 0;
  list-style: none;
  background: #fff;
  border: 1px solid #ddd;
  border-radius: 6px;
  box-shadow: 0 4px 16px rgb(0 0 0 / 12%);
}

.inbox li {
  padding: 6px 10px;
  cursor: pointer;
}

.inbox li.unread {
  background: #f5f8ff;
}
</style>

import { defineStore } from 'pinia'
import { reactive, ref } from 'vue'
import { workspaceApi } from '../api/workspace'
import type { PageNode, Workspace } from '../api/workspace'

/** 최상위 페이지 목록의 열쇠. */
const ROOT = ''

/**
 * 지금 워크스페이스와 사이드바 트리 (docs/10-FRONTEND.md 12.1). 하위 목록은 펼칠 때 한 단계씩 읽는다(8.6). 다른 사람의 트리
 * 변경을 바로 받는 일(W-5, SSE)은 아직 없다. 내가 만든 페이지와 내가 고친 제목은 바로 반영한다.
 */
export const useWorkspace = defineStore('workspace', () => {
  const workspaces = ref<Workspace[]>([])
  const current = ref<Workspace | null>(null)
  const children = reactive(new Map<string, PageNode[]>())
  const expanded = reactive(new Set<string>())

  async function loadWorkspaces(): Promise<Workspace[]> {
    workspaces.value = await workspaceApi.list()
    return workspaces.value
  }

  async function create(name: string): Promise<Workspace> {
    const workspace = await workspaceApi.create(name)
    workspaces.value = [...workspaces.value, workspace]
    return workspace
  }

  async function open(workspaceId: string): Promise<void> {
    if (current.value?.id === workspaceId) {
      return
    }
    let found = workspaces.value.find((workspace) => workspace.id === workspaceId)
    if (found === undefined) {
      // 방금 초대받았거나 처음 연 화면이다. 목록을 다시 읽는다.
      found = (await loadWorkspaces()).find((workspace) => workspace.id === workspaceId)
    }
    current.value = found ?? null
    children.clear()
    expanded.clear()
    if (current.value !== null) {
      await loadChildren(null)
    }
  }

  async function loadChildren(parentId: string | null): Promise<void> {
    if (current.value === null) {
      return
    }
    children.set(parentId ?? ROOT, await workspaceApi.children(current.value.id, parentId))
  }

  function childrenOf(parentId: string | null): PageNode[] | undefined {
    return children.get(parentId ?? ROOT)
  }

  async function toggle(pageId: string): Promise<void> {
    if (expanded.has(pageId)) {
      expanded.delete(pageId)
      return
    }
    expanded.add(pageId)
    await loadChildren(pageId)
  }

  /** 페이지를 만들고 부모를 펼쳐 새 페이지가 보이게 한다. */
  async function createPage(parentId: string | null): Promise<PageNode> {
    const workspace = current.value as Workspace
    const page = await workspaceApi.createPage(workspace.id, parentId)
    if (parentId !== null) {
      expanded.add(parentId)
      for (const list of children.values()) {
        const parent = list.find((node) => node.id === parentId)
        if (parent !== undefined) {
          parent.hasChildren = true
        }
      }
    }
    await loadChildren(parentId)
    return page
  }

  /** 편집기에서 제목 줄을 고치면 사이드바에도 바로 보인다. 서버에도 커밋 뒤 따로 저장된다(D-21). */
  function setTitle(pageId: string, title: string): void {
    for (const list of children.values()) {
      const node = list.find((page) => page.id === pageId)
      if (node !== undefined) {
        node.title = title
      }
    }
  }

  function reset(): void {
    workspaces.value = []
    current.value = null
    children.clear()
    expanded.clear()
  }

  return {
    workspaces,
    current,
    expanded,
    loadWorkspaces,
    create,
    open,
    loadChildren,
    childrenOf,
    toggle,
    createPage,
    setTitle,
    reset,
  }
})

import type { PageRole } from './role'
import type { PersistedSession } from './clientSession'

/**
 * 문서마다 보내지 못한 편집과 마지막 확정 문서를 IndexedDB 에 남긴다 (docs/10-FRONTEND.md 12.4, R-6).
 *
 * - 열쇠는 사용자와 문서다. 다른 사람이 같은 브라우저로 로그인해도 앞 사람의 편집을 이어받지 않는다
 * - 한 문서를 남기는 탭은 하나다. Web Locks 로 그 문서의 잠금을 잡은 탭만 읽고 쓴다. 같은 문서를 탭 두 개로 열면 뒤의 탭은
 *   메모리에만 둔다. 두 탭이 같은 clientId 를 이어받으면 서버가 순번을 헷갈린다
 * - 로그아웃하면 모두 지운다. 공용 PC 에 문서가 남지 않게 하기 위해서다
 */
export interface OfflineRecord {
  session: PersistedSession
  /** 마지막으로 받은 내 등급. 연결 전에도 편집할 수 있게 한다. 서버가 welcome 에서 다시 정한다. */
  role: PageRole | null
  savedAt: number
}

export interface OfflineStore {
  load(): Promise<OfflineRecord | null>
  save(record: OfflineRecord): Promise<void>
  /** 이 탭이 이 문서를 남기기로 했는지. 잠금을 잡지 못했으면 false 이고 load 와 save 는 아무것도 하지 않는다. */
  readonly owned: boolean
  release(): void
}

const DB_NAME = 'copage'
const LOCK_WAIT_MS = 1_000
const STORE = 'docs'

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, 1)
    request.onupgradeneeded = () => request.result.createObjectStore(STORE)
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
}

function run<T>(mode: IDBTransactionMode, work: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  return openDb().then(
    (db) =>
      new Promise<T>((resolve, reject) => {
        const tx = db.transaction(STORE, mode)
        const request = work(tx.objectStore(STORE))
        tx.oncomplete = () => {
          db.close()
          resolve(request.result)
        }
        tx.onerror = () => {
          db.close()
          reject(tx.error)
        }
      }),
  )
}

/**
 * 이 문서의 잠금을 잡고 저장소를 연다. 잠금은 탭이 닫히거나 release 할 때까지 쥔다. Web Locks 나 IndexedDB 가 없는 환경(사생활
 * 보호 모드 일부)에서는 owned 가 false 인 저장소를 돌려준다. 그래도 편집은 메모리에서 된다.
 */
export async function openOfflineStore(userId: string, docId: string): Promise<OfflineStore> {
  const key = `${userId}:${docId}`
  const none: OfflineStore = {
    owned: false,
    load: async () => null,
    save: async () => undefined,
    release: () => undefined,
  }
  if (typeof indexedDB === 'undefined' || typeof navigator === 'undefined' || navigator.locks === undefined) {
    return none
  }
  let release: () => void = () => undefined
  // 같은 탭에서 앞 편집기가 막 닫히며 잠금을 푸는 중일 수 있어 잠시 기다린다. 다른 탭이 쥐고 있으면 풀지 않으므로 포기한다.
  const abort = new AbortController()
  const timeout = setTimeout(() => abort.abort(), LOCK_WAIT_MS)
  const held = await new Promise<boolean>((resolve) => {
    navigator.locks
      .request(`copage-doc:${key}`, { signal: abort.signal }, () => {
        clearTimeout(timeout)
        resolve(true)
        // 잡은 채로 둔다. release 가 불리면 풀린다.
        return new Promise<void>((done) => (release = done))
      })
      .catch(() => resolve(false))
  })
  if (!held) {
    return none
  }
  return {
    owned: true,
    load: async () => {
      try {
        return ((await run('readonly', (store) => store.get(key))) as OfflineRecord | undefined) ?? null
      } catch (e) {
        console.warn('보관한 편집을 읽지 못했다', e)
        return null
      }
    },
    save: async (record) => {
      try {
        await run('readwrite', (store) => store.put(record, key))
      } catch (e) {
        console.warn('편집을 브라우저에 남기지 못했다', e)
      }
    },
    release: () => release(),
  }
}

/** 로그아웃할 때 모든 문서의 기록을 지운다. */
export async function clearOfflineStore(): Promise<void> {
  if (typeof indexedDB === 'undefined') {
    return
  }
  try {
    await run('readwrite', (store) => store.clear())
  } catch (e) {
    console.warn('브라우저에 남긴 편집을 지우지 못했다', e)
  }
}

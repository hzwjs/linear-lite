import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick, ref } from 'vue'
import type { AssigneeOption } from '../types/domain'
import { useAuthStore } from '../store/authStore'
import { useDefaultAssigneeToSelf } from './useDefaultAssigneeToSelf'

vi.mock('../services/api/auth', () => ({
  authApi: {
    startDocumentAssetSession: vi.fn().mockResolvedValue(undefined),
    logout: vi.fn().mockResolvedValue(undefined)
  }
}))

const SELF_KEY = 'user:7'

function tokenFor(id: number) {
  return `header.${btoa(JSON.stringify({ sub: String(id) }))}.signature`
}

function memberOptions(): AssigneeOption[] {
  return [
    { key: 'user:8', name: '李明', principalType: 'human' },
    { key: SELF_KEY, name: '黄志文', principalType: 'human' }
  ]
}

function signInAsSelf() {
  setActivePinia(createPinia())
  useAuthStore().setSession(tokenFor(7), 7, '黄志文')
}

beforeEach(() => {
  localStorage.clear()
})

describe('新建任务的默认负责人', () => {
  it('选项已加载时默认选中当前操作用户', () => {
    signInAsSelf()
    const assignees = ref(memberOptions())
    const { assigneeKey, resetAssigneeToDefault } = useDefaultAssigneeToSelf(() => assignees.value)

    resetAssigneeToDefault()

    expect(assigneeKey.value).toBe(SELF_KEY)
  })

  it('选项异步返回后补齐默认负责人', async () => {
    signInAsSelf()
    const assignees = ref<AssigneeOption[]>([])
    const { assigneeKey, resetAssigneeToDefault } = useDefaultAssigneeToSelf(() => assignees.value)

    resetAssigneeToDefault()
    expect(assigneeKey.value).toBe('')

    assignees.value = memberOptions()
    await nextTick()

    expect(assigneeKey.value).toBe(SELF_KEY)
  })

  it('手动换人和主动选未分配后不再被默认值覆盖', async () => {
    signInAsSelf()
    const assignees = ref<AssigneeOption[]>([])
    const { assigneeKey, pickAssignee, resetAssigneeToDefault } = useDefaultAssigneeToSelf(() => assignees.value)

    resetAssigneeToDefault()
    pickAssignee('user:8')
    assignees.value = memberOptions()
    await nextTick()
    expect(assigneeKey.value).toBe('user:8')

    pickAssignee('')
    assignees.value = memberOptions()
    await nextTick()
    expect(assigneeKey.value).toBe('')
  })

  it('重新打开表单时回到当前操作用户', () => {
    signInAsSelf()
    const assignees = ref(memberOptions())
    const { assigneeKey, pickAssignee, resetAssigneeToDefault } = useDefaultAssigneeToSelf(() => assignees.value)

    pickAssignee('')
    resetAssigneeToDefault()

    expect(assigneeKey.value).toBe(SELF_KEY)
  })

  it('当前用户不在可选负责人里时保持未分配', () => {
    signInAsSelf()
    const assignees = ref<AssigneeOption[]>([{ key: 'user:8', name: '李明', principalType: 'human' }])
    const { assigneeKey, resetAssigneeToDefault } = useDefaultAssigneeToSelf(() => assignees.value)

    resetAssigneeToDefault()

    expect(assigneeKey.value).toBe('')
  })

  it('未登录时不设置默认负责人', () => {
    setActivePinia(createPinia())
    const assignees = ref(memberOptions())
    const { assigneeKey, resetAssigneeToDefault } = useDefaultAssigneeToSelf(() => assignees.value)

    resetAssigneeToDefault()

    expect(assigneeKey.value).toBe('')
  })
})

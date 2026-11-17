import { ref, toValue, watch, type MaybeRefOrGetter } from 'vue'
import { useAuthStore } from '../store/authStore'
import type { AssigneeOption } from '../types/domain'
import { userAssigneeKey } from '../utils/assigneeKey'

/**
 * 新建任务时的负责人默认值：当前操作用户。
 *
 * - 负责人选项按项目异步加载，所以选项到位后再补一次默认值，避免默认负责人显示为空。
 * - 当前用户不在该项目的可选负责人里时保持未分配：后端只接受项目内负责人。
 * - 用户手动选过负责人（含主动选「未分配」）后不再覆盖。
 */
export function useDefaultAssigneeToSelf(assignees: MaybeRefOrGetter<AssigneeOption[]>) {
  const authStore = useAuthStore()
  const assigneeKey = ref('')
  const manuallyPicked = ref(false)

  function selfAssigneeKey(): string {
    const userId = authStore.currentUser?.id
    if (userId == null) return ''
    const key = userAssigneeKey(userId)
    return toValue(assignees).some((option) => option.key === key) ? key : ''
  }

  function applyDefault() {
    if (manuallyPicked.value) return
    assigneeKey.value = selfAssigneeKey()
  }

  // 选项可能在表单打开之后才返回，到位时补齐默认负责人。
  watch(() => toValue(assignees), applyDefault)

  function pickAssignee(key: string) {
    manuallyPicked.value = true
    assigneeKey.value = key
  }

  /** 表单打开或提交后重置时调用，重新按当前用户填充默认负责人。 */
  function resetAssigneeToDefault() {
    manuallyPicked.value = false
    applyDefault()
  }

  return { assigneeKey, pickAssignee, resetAssigneeToDefault }
}

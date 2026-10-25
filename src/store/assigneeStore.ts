import { defineStore } from 'pinia'
import { ref } from 'vue'
import { projectApi } from '../services/api/project'
import type { AssigneeOption } from '../types/domain'

export const useAssigneeStore = defineStore('assigneeStore', () => {
  const optionsByProject = ref<Record<number, AssigneeOption[]>>({})
  const pending = new Map<number, Promise<void>>()

  async function fetchProjectAssignees(projectId: number): Promise<void> {
    if (optionsByProject.value[projectId] != null) return
    const current = pending.get(projectId)
    if (current) return current

    const request = projectApi.listAssignees(projectId).then((options) => {
      optionsByProject.value = { ...optionsByProject.value, [projectId]: options }
    }).finally(() => {
      pending.delete(projectId)
    })
    pending.set(projectId, request)
    return request
  }

  function invalidateProjectAssignees(projectId: number) {
    const next = { ...optionsByProject.value }
    delete next[projectId]
    optionsByProject.value = next
  }

  return { optionsByProject, fetchProjectAssignees, invalidateProjectAssignees }
})

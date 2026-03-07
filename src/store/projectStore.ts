import { defineStore } from 'pinia'
import { ref } from 'vue'
import { projectApi } from '../services/api/project'
import type { Project } from '../types/domain'

export const useProjectStore = defineStore('projectStore', () => {
  const projects = ref<Project[]>([])
  const activeProjectId = ref<number | null>(null)

  async function fetchProjects() {
    const list = await projectApi.list()
    projects.value = list
    const first = list[0]
    if (first && activeProjectId.value == null) {
      activeProjectId.value = first.id
    }
    return list
  }

  function setActiveProject(id: number | null) {
    activeProjectId.value = id
  }

  async function createProject(body: { name: string; identifier: string }) {
    const project = await projectApi.create(body)
    projects.value = await projectApi.list()
    return project
  }

  async function updateProject(
    id: number,
    body: { name?: string; identifier?: string }
  ) {
    const project = await projectApi.update(id, body)
    const idx = projects.value.findIndex((p) => p.id === id)
    if (idx !== -1) projects.value[idx] = project
    return project
  }

  return {
    projects,
    activeProjectId,
    fetchProjects,
    setActiveProject,
    createProject,
    updateProject
  }
})

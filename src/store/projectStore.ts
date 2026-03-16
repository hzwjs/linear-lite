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
    const activeStillExists = list.some((project) => project.id === activeProjectId.value)
    if (first && (activeProjectId.value == null || !activeStillExists)) {
      activeProjectId.value = first.id
    } else if (!first) {
      activeProjectId.value = null
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

  async function deleteProject(id: number) {
    await projectApi.delete(id)
    projects.value = projects.value.filter((project) => project.id !== id)
    if (activeProjectId.value === id) {
      activeProjectId.value = projects.value[0]?.id ?? null
    }
  }

  return {
    projects,
    activeProjectId,
    fetchProjects,
    setActiveProject,
    createProject,
    updateProject,
    deleteProject
  }
})

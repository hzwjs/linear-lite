import { defineStore } from 'pinia'
import { ref } from 'vue'
import { projectApi } from '../services/api/project'
import type { Project } from '../types/domain'

const ACTIVE_PROJECT_KEY = 'linear-lite-active-project'

function getStoredActiveProjectId(): number | null {
  try {
    const raw = localStorage.getItem(ACTIVE_PROJECT_KEY)
    if (raw == null) return null
    const n = Number(raw)
    return Number.isInteger(n) ? n : null
  } catch {
    return null
  }
}

function persistActiveProjectId(id: number | null) {
  try {
    if (id == null) localStorage.removeItem(ACTIVE_PROJECT_KEY)
    else localStorage.setItem(ACTIVE_PROJECT_KEY, String(id))
  } catch {}
}

export const useProjectStore = defineStore('projectStore', () => {
  const projects = ref<Project[]>([])
  const activeProjectId = ref<number | null>(getStoredActiveProjectId())

  async function fetchProjects() {
    const list = await projectApi.list()
    projects.value = list
    const first = list[0]
    const activeStillExists = list.some((project) => project.id === activeProjectId.value)
    if (first && (activeProjectId.value == null || !activeStillExists)) {
      activeProjectId.value = first.id
      persistActiveProjectId(activeProjectId.value)
    } else if (!first) {
      activeProjectId.value = null
      persistActiveProjectId(null)
    }
    return list
  }

  function setActiveProject(id: number | null) {
    activeProjectId.value = id
    persistActiveProjectId(id)
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
      persistActiveProjectId(activeProjectId.value)
    }
  }

  async function inviteToProject(id: number, email: string) {
    await projectApi.invite(id, { email })
  }

  return {
    projects,
    activeProjectId,
    fetchProjects,
    setActiveProject,
    createProject,
    updateProject,
    deleteProject,
    inviteToProject
  }
})

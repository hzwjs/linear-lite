import { createRouter, createWebHistory } from 'vue-router'
import type { Pinia } from 'pinia'
import { useAuthStore } from '../store/authStore'
import { tryRecoverDynamicImport } from '../utils/dynamicImportRecovery'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'home',
      component: () => import('../views/BoardView.vue')
    },
    {
      path: '/projects/:projectId/tasks/:taskId',
      name: 'task-detail',
      component: () => import('../views/BoardView.vue')
    },
    {
      path: '/projects/:projectId/documents',
      name: 'project-documents',
      component: () => import('../views/DocumentsView.vue')
    },
    {
      path: '/projects/:projectId/documents/:documentId',
      name: 'project-document-detail',
      component: () => import('../views/DocumentsView.vue')
    },
    {
      path: '/projects/:projectId/settings',
      name: 'project-settings',
      component: () => import('../views/ProjectSettingsView.vue')
    },
    {
      path: '/tasks/:taskId',
      name: 'task-detail-legacy',
      component: () => import('../views/BoardView.vue')
    },
    {
      path: '/analytics',
      name: 'analytics',
      component: () => import('../views/AnalyticsView.vue')
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/LoginView.vue')
    }
  ]
})

export function setupRouterGuards(pinia: Pinia) {
  router.beforeEach((to) => {
    const authStore = useAuthStore(pinia)
    const loggedIn = authStore.isLoggedIn
    if (to.name === 'login') {
      if (loggedIn) return { path: '/' }
      return true
    }
    if (!loggedIn) {
      return { path: '/login' }
    }
    return true
  })

  router.onError((error) => {
    if (tryRecoverDynamicImport(error)) return
    console.error('Router navigation error:', error)
  })
}

export default router

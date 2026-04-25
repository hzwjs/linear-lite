import { createRouter, createWebHistory } from 'vue-router'
import type { Pinia } from 'pinia'
import { useAuthStore } from '../store/authStore'

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
  router.beforeEach((to, _from, next) => {
    const authStore = useAuthStore(pinia)
    const loggedIn = authStore.isLoggedIn
    if (to.name === 'login') {
      if (loggedIn) next({ path: '/' })
      else next()
      return
    }
    if (!loggedIn) {
      next({ path: '/login' })
      return
    }
    next()
  })
}

export default router

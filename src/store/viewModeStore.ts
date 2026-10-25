import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import {
  getStoredViewConfig,
  setStoredViewConfig,
  normalizeViewConfig,
  type GroupBy,
  type OrderBy,
  type OrderDirection,
  type ViewConfig,
  type ViewType,
  type VisibleProperty
} from '../utils/viewPreference'
import type { TaskScope } from '../utils/taskScope'

/**
 * 视图模式（Board/List）响应式状态，与 Command Palette 及 BoardView 共享；
 * 持久化到 localStorage。
 */
export const useViewModeStore = defineStore('viewModeStore', () => {
  const viewConfig = ref<ViewConfig>(getStoredViewConfig())

  watch(
    viewConfig,
    (config) => {
      setStoredViewConfig(config)
    },
    { deep: true }
  )

  const viewType = computed(() => viewConfig.value.layout)
  const isList = computed(() => viewConfig.value.layout === 'list')
  const groupBy = computed(() =>
    isList.value ? viewConfig.value.listGroupBy : viewConfig.value.groupBy
  )
  const effectiveViewConfig = computed<ViewConfig>(() => ({
    ...viewConfig.value,
    groupBy: groupBy.value
  }))
  const visibleProperties = computed(() => [...viewConfig.value.visibleProperties].sort())

  function setView(v: ViewType) {
    viewConfig.value = {
      ...viewConfig.value,
      layout: v
    }
  }

  function setGroupBy(groupBy: GroupBy) {
    viewConfig.value = {
      ...viewConfig.value,
      ...(isList.value ? { listGroupBy: groupBy } : { groupBy })
    }
  }

  function setOrderBy(orderBy: OrderBy) {
    viewConfig.value = {
      ...viewConfig.value,
      orderBy
    }
  }

  function setOrderDirection(orderDirection: OrderDirection) {
    viewConfig.value = {
      ...viewConfig.value,
      orderDirection
    }
  }

  function toggleVisibleProperty(property: VisibleProperty) {
    const next = new Set(viewConfig.value.visibleProperties)
    if (next.has(property)) {
      next.delete(property)
    } else {
      next.add(property)
    }
    viewConfig.value = {
      ...viewConfig.value,
      visibleProperties: [...next]
    }
  }

  function setScope(scope: TaskScope) {
    viewConfig.value = {
      ...viewConfig.value,
      scope
    }
  }

  function setShowEmptyGroups(showEmptyGroups: boolean) {
    viewConfig.value = {
      ...viewConfig.value,
      showEmptyGroups
    }
  }

  function setShowSubIssues(showSubIssues: boolean) {
    viewConfig.value = {
      ...viewConfig.value,
      showSubIssues
    }
  }

  function setNestedSubIssues(nestedSubIssues: boolean) {
    viewConfig.value = {
      ...viewConfig.value,
      nestedSubIssues
    }
  }

  /** 按项目恢复视图配置等场景下整体替换（会规范化字段） */
  function hydrateViewConfig(next: ViewConfig) {
    viewConfig.value = normalizeViewConfig(next)
  }

  return {
    viewConfig,
    effectiveViewConfig,
    groupBy,
    viewType,
    visibleProperties,
    setView,
    setGroupBy,
    setOrderBy,
    setOrderDirection,
    toggleVisibleProperty,
    setScope,
    setShowEmptyGroups,
    setShowSubIssues,
    setNestedSubIssues,
    hydrateViewConfig
  }
})

<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ProjectSettingsModal from '../components/ProjectSettingsModal.vue'
import { useFavoriteStore } from '../store/favoriteStore'
import { useProjectStore } from '../store/projectStore'

const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()
const favoriteStore = useFavoriteStore()

const projectId = computed(() => Number(route.params.projectId))
const project = computed(() =>
  projectStore.projects.find((item) => item.id === projectId.value) ?? null
)

// 设置页与项目工作区共享同一个当前项目，确保保存、导入后的刷新目标唯一。
watch(projectId, (id) => {
  if (Number.isFinite(id) && projectStore.activeProjectId !== id) {
    projectStore.setActiveProject(id)
  }
}, { immediate: true })

function leaveSettings() {
  void router.push('/')
}

function handleDeleted() {
  void favoriteStore.fetchFavorites()
  leaveSettings()
}
</script>

<template>
  <ProjectSettingsModal
    v-if="project"
    :open="true"
    :project="project"
    @close="leaveSettings"
    @updated="() => {}"
    @deleted="handleDeleted"
  />
  <div v-else class="settings-missing">
    <p>{{ $t('emptyState.selectProject') }}</p>
  </div>
</template>

<style scoped>
.settings-missing {
  flex: 1;
  display: grid;
  place-items: center;
  color: var(--color-text-secondary);
}
</style>

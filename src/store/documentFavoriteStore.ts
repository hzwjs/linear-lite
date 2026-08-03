import { defineStore } from 'pinia'
import { ref } from 'vue'
import { documentApi } from '../services/api/documents'
import type { ProjectDocumentTreeNode } from '../types/document'

export const useDocumentFavoriteStore = defineStore('documentFavoriteStore', () => {
  const favorites = ref<ProjectDocumentTreeNode[]>([])
  const isLoading = ref(false)

  function syncDocument(document: ProjectDocumentTreeNode) {
    const index = favorites.value.findIndex((item) => item.id === document.id)
    if (document.favorited) {
      if (index === -1) favorites.value = [document, ...favorites.value]
      else favorites.value[index] = document
      return
    }
    if (index !== -1) favorites.value = favorites.value.filter((item) => item.id !== document.id)
  }

  function removeDocument(documentId: number) {
    favorites.value = favorites.value.filter((item) => item.id !== documentId)
  }

  async function fetchFavorites() {
    isLoading.value = true
    try {
      favorites.value = await documentApi.listFavorites()
      return favorites.value
    } finally {
      isLoading.value = false
    }
  }

  return { favorites, isLoading, syncDocument, removeDocument, fetchFavorites }
})

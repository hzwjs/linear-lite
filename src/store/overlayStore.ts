import { defineStore } from 'pinia'
import { ref } from 'vue'

type OverlayEntry = { id: string; close: () => void }

/**
 * 浮层栈：Esc 关闭当前最上层浮层（Command Palette / Drawer / Modal）。
 * 各浮层打开时 push(id, close)，关闭时 remove(id)。
 */
export const useOverlayStore = defineStore('overlayStore', () => {
  const stack = ref<OverlayEntry[]>([])

  function push(id: string, close: () => void) {
    const existing = stack.value.findIndex((e) => e.id === id)
    if (existing !== -1) stack.value.splice(existing, 1)
    stack.value.push({ id, close })
  }

  function remove(id: string) {
    stack.value = stack.value.filter((e) => e.id !== id)
  }

  function popAndClose() {
    const top = stack.value.pop()
    if (top) top.close()
  }

  return { stack, push, remove, popAndClose }
})

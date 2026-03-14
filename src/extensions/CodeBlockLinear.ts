import { CodeBlockLowlight } from '@tiptap/extension-code-block-lowlight'
import { VueNodeViewRenderer } from '@tiptap/vue-3'
import CodeBlockLinearView from '../components/CodeBlockLinearView.vue'

export function createCodeBlockLinear(config: { lowlight: any; defaultLanguage?: string | null }) {
  return CodeBlockLowlight.extend({
    addNodeView() {
      return VueNodeViewRenderer(CodeBlockLinearView)
    },
  }).configure({
    lowlight: config.lowlight,
    defaultLanguage: config.defaultLanguage ?? 'bash',
  })
}

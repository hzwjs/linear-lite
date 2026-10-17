import {
  BlockNoteSchema,
  createBlockSpec,
  createCodeBlockSpec,
  createInlineContentSpec,
  defaultBlockSpecs,
  defaultInlineContentSpecs,
  type BlockSpec,
  type InlineContentSpec,
} from '@blocknote/core'

export const DOCUMENT_IMAGE_CONFIG = {
  type: 'documentImage',
  propSchema: {
    imageAssetId: { default: 0 },
    altText: { default: '' },
    caption: { default: '' },
  },
  content: 'none',
} as const

export const MENTION_CONFIG = {
  type: 'mention',
  propSchema: {
    userId: { default: '' },
    label: { default: '' },
  },
  content: 'none',
} as const

export const SUPPORTED_CODE_BLOCK_LANGUAGES: Record<string, { name: string; aliases?: string[] }> = {
  text: { name: 'Plain Text' },
  javascript: { name: 'JavaScript', aliases: ['js'] },
  typescript: { name: 'TypeScript', aliases: ['ts'] },
  python: { name: 'Python', aliases: ['py'] },
  java: { name: 'Java' },
  go: { name: 'Go', aliases: ['golang'] },
  rust: { name: 'Rust' },
  cpp: { name: 'C++', aliases: ['c++'] },
  c: { name: 'C' },
  csharp: { name: 'C#', aliases: ['cs'] },
  php: { name: 'PHP' },
  ruby: { name: 'Ruby', aliases: ['rb'] },
  swift: { name: 'Swift' },
  kotlin: { name: 'Kotlin' },
  html: { name: 'HTML' },
  css: { name: 'CSS' },
  scss: { name: 'SCSS' },
  sql: { name: 'SQL' },
  json: { name: 'JSON' },
  yaml: { name: 'YAML', aliases: ['yml'] },
  xml: { name: 'XML' },
  bash: { name: 'Bash', aliases: ['sh', 'shell'] },
  markdown: { name: 'Markdown', aliases: ['md'] },
  mermaid: { name: 'Mermaid', aliases: ['mmd'] },
}

export const BLOCKNOTE_FRAGMENT = 'document-store'
export const HTML_TEXT_FIELD = 'html'
export const BLOCKNOTE_MAX_BYTES = 10 * 1024 * 1024
export const HTML_MAX_BYTES = 5 * 1024 * 1024

type DocumentSchemaSpecs = {
  documentImageSpec: BlockSpec
  mentionSpec: InlineContentSpec<any>
  codeBlockSpec?: BlockSpec
}

export function createDocumentSchema(specs?: Partial<DocumentSchemaSpecs>) {
  const { image: _image, ...defaultDocumentBlocks } = defaultBlockSpecs
  const documentImageSpec = specs?.documentImageSpec ?? createBlockSpec(
    DOCUMENT_IMAGE_CONFIG,
    {
      render: (block) => {
        const dom = document.createElement('span')
        dom.setAttribute('data-image-asset-id', String(block.props.imageAssetId))
        dom.setAttribute('data-alt-text', String(block.props.altText))
        dom.setAttribute('data-caption', String(block.props.caption))
        return { dom }
      },
      parse: (element) => {
        const imageAssetId = Number(element.getAttribute('data-image-asset-id'))
        if (!Number.isSafeInteger(imageAssetId) || imageAssetId <= 0) return undefined
        return {
          imageAssetId,
          altText: element.getAttribute('data-alt-text') ?? '',
          caption: element.getAttribute('data-caption') ?? '',
        }
      },
      toExternalHTML: (block) => {
        const dom = document.createElement('img')
        dom.setAttribute('data-image-asset-id', String(block.props.imageAssetId))
        dom.setAttribute('data-alt-text', String(block.props.altText))
        dom.setAttribute('data-caption', String(block.props.caption))
        return { dom }
      },
    },
  )()
  const mentionSpec = specs?.mentionSpec ?? createInlineContentSpec(MENTION_CONFIG, {
    render: (inlineContent) => {
      const dom = document.createElement('span')
      dom.textContent = `@${inlineContent.props.label}`
      return { dom }
    },
    parse: (element) => {
      const label = element.textContent?.replace(/^@/, '') ?? ''
      const userId = element.getAttribute('data-user-id')
      if (!userId) return undefined
      return { userId, label }
    },
  })

  return BlockNoteSchema.create({
    blockSpecs: {
      ...defaultDocumentBlocks,
      codeBlock: specs?.codeBlockSpec ?? createCodeBlockSpec({ supportedLanguages: SUPPORTED_CODE_BLOCK_LANGUAGES }),
      documentImage: documentImageSpec,
    },
    inlineContentSpecs: {
      ...defaultInlineContentSpecs,
      mention: mentionSpec,
    },
  })
}

export function extractImageAssetIds(blocks: unknown): number[] {
  const ids = new Set<number>()
  const visit = (value: unknown) => {
    if (!Array.isArray(value)) return
    for (const candidate of value) {
      if (!candidate || typeof candidate !== 'object') continue
      const block = candidate as { type?: unknown; props?: { imageAssetId?: unknown }; children?: unknown }
      if (block.type === 'documentImage') {
        const assetId = Number(block.props?.imageAssetId)
        if (Number.isSafeInteger(assetId) && assetId > 0) ids.add(assetId)
      }
      visit(block.children)
    }
  }
  visit(blocks)
  return [...ids].sort((left, right) => left - right)
}

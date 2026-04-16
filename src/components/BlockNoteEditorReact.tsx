/** @jsxImportSource react */
import { useCallback, useEffect, useMemo, useRef } from 'react'
import '@blocknote/mantine/style.css'
import { BlockNoteView } from '@blocknote/mantine'
import {
  SuggestionMenuController,
  createReactInlineContentSpec,
  useCreateBlockNote,
} from '@blocknote/react'
import {
  BlockNoteSchema,
  defaultBlockSpecs,
  defaultInlineContentSpecs,
  filterSuggestionItems,
  type BlockNoteEditor,
  type PartialBlock,
} from '@blocknote/core'
import { createCodeBlockSpec } from '@blocknote/core/blocks'
import { parseBlockNoteStoredBlocks } from '../utils/blockNoteDescription'

// ─── Code block with language selector ─────────────────────────────────────────

const codeBlockWithLanguages = createCodeBlockSpec({
  supportedLanguages: {
    text:       { name: 'Plain Text' },
    javascript: { name: 'JavaScript', aliases: ['js'] },
    typescript: { name: 'TypeScript', aliases: ['ts'] },
    python:     { name: 'Python',     aliases: ['py'] },
    java:       { name: 'Java' },
    go:         { name: 'Go',         aliases: ['golang'] },
    rust:       { name: 'Rust' },
    cpp:        { name: 'C++',        aliases: ['c++'] },
    c:          { name: 'C' },
    csharp:     { name: 'C#',         aliases: ['cs'] },
    php:        { name: 'PHP' },
    ruby:       { name: 'Ruby',       aliases: ['rb'] },
    swift:      { name: 'Swift' },
    kotlin:     { name: 'Kotlin' },
    html:       { name: 'HTML' },
    css:        { name: 'CSS' },
    scss:       { name: 'SCSS' },
    sql:        { name: 'SQL' },
    json:       { name: 'JSON' },
    yaml:       { name: 'YAML',       aliases: ['yml'] },
    xml:        { name: 'XML' },
    bash:       { name: 'Bash',       aliases: ['sh', 'shell'] },
    markdown:   { name: 'Markdown',   aliases: ['md'] },
  },
})

// ─── Mention inline content spec ───────────────────────────────────────────────

const mentionSpec = createReactInlineContentSpec(
  {
    type: 'mention' as const,
    propSchema: {
      userId: { default: '' as string },
      label: { default: '' as string },
    },
    content: 'none' as const,
  },
  {
    render: ({ inlineContent }) => (
      <span className="bn-mention">@{inlineContent.props.label}</span>
    ),
  }
)

// Schema shared across all editor instances (includes default text/link + mention,
// and a code block with a language selector)
const schema = BlockNoteSchema.create({
  blockSpecs: {
    ...defaultBlockSpecs,
    codeBlock: codeBlockWithLanguages,
  },
  inlineContentSpecs: {
    ...defaultInlineContentSpecs,
    mention: mentionSpec,
  },
})

// ─── Helpers ────────────────────────────────────────────────────────────────────

type AnyBlock = {
  content?: unknown[]
  children?: AnyBlock[]
}

function extractMentionIdsFromBlocks(blocks: AnyBlock[]): number[] {
  const ids: number[] = []
  for (const block of blocks) {
    if (Array.isArray(block.content)) {
      for (const inline of block.content as Array<{ type?: string; props?: { userId?: string } }>) {
        if (inline.type === 'mention' && inline.props?.userId) {
          const id = parseInt(String(inline.props.userId), 10)
          if (Number.isFinite(id)) ids.push(id)
        }
      }
    }
    if (Array.isArray(block.children) && block.children.length > 0) {
      ids.push(...extractMentionIdsFromBlocks(block.children))
    }
  }
  return [...new Set(ids)]
}

// ─── Component ──────────────────────────────────────────────────────────────────

export interface EditorApi {
  focus: () => void
  getMentionedUserIds: () => number[]
}

/** Vue/veaury 可能以 `upload-file` 传入，与 React 的 `uploadFile` 并存 */
export type BlockNoteEditorReactProps = {
  /** BlockNote 存库的 JSON（Block[]）或 Markdown 文本（含以 `[` 开头的链接等） */
  initialContent?: string
  placeholder?: string
  editable?: boolean
  mentionMembers?: Array<{ id: number; label: string }>
  /** Should resolve the uploaded file URL */
  uploadFile?: (file: File) => Promise<string>
  'upload-file'?: (file: File) => Promise<string>
  /** Called on every document change with serialized JSON and mentioned user IDs */
  onChange?: (jsonString: string, mentionedUserIds: number[]) => void
  'on-change'?: (jsonString: string, mentionedUserIds: number[]) => void
  onBlur?: () => void
  'on-blur'?: () => void
  /** Called once when the editor is mounted, receives imperative API */
  onInit?: (api: EditorApi) => void
  'on-init'?: (api: EditorApi) => void
  /** 任务描述：侧栏（+ / 拖拽）与 `/` slash 菜单；其它场景保持关闭以减小干扰 */
  blockChrome?: boolean
  'block-chrome'?: boolean
}

export default function BlockNoteEditorReact(props: BlockNoteEditorReactProps) {
  const {
    initialContent,
    placeholder,
    editable = true,
    mentionMembers,
    uploadFile,
    onChange,
    onBlur,
    onInit,
    blockChrome = false,
  } = props

  const blockChromeOn = blockChrome === true || props['block-chrome'] === true

  const uploadFileResolved =
    uploadFile ?? props['upload-file']

  const uploadFileRef = useRef(uploadFileResolved)
  uploadFileRef.current = uploadFileResolved

  /** BlockNote 只在创建 editor 时读 options；用稳定函数 + ref 承接 Vue 侧晚到或 `upload-file` 命名的回调 */
  const blockNoteUploadFile = useCallback((file: File) => {
    const fn = uploadFileRef.current
    if (!fn) {
      return Promise.reject(new Error('uploadFile not configured'))
    }
    return fn(file)
  }, [])

  const mentionMembersRef = useRef(mentionMembers)
  mentionMembersRef.current = mentionMembers

  const onChangeResolved = onChange ?? props['on-change']
  const onChangeRef = useRef(onChangeResolved)
  onChangeRef.current = onChangeResolved

  const onBlurResolved = onBlur ?? props['on-blur']
  const onBlurRef = useRef(onBlurResolved)
  onBlurRef.current = onBlurResolved

  const onInitResolved = onInit ?? props['on-init']
  const onInitRef = useRef(onInitResolved)
  onInitRef.current = onInitResolved

  // 仅确认的 BlockNote JSON 作为 initialContent；其余整段交给 mount 后 Markdown 解析
  const parsedJsonInitial = useMemo(() => {
    const raw = (initialContent ?? '').trim()
    if (!raw) return undefined
    return parseBlockNoteStoredBlocks(raw)
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const editor = useCreateBlockNote(
    {
      schema,
      uploadFile: blockNoteUploadFile,
      initialContent: parsedJsonInitial as PartialBlock<any, any, any>[] | undefined,
      // Use deprecated placeholders until dictionary approach is confirmed stable
      placeholders: placeholder ? { default: placeholder } : undefined,
    },
    [] // no deps – editor is stable for the component lifetime
  )

  // 非 BlockNote JSON 的整段内容（含 `[` 开头的 Markdown）在 mount 后解析
  useEffect(() => {
    const raw = (initialContent ?? '').trim()
    if (!raw) return
    if (parseBlockNoteStoredBlocks(raw) !== undefined) return
    try {
      const blocks = editor.tryParseMarkdownToBlocks(raw)
      if (blocks.length > 0) {
        editor.replaceBlocks(editor.document, blocks)
      }
    } catch {
      // Ignore conversion errors; editor will start empty
    }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  // Expose imperative API to Vue wrapper via onInit callback
  useEffect(() => {
    const fn = onInitRef.current
    if (!fn) return
    fn({
      focus: () => editor.focus(),
      getMentionedUserIds: () =>
        extractMentionIdsFromBlocks(editor.document as unknown as AnyBlock[]),
    })
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const handleChange = useCallback((_changedEditor?: unknown) => {
    const jsonString = JSON.stringify(editor.document)
    const mentionedIds = extractMentionIdsFromBlocks(editor.document as unknown as AnyBlock[])
    onChangeRef.current?.(jsonString, mentionedIds)
  }, [editor])

  const handleBlur = useCallback((_e: React.FocusEvent) => {
    onBlurRef.current?.()
  }, [])

  return (
    <BlockNoteView
      editor={editor}
      editable={editable}
      onChange={handleChange}
      onBlur={handleBlur}
      theme="light"
      slashMenu={blockChromeOn}
      sideMenu={blockChromeOn}
      formattingToolbar={false}
      linkToolbar={false}
      filePanel={false}
      tableHandles={false}
      emojiPicker={false}
      comments={false}
    >
      {mentionMembers !== undefined && (
        <SuggestionMenuController
          triggerCharacter="@"
          getItems={async (query) => {
            const members = mentionMembersRef.current ?? []
            const items = members.map((m) => ({
              title: m.label,
              onItemClick: () => {
                editor.insertInlineContent(
                  [
                    {
                      type: 'mention',
                      props: {
                        userId: String(m.id),
                        label: m.label,
                      },
                    },
                    ' ',
                  ] as Parameters<BlockNoteEditor<any, any, any>['insertInlineContent']>[0]
                )
              },
            }))
            return filterSuggestionItems(items, query)
          }}
        />
      )}
    </BlockNoteView>
  )
}

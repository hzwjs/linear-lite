import { HostError } from './device.mjs'

const text = { type: 'string' }
const nonempty = { type: 'string', minLength: 1 }
const id = { type: 'integer', minimum: 1 }
const operationId = { type: 'string', minLength: 1, maxLength: 128, pattern: '^[A-Za-z0-9_-]+$' }
const strings = { type: 'array', items: nonempty }
const object = (properties, required = Object.keys(properties)) => ({ type: 'object', properties, required, additionalProperties: false })
const condition = object({ condition: nonempty, result: nonempty, evidenceRefs: strings })
const dependencyCommon = { missing: nonempty, reason: nonempty, evidence: nonempty }
const dependency = { oneOf: [
  object({ action: { const: 'ADD' }, prerequisiteTaskId: id, ...dependencyCommon, goal: nonempty, impact: nonempty }, ['action', 'prerequisiteTaskId', 'missing', 'reason', 'evidence']),
  object({ action: { const: 'REMOVE' }, prerequisiteTaskId: id, ...dependencyCommon, goal: nonempty, impact: nonempty }, ['action', 'prerequisiteTaskId', 'missing', 'reason', 'evidence']),
  object({ action: { const: 'REQUEST_GOAL' }, ...dependencyCommon, goal: nonempty, impact: nonempty }),
] }

// 此目录同时生成模型工具定义、提示和宿主参数校验。
const actions = {
  observe: { description: '观察批次中的任务与协作事实', payload: object({}) },
  claim: { description: '领取观察到的 READY 任务', payload: object({ taskId: id, inputRevision: id }) },
  list_files: { description: '浏览项目目录。根目录用 .，有 nextAfter 时用作下一页的 after',
    payload: object({ path: nonempty, after: nonempty }, ['path']) },
  read_file: { description: '读取项目文件及版本', payload: object({ path: nonempty }) },
  create_file: { description: '创建项目文件', payload: object({ path: nonempty, content: text, operationId }) },
  apply_patch: { description: '按读取版本替换文件片段', payload: object({ path: nonempty, version: nonempty, oldText: nonempty, newText: text, operationId }) },
  replace_file: { description: '按读取版本替换文件内容', payload: object({ path: nonempty, version: nonempty, content: text, operationId }) },
  remove_file: { description: '按读取版本删除文件', payload: object({ path: nonempty, version: nonempty, operationId }) },
  rename_file: { description: '按读取版本移动文件', payload: object({ from: nonempty, to: nonempty, version: nonempty, operationId }) },
  make_directory: { description: '创建项目目录', payload: object({ path: nonempty, operationId }) },
  list_documents: { description: '查看项目文档目录', payload: object({}) },
  read_document: { description: '读取项目文档', payload: object({ documentId: id }) },
  adopt_context: { description: '采用本次执行中读取的文档和项目文件作为上下文', payload: object({ documentIds: { type: 'array', items: id }, projectPaths: strings }) },
  change_dependency: { description: '按发现的缺失材料增删任务依赖或请求扩展目标', payload: dependency },
  retract_dependency: { description: '依据共享事实撤销宿主提供的已停止尝试所建立的错误关系', payload: object({
    relationOperationId: operationId, ...dependencyCommon,
  }) },
  split_task: { description: '当前任务含多个可独立验证的成果时，在本任务下创建子任务（只写需求与完成条件，不声明依赖）。创建后本次尝试结束，子任务由其他尝试领取', payload: object({
    reason: nonempty,
    subtasks: { type: 'array', minItems: 1, maxItems: 8, items: object({ title: { type: 'string', minLength: 1, maxLength: 256 }, description: { type: 'string', maxLength: 8000 } }, ['title']) },
  }) },
  review_prerequisite: { description: '复核来源任务成果，选择采用、修补、等待或请求返工', payload: object({
    sourceTaskId: id, sourceAttemptId: id, sourceInputRevision: id,
    decision: { type: 'string', enum: ['ADOPT', 'REPAIR_IN_SCOPE', 'WAIT_FOR_CHANGE', 'REQUEST_REWORK'] },
    finding: nonempty, evidence: nonempty, paths: strings, goal: nonempty, impact: nonempty,
  }, ['sourceTaskId', 'sourceAttemptId', 'sourceInputRevision', 'decision', 'finding', 'evidence', 'paths']) },
  checkpoint: { description: '记录产物、发现或阻塞事实', payload: object({ artifactRefs: strings, finding: text, blockedReason: text }, ['artifactRefs']) },
  deliver: {
    description: `提交验收条件和成果引用。conditions 至少包含一项，每项必填 ${condition.required.join('、')}，其中 condition 和 result 为非空字符串，evidenceRefs 为字符串数组`,
    payload: object({ conditions: { type: 'array', items: condition, minItems: 1 }, artifactRefs: strings }),
  },
  stop: { description: '说明无法完成的原因并结束', payload: object({ reason: nonempty }) },
}

const names = Object.keys(actions)
const observeCall = 'tools.host_action({ action: "observe", payload: {} })'
export const HOST_ACTION_TOOL = Object.freeze({
  type: 'function', name: 'host_action',
  description: `观察协作环境、处理项目任务并记录成果。每次传入 action 和 payload，观察示例：${observeCall}。`,
  // Code Mode 从顶层对象生成参数签名，动作专属字段由宿主按同一目录校验。
  inputSchema: object({ action: { type: 'string', enum: names }, payload: { type: 'object', additionalProperties: true } }),
})

function describePayload(schema) {
  const variants = schema.oneOf || [schema]
  return variants.map(variant => Object.keys(variant.properties).map(key => {
    const field = variant.properties[key]
    const name = field.const === undefined ? key : `${key}=${field.const}`
    return variant.required.includes(key) ? name : `${name}?`
  }).join(', ')).map(fields => `{${fields}}`).join(' 或 ')
}

export const HOST_ACTION_PROMPT = [
  '你在 Linear Lite 共享项目中工作。使用 host_action 观察协作环境，选择 READY 任务并领取，依据任务和共享事实自主推进。',
  `调用 ${observeCall} 观察。用观察结果中的任务标识与输入版本调用 tools.host_action({ action: "claim", payload: { taskId, inputRevision } }) 领取。下列每项的字段都放入 payload，问号表示可省略。`,
  '领取成功时，宿主把描述图片作为当前回合的图像输入。按 descriptionImages 中 included 为 true 的序号查看会话中的图像；若有 error，再判断缺失图片是否阻止任务。',
  ...names.map(name => `${name} ${describePayload(actions[name].payload)}：${actions[name].description}。`),
  '项目文件路径使用项目相对路径。通过 list_files 浏览目录，通过 read_file 读取项目规则和源文件。文件修改使用宿主管理的文件操作，每次操作使用唯一 operationId，更新或删除前读取文件版本。',
  '按需读取项目文档和文件，adopt_context 只能选择本次执行已读取的材料。查看 observe 返回的 prerequisites 和 sourceObservations，用读取结果复核来源成果。复核的 paths 由宿主读取并生成事实。',
  '领取任务后先判断规模。一个 Agent 能在本回合内完成并验证的任务直接做，不拆分。只有任务包含多个可独立验证的成果，或明显超出一个回合时，才用 split_task 在本任务下创建子任务，每个子任务写清需求与完成条件，不写依赖、不写文件清单。拆分后本次尝试结束，不再交付本任务。',
  '拆分出的子任务之间若有先后关系，由领取子任务的 Agent 在发现缺少成果时再用 change_dependency 记录，拆分时不预先声明。',
  '发现当前目标内缺项时，依据共享环境中的可核验文件和任务事实行动。新增业务目标时请求授权。',
  '撤回关系只接受 observe 提供的 retractableDependency。复查其依据，确认判断有误时提交撤回，关系成立时结束回合并保留等待。',
  '按需记录检查点，读取已核验的共享成果由宿主记录采用事实。协作操作返回 stopRequested 时结束本次尝试。调用 deliver 后结束回合，批次需要用户决定时调用 stop。不要操作 Git 或输出凭据。',
].join('\n')

function matches(value, schema) {
  if (schema.oneOf && schema.oneOf.filter(option => matches(value, option)).length !== 1) return false
  if (schema.const !== undefined && value !== schema.const) return false
  if (schema.enum && !schema.enum.includes(value)) return false
  if (schema.type === 'object') {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) return false
    if (schema.required?.some(key => !Object.hasOwn(value, key))) return false
    return Object.keys(value).every(key => schema.properties && Object.hasOwn(schema.properties, key)
      ? matches(value[key], schema.properties[key]) : schema.additionalProperties !== false)
  }
  if (schema.type === 'array') return Array.isArray(value)
    && (schema.minItems === undefined || value.length >= schema.minItems)
    && (schema.maxItems === undefined || value.length <= schema.maxItems)
    && value.every(item => matches(item, schema.items))
  if (schema.type === 'integer') return Number.isSafeInteger(value) && (schema.minimum === undefined || value >= schema.minimum)
  if (schema.type === 'string') return typeof value === 'string'
    && (schema.minLength === undefined || value.length >= schema.minLength)
    && (schema.maxLength === undefined || value.length <= schema.maxLength)
    && (schema.pattern === undefined || new RegExp(schema.pattern).test(value))
  return true
}

export function validateHostAction(args) {
  if (!matches(args, HOST_ACTION_TOOL.inputSchema))
    throw new HostError(400, 'TOOL_INPUT_INVALID', `宿主操作参数无效，须传入 action 和 payload。观察示例：${observeCall}。`)
  const spec = actions[args.action]
  if (!matches(args.payload, spec.payload))
    throw new HostError(400, 'TOOL_INPUT_INVALID',
      `宿主操作参数无效，${args.action} 的 payload 结构为 ${describePayload(spec.payload)}。${spec.description}。`)
  return args
}

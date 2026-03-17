const zhCN = {
  app: {
    name: 'Linear Lite'
  },
  auth: {
    signIn: '登录',
    signUp: '注册',
    subtitle: {
      login: '登录以继续',
      register: '通过邮箱验证码创建账号'
    },
    tabs: {
      login: '登录',
      register: '注册'
    },
    placeholder: {
      identity: '邮箱或用户名',
      email: '邮箱',
      verificationCode: '验证码',
      username: '用户名',
      password: '密码'
    },
    sending: '发送中…',
    sendCode: '发送验证码',
    loading: {
      login: '登录中…',
      register: '创建中…'
    },
    action: {
      signIn: '登录',
      signUp: '创建账号'
    },
    error: {
      enterEmail: '请输入邮箱',
      sendCodeFailed: '发送验证码失败',
      enterCredentials: '请输入邮箱/用户名和密码',
      completeRegistration: '请完成所有注册字段',
      authFailed: '认证失败'
    }
  },
  common: {
    all: '全部',
    none: '无',
    workspace: '工作区',
    unassigned: '未分配',
    noProject: '无项目',
    someone: '某人',
    status: '状态',
    priority: '优先级',
    assignee: '负责人',
    project: '项目',
    title: '标题',
    created: '创建时间',
    updated: '更新时间',
    dueDate: '截止日期',
    close: '关闭',
    cancel: '取消',
    save: '保存',
    attach: '附件',
    retry: '重试',
    remove: '移除',
    loading: '加载中...',
    create: '创建',
    creating: '创建中...',
    board: '看板',
    list: '列表',
    filter: '筛选',
    display: '显示',
    labels: '标签',
    import: '导入'
  },
  sidebar: {
    favorites: '收藏',
    projects: '项目',
    newProjectTitle: '新建项目',
    projectSettings: '项目设置',
    signOut: '登出'
  },
  projectModal: {
    title: '新建项目',
    form: {
      nameLabel: '项目名称',
      identifierLabel: '标识符',
      namePlaceholder: '例如：Engineering',
      identifierPlaceholder: '例如：ENG（3 个字母）'
    },
    buttons: {
      create: '创建',
      creating: '创建中...'
    },
    validation: {
      nameAndIdentifierRequired: '请输入项目名称和标识符',
      identifierTooLong: '标识符最多 16 个字符',
      createFailed: '创建失败'
    }
  },
  projectSettingsModal: {
    title: '项目设置',
    inviteTitle: '通过邮箱邀请',
    inviteDescription: '被邀请用户登录或注册后即可看到该项目。',
    inviteButton: '邀请',
    inviting: '邀请中…',
    inviteSuccess: '邀请已发送。',
    invitePlaceholder: 'name@example.com',
    deleteTitle: '删除项目',
    deleteDescription: '此操作将永久删除项目及其所有任务。',
    deleteButton: '删除项目',
    deleting: '删除中…',
    deleteConfirm: '删除项目“{name}”及其所有任务？该操作无法撤销。',
    errors: {
      updateFailed: '更新失败',
      deleteFailed: '删除失败',
      emailRequired: '请输入要邀请的邮箱',
      inviteFailed: '邀请失败'
    },
    buttons: {
      save: '保存',
      saving: '保存中…'
    }
  },
  emptyState: {
    noProjects: '暂无项目'
  },
  command: {
    newTask: '新建任务',
    viewBoard: '切换到看板',
    viewList: '切换到列表',
    projectSettings: '打开项目设置',
    focusSearch: '聚焦搜索'
  },
  commandPalette: {
    title: '命令面板',
    searchPlaceholder: '输入命令或搜索...',
    searchAria: '搜索命令',
    noMatches: '没有匹配的命令。'
  },
  boardView: {
    newIssue: '新建任务',
    searchIssues: '搜索任务...',
    allIssues: '全部任务',
    active: '进行中',
    backlog: '待办',
    filterOptions: '筛选选项',
    filterByStatus: '按状态筛选',
    filterByPriority: '按优先级筛选',
    groupBy: '分组方式',
    groupTasks: '任务分组',
    sort: '排序',
    orderTasks: '任务排序',
    completed: '已完成',
    completedVisibility: '完成项显示',
    allStatus: '全部状态',
    allPriorities: '全部优先级',
    group: '分组',
    orderAsc: '↑ 升序',
    orderDesc: '↓ 降序',
    emptyGroups: '显示空分组',
    displayOptions: '显示选项',
    showOnIssue: '任务上显示',
    subIssues: '子任务',
    showSubIssues: '显示子任务',
    nestedSubIssues: '嵌套子任务',
    loadingTasks: '任务加载中...',
    noTasks: '你还没有任何任务。',
    createFirstTask: '创建第一个任务',
    noTasksMatchFilters: '没有任务符合当前筛选条件。',
    clearFilters: '清除筛选',
    addIssue: '添加任务',
    addIssueToColumn: '向此列添加任务',
    openOnly: '仅未完成',
    id: 'ID'
  },
  issueComposer: {
    dialogLabel: '创建任务',
    title: '新建任务',
    issueTitlePlaceholder: '任务标题',
    descriptionPlaceholder: '添加描述... 输入 / 使用格式化',
    createMore: '继续创建',
    createIssue: '创建任务',
    creatingIssue: '创建中...'
  },
  attachments: {
    fileTooLargeSkipped: '超过 {size}，已跳过',
    uploadFailed: '上传失败',
    uploading: '上传中...',
    downloadFailed: '下载失败',
    deleteFailed: '删除失败'
  },
  taskImage: {
    altFallback: '图片'
  },
  editor: {
    placeholder: '输入内容…'
  },
  taskImportModal: {
    ariaLabel: '导入任务',
    title: '任务导入',
    subtitle: '从 CSV 或 Excel 导入任务',
    steps: {
      upload: '上传',
      mapping: '映射',
      preview: '预览',
      result: '结果'
    },
    downloadTemplate: '下载模板',
    dropzone: {
      title: '选择 `.csv` 或 `.xlsx` 文件',
      copy: '必填模板列：`title`、`importId`。可选：`parentImportId`、`description`、`status`、`priority`、`assignee`、`dueDate`。'
    },
    fileMeta: {
      file: '文件',
      rows: '行数',
      project: '项目',
      noProject: '未选择项目'
    },
    mapping: {
      required: '必填',
      unmapped: '未映射'
    },
    fields: {
      importId: '导入 ID',
      parentImportId: '父级导入 ID'
    },
    preview: {
      summary: {
        total: '总计',
        parents: '父任务',
        subtasks: '子任务'
      },
      table: {
        parent: '父任务',
        topLevel: '顶层'
      },
      errors: {
        lineMessage: '第 {lineNumber} 行 · {field} · {message}'
      }
    },
    result: {
      success: '已将 {count} 个任务导入当前项目。',
      summary: {
        parents: '父任务',
        subtasks: '子任务',
        created: '已创建'
      }
    },
    footer: {
      back: '返回',
      review: '查看导入',
      importIssues: '导入任务',
      importing: '导入中...',
      done: '完成'
    },
    errors: {
      parseFailed: '解析文件失败。',
      importFailed: '导入失败。'
    }
  },
  taskEditor: {
    workspaceAria: '任务工作区',
    breadcrumbAria: '面包屑导航',
    newIssue: '新建任务',
    issue: '任务',
    subIssueOf: '子任务隶属于 {id} {title}',
    addToFavorites: '添加到收藏',
    removeFromFavorites: '从收藏中移除',
    saved: '已保存',
    saving: '保存中...',
    previousIssue: '上一个任务',
    nextIssue: '下一个任务',
    issueTitlePlaceholder: '任务标题',
    descriptionPlaceholder: '添加描述... 输入 / 使用格式化',
    attachments: '附件',
    deleteAttachment: '删除附件',
    noAttachments: '暂无附件。点击回形针添加一个。',
    subIssues: '子任务',
    noSubIssues: '暂无子任务。添加一个来拆解当前任务。',
    createNewSubIssue: '创建子任务',
    nestedSubIssues: '嵌套子任务',
    discard: '放弃',
    createSubIssue: '创建',
    creatingSubIssue: '创建中...',
    activity: '活动',
    unsubscribe: '取消订阅',
    loadingActivity: '活动加载中...',
    noActivityYet: '暂无活动。',
    createdIssueSuffix: '创建了该任务',
    leaveComment: '留下评论...',
    commentAria: '评论',
    sendAria: '发送',
    setPriority: '设置优先级',
    addLabel: '添加标签',
    addToProject: '添加到项目',
    completedAt: '完成时间',
    justNow: '刚刚',
    minutesAgo: '{count} 分钟前',
    hoursAgo: '{count} 小时前',
    daysAgo: '{count} 天前',
    monthsAgo: '{count} 个月前'
  },
  taskList: {
    createIssueInGroup: '在分组中创建任务',
    markDone: '标记为已完成',
    markNotDone: '标记为未完成',
    addSubIssue: '添加子任务'
  },
  status: {
    backlog: '待办',
    todo: '待处理',
    in_progress: '进行中',
    in_review: '审核中',
    done: '已完成',
    canceled: '已取消',
    duplicate: '重复'
  },
  priority: {
    low: '低',
    medium: '中',
    high: '高',
    urgent: '紧急'
  },
  fieldLabel: {
    assigneeId: '负责人',
    dueDate: '截止日期',
    title: '标题',
    description: '描述',
    status: '状态',
    priority: '优先级',
    default: '字段'
  },
  activity: {
    created: '{actor} 创建了任务',
    favorited: '{actor} 收藏了任务',
    unfavorited: '{actor} 取消了收藏',
    changedField: '{actor} 修改了 {field}',
    changedFromTo: '{actor} 将 {field} 从 {oldValue} 改为 {newValue}',
    updated: '{actor} 更新了任务',
    emptyValue: '空'
  }
} as const

export default zhCN

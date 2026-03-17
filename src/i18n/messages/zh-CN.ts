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
  sidebar: {
    favorites: '收藏',
    projects: '项目',
    newProjectTitle: '新建项目',
    projectSettings: '项目设置',
    signOut: '登出'
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

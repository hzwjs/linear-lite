const zhCN = {
  app: {
    name: 'Linear Lite'
  },
  auth: {
    signIn: '登录',
    signUp: '注册'
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

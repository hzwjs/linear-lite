const en = {
  app: {
    name: 'Linear Lite'
  },
  auth: {
    signIn: 'Log in',
    signUp: 'Sign up'
  },
  status: {
    backlog: 'Backlog',
    todo: 'Todo',
    in_progress: 'In Progress',
    in_review: 'In Review',
    done: 'Done',
    canceled: 'Canceled',
    duplicate: 'Duplicate'
  },
  priority: {
    low: 'Low',
    medium: 'Medium',
    high: 'High',
    urgent: 'Urgent'
  },
  fieldLabel: {
    assigneeId: 'assignee',
    dueDate: 'due date',
    status: 'status',
    priority: 'priority',
    default: 'field'
  },
  activity: {
    created: '{actor} created the issue',
    favorited: '{actor} favorited the issue',
    unfavorited: '{actor} removed the issue from favorites',
    changedField: '{actor} changed {field}',
    changedFromTo: '{actor} changed {field} from {oldValue} to {newValue}',
    updated: '{actor} updated the issue',
    emptyValue: 'empty'
  }
} as const

export default en

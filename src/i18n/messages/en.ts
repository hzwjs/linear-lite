const en = {
  app: {
    name: 'Linear Lite'
  },
  auth: {
    signIn: 'Log in',
    signUp: 'Sign up'
  },
  sidebar: {
    favorites: 'Favorites',
    projects: 'Projects',
    newProjectTitle: 'New project',
    projectSettings: 'Project settings',
    signOut: 'Sign out'
  },
  emptyState: {
    noProjects: 'No projects yet'
  },
  command: {
    newTask: 'New task',
    viewBoard: 'Switch to Board view',
    viewList: 'Switch to List view',
    projectSettings: 'Open project settings',
    focusSearch: 'Focus search'
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
    title: 'title',
    description: 'description',
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

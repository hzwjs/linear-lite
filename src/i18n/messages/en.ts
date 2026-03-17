const en = {
  app: {
    name: 'Linear Lite'
  },
  auth: {
    signIn: 'Log in',
    signUp: 'Sign up',
    subtitle: {
      login: 'Sign in to continue',
      register: 'Create your account with email verification'
    },
    tabs: {
      login: 'Log in',
      register: 'Sign up'
    },
    placeholder: {
      identity: 'Email or username',
      email: 'Email',
      verificationCode: 'Verification code',
      username: 'Username',
      password: 'Password'
    },
    sending: 'Sending...',
    sendCode: 'Send code',
    loading: {
      login: 'Signing in...',
      register: 'Creating account...'
    },
    action: {
      signIn: 'Sign in',
      signUp: 'Create account'
    },
    error: {
      enterEmail: 'Please enter email',
      sendCodeFailed: 'Failed to send code',
      enterCredentials: 'Please enter email or username and password',
      completeRegistration: 'Please complete all registration fields',
      authFailed: 'Authentication failed'
    }
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

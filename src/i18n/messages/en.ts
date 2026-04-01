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
  common: {
    all: 'All',
    none: 'None',
    workspace: 'Workspace',
    unassigned: 'Unassigned',
    noProject: 'No Project',
    someone: 'Someone',
    status: 'Status',
    priority: 'Priority',
    assignee: 'Assignee',
    project: 'Project',
    title: 'Title',
    created: 'Created',
    updated: 'Updated',
    dueDate: 'Due date',
    plannedStartDate: 'Planned start',
    close: 'Close',
    cancel: 'Cancel',
    save: 'Save',
    attach: 'Attach',
    retry: 'Retry',
    remove: 'Remove',
    loading: 'Loading...',
    create: 'Create',
    creating: 'Creating...',
    board: 'Board',
    list: 'List',
    filter: 'Filter',
    display: 'Display',
    labels: 'Labels',
    import: 'Import'
  },
  sidebar: {
    favorites: 'Favorites',
    projects: 'Projects',
    newProjectTitle: 'New project',
    projectSettings: 'Project settings',
    analytics: 'Analytics',
    signOut: 'Sign out',
    hideSidebar: 'Hide sidebar',
    showSidebar: 'Show sidebar'
  },
  projectModal: {
    title: 'New project',
    form: {
      nameLabel: 'Project name',
      identifierLabel: 'Identifier',
      namePlaceholder: 'e.g. Engineering',
      identifierPlaceholder: 'e.g. ENG (3 letters)'
    },
    buttons: {
      create: 'Create',
      creating: 'Creating...'
    },
    validation: {
      nameAndIdentifierRequired: 'Please enter project name and identifier',
      identifierTooLong: 'Identifier must be at most 16 characters',
      createFailed: 'Create failed'
    }
  },
  projectSettingsModal: {
    title: 'Project settings',
    inviteTitle: 'Invite by email',
    inviteDescription: 'Invited users will see this project after they sign in or register.',
    inviteButton: 'Invite',
    inviting: 'Inviting...',
    inviteSuccess: 'Invitation sent.',
    invitePlaceholder: "name{'@'}example.com",
    deleteTitle: 'Delete project',
    deleteDescription: 'This deletes the project and all tasks permanently.',
    deleteButton: 'Delete project',
    deleting: 'Deleting...',
    deleteConfirm: 'Delete project \"{name}\" and all its tasks? This cannot be undone.',
    errors: {
      updateFailed: 'Update failed',
      deleteFailed: 'Delete failed',
      emailRequired: 'Please enter an email to invite',
      inviteFailed: 'Invite failed'
    },
    buttons: {
      save: 'Save',
      saving: 'Saving...'
    }
  },
  emptyState: {
    noProjects: 'No projects yet',
    selectProject: 'Select a project from the left'
  },
  command: {
    newTask: 'New task',
    viewBoard: 'Switch to Board view',
    viewList: 'Switch to List view',
    projectSettings: 'Open project settings',
    focusSearch: 'Focus search',
    toggleSidebar: 'Toggle sidebar visibility'
  },
  commandPalette: {
    title: 'Command palette',
    searchPlaceholder: 'Type a command or search...',
    searchAria: 'Search commands',
    noMatches: 'No commands match.'
  },
  boardView: {
    newIssue: 'New issue',
    searchIssues: 'Search issues...',
    allIssues: 'All issues',
    active: 'Active',
    backlog: 'Backlog',
    filterOptions: 'Filter options',
    filterByStatus: 'Filter by status',
    filterByPriority: 'Filter by priority',
    groupBy: 'Group by',
    groupTasks: 'Group tasks',
    sort: 'Sort',
    orderTasks: 'Order tasks',
    completed: 'Completed',
    completedVisibility: 'Completed visibility',
    allStatus: 'All Status',
    allPriorities: 'All Priorities',
    group: 'Group',
    orderAsc: '↑ Asc',
    orderDesc: '↓ Desc',
    emptyGroups: 'Empty groups',
    displayOptions: 'Display options',
    showOnIssue: 'Show on issue',
    subIssues: 'Sub-issues',
    showSubIssues: 'Show sub-issues',
    nestedSubIssues: 'Nested sub-issues',
    loadingTasks: 'Loading tasks...',
    noTasks: "You don't have any tasks yet.",
    createFirstTask: 'Create your first task',
    noTasksMatchFilters: 'No tasks match your filters.',
    clearFilters: 'Clear filters',
    addIssue: 'Add issue',
    addIssueToColumn: 'Add issue to this column',
    openOnly: 'Open only',
    id: 'ID',
    progress: 'Progress',
    plannedStart: 'Planned start'
  },
  issueComposer: {
    dialogLabel: 'Create issue',
    title: 'New issue',
    issueTitlePlaceholder: 'Issue title',
    descriptionPlaceholder: 'Add description... Type / for formatting',
    createMore: 'Create more',
    createIssue: 'Create issue',
    creatingIssue: 'Creating...'
  },
  attachments: {
    fileTooLargeSkipped: 'exceeds {size} and was skipped',
    uploadFailed: 'Upload failed',
    uploading: 'Uploading...',
    downloadFailed: 'Download failed',
    deleteFailed: 'Delete failed'
  },
  taskImage: {
    altFallback: 'image'
  },
  editor: {
    placeholder: 'Write something...',
    slashMenu: {
      ariaLabel: 'Block type menu',
      heading1: 'Heading 1',
      heading2: 'Heading 2',
      heading3: 'Heading 3',
      bulletList: 'Bulleted list',
      orderedList: 'Numbered list',
      taskList: 'Checklist',
      codeBlock: 'Code block',
      blockquote: 'Blockquote'
    },
    codeBlock: {
      languageAria: 'Code block language',
      copyAria: 'Copy code',
      languages: {
        plainText: 'Plain text',
        javascript: 'JavaScript',
        typescript: 'TypeScript',
        sql: 'SQL',
        json: 'JSON',
        html: 'HTML',
        css: 'CSS',
        bash: 'Bash',
        python: 'Python',
        java: 'Java',
        xml: 'XML'
      }
    }
  },
  select: {
    placeholder: 'Select…',
    ariaLabel: 'Select option',
    searchAria: 'Search'
  },
  datePicker: {
    placeholder: 'Select date',
    triggerAria: 'Due date',
    dialogAria: 'Choose date',
    previousMonth: 'Previous month',
    nextMonth: 'Next month',
    today: 'Today',
    todayAria: 'Today, {day}',
    weekdays: {
      mon: 'Mon',
      tue: 'Tue',
      wed: 'Wed',
      thu: 'Thu',
      fri: 'Fri',
      sat: 'Sat',
      sun: 'Sun'
    }
  },
  taskStore: {
    errors: {
      loadFailed: 'Failed to load tasks.',
      noProject: 'No project selected.',
      createFailed: 'Failed to create task.',
      updateFailed: 'Failed to update task.'
    }
  },
  taskImport: {
    errors: {
      maxRows: 'Import supports up to {count} rows per file.',
      titleColumnRequired: 'Title column is required.',
      importIdColumnRequired: 'Import ID column is required.',
      titleRequired: 'Title is required.',
      importIdRequired: 'Import ID is required.',
      importIdUnique: 'Import ID must be unique within the file.',
      invalidStatus: 'Status must be one of: {values}.',
      invalidPriority: 'Priority must be one of: {values}.',
      invalidDueDate: 'Due date must use YYYY-MM-DD.',
      parentSelfReference: 'Parent Import ID cannot reference the same row.',
      parentMissing: 'Parent Import ID must reference another row in the same file.',
      unsupportedFileType: 'Only .csv and .xlsx files are supported.',
      noSheets: 'The file does not contain any sheets.',
      firstSheetUnreadable: 'The first sheet could not be read.',
      missingHeader: 'The file must include a header row.',
      invalidPlannedStartDate: 'Planned start date must use YYYY-MM-DD.',
      invalidProgress: 'Progress must be an integer from 0 to 100 (optional % suffix).'
    }
  },
  taskImportModal: {
    ariaLabel: 'Import tasks',
    title: 'Task import',
    subtitle: 'Import issues from CSV or Excel',
    steps: {
      upload: 'Upload',
      mapping: 'Mapping',
      preview: 'Preview',
      result: 'Result'
    },
    downloadTemplate: 'Download template',
    dropzone: {
      title: 'Choose a `.csv` or `.xlsx` file',
      copy: 'Required template columns: `title`, `importId`. Optional: `parentImportId`, `description`, `status`, `priority`, `assignee`, `plannedStartDate`, `dueDate`, `progressPercent`.'
    },
    fileMeta: {
      file: 'File',
      rows: 'Rows',
      project: 'Project',
      noProject: 'No project selected'
    },
    mapping: {
      required: 'Required',
      unmapped: 'Unmapped'
    },
    fields: {
      importId: 'Import ID',
      parentImportId: 'Parent Import ID',
      progressPercent: 'Progress %',
      plannedStartDate: 'Planned start'
    },
    preview: {
      summary: {
        total: 'Total',
        parents: 'Parents',
        subtasks: 'Subtasks'
      },
      table: {
        parent: 'Parent',
        topLevel: 'Top-level'
      },
      errors: {
        lineMessage: 'Line {lineNumber} · {field} · {message}'
      }
    },
    result: {
      success: 'Imported {count} issues into the current project.',
      summary: {
        parents: 'Parents',
        subtasks: 'Subtasks',
        created: 'Created'
      }
    },
    footer: {
      back: 'Back',
      review: 'Review import',
      importIssues: 'Import issues',
      importing: 'Importing...',
      done: 'Done'
    },
    errors: {
      parseFailed: 'Failed to parse file.',
      importFailed: 'Import failed.'
    }
  },
  taskEditor: {
    workspaceAria: 'Issue workspace',
    breadcrumbAria: 'Breadcrumb',
    newIssue: 'New issue',
    issue: 'Issue',
    subIssueOf: 'Sub-issue of {id} {title}',
    addToFavorites: 'Add to favorites',
    removeFromFavorites: 'Remove from favorites',
    saved: 'Saved',
    saving: 'Saving...',
    previousIssue: 'Previous issue',
    nextIssue: 'Next issue',
    issueTitlePlaceholder: 'Issue title',
    descriptionPlaceholder: 'Add description... Type / for formatting',
    attachments: 'Attachments',
    deleteAttachment: 'Delete attachment',
    noAttachments: 'No attachments. Use the paperclip to add one.',
    subIssues: 'Sub-issues',
    noSubIssues: 'No sub-issues. Add one to break down this task.',
    createNewSubIssue: 'Create new sub-issue',
    nestedSubIssues: 'Nested sub-issues',
    discard: 'Discard',
    createSubIssue: 'Create',
    creatingSubIssue: 'Creating...',
    activity: 'Activity',
    unsubscribe: 'Unsubscribe',
    loadingActivity: 'Loading activity...',
    noActivityYet: 'No activity yet.',
    createdIssueSuffix: 'created the issue',
    leaveComment: 'Leave a comment...',
    commentAria: 'Comment',
    sendAria: 'Send',
    setPriority: 'Set priority',
    addLabel: 'Add label',
    addToProject: 'Add to project',
    completedAt: 'Completed at',
    progress: 'Progress',
    progressAria: 'Completion progress from 0 to 100 percent',
    justNow: 'just now',
    minutesAgo: '{count}m ago',
    hoursAgo: '{count}h ago',
    daysAgo: '{count}d ago',
    monthsAgo: '{count}mo ago',
    importedAssigneeLine: 'Imported assignee: {name}'
  },
  taskList: {
    changeStatus: 'Change status',
    createIssueInGroup: 'Create issue in group',
    copyTitle: 'Copy title',
    titleCopied: 'Copied',
    markDone: 'Mark done',
    markNotDone: 'Mark not done',
    addSubIssue: 'Add sub-issue',
    expandSubtasks: 'Expand subtasks',
    collapseSubtasks: 'Collapse subtasks',
    expandAllSubtasks: 'Expand all',
    collapseAllSubtasks: 'Collapse all',
    columnPlannedStart: 'Planned start',
    columnDueDate: 'Due date',
    columnProgress: 'Progress',
    changeAssignee: 'Change assignee',
    assigneeSearchPlaceholder: 'Search members',
    clearDate: 'Clear date',
    progressDragAria: 'Drag or use arrow keys to adjust progress'
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
    progressPercent: 'progress',
    dueDate: 'due date',
    plannedStartDate: 'planned start',
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
  },
  analytics: {
    title: 'Project Analytics',
    granularity: {
      day: 'Day',
      week: 'Week',
      month: 'Month',
      year: 'Year'
    },
    dateRange: 'Date range',
    dateSingle: 'Date',
    yearSingle: 'Year',
    trend: 'Trend',
    created: 'Created',
    completed: 'Completed',
    due: 'Due',
    currentSnapshot: 'Current Snapshot',
    totalTasks: 'Total tasks',
    overdue: 'Overdue',
    statusBreakdown: 'Status Distribution',
    assigneeBreakdown: 'Assignee Distribution',
    priorityBreakdown: 'Priority Distribution',
    taskList: 'Task Details',
    dayFocus: 'Daily Key Metrics',
    allTasks: 'All Tasks',
    createdToday: 'Created Today',
    completedToday: 'Completed Today',
    dueToday: 'Due Today',
    noData: 'No data',
    loading: 'Loading...',
    loadError: 'Load failed',
    retry: 'Retry',
    netChange: 'Net change',
    unassigned: 'Unassigned',
    page: 'Page {page}',
    totalItems: '{total} items',
    prevPage: 'Previous',
    nextPage: 'Next'
  }
} as const

export default en

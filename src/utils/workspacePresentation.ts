export interface WorkspacePresentation {
  preservePrimaryCanvas: boolean
  workspaceMode: 'none' | 'overlay' | 'inline'
}

export function resolveWorkspacePresentation(taskId: string | null | undefined): WorkspacePresentation {
  return {
    preservePrimaryCanvas: true,
    workspaceMode: taskId ? 'inline' : 'none'
  }
}

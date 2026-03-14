export interface SubtaskProgressDisplay {
  visible: boolean
  countText: string
  progress: number
  completed: boolean
}

export function getSubtaskProgressDisplay(
  completedSubtasks = 0,
  totalSubtasks = 0
): SubtaskProgressDisplay {
  if (totalSubtasks <= 0) {
    return {
      visible: false,
      countText: '',
      progress: 0,
      completed: false
    }
  }

  const progress = Math.min(Math.max(completedSubtasks / totalSubtasks, 0), 1)

  return {
    visible: true,
    countText: `${completedSubtasks}/${totalSubtasks}`,
    progress,
    completed: completedSubtasks >= totalSubtasks
  }
}

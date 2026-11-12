const sourceKey = (attemptId, revision) => `${attemptId}:${revision}`

export function sourcePlans(tasks) {
  const plans = new Map()
  for (const task of tasks) {
    const taskSources = new Map()
    for (const source of task.prerequisites) {
      const taskPlans = new Map()
      for (const candidate of [source, ...source.traceCandidates]) {
        const { attemptId, inputRevision, evidence } = candidate
        const key = sourceKey(attemptId, inputRevision)
        if (!plans.has(key)) plans.set(key, { sourceAttemptId: attemptId, sourceInputRevision: inputRevision, paths: new Set() })
        const { paths } = plans.get(key)
        taskPlans.set(key, paths)
        for (const path of evidence.artifactRefs) paths.add(path)
      }
      taskSources.set(source.taskId, taskPlans)
    }
    for (const attempt of task.prerequisiteReviewFacts) {
      for (const { review } of attempt.reviews) {
        const paths = taskSources.get(review.sourceTaskId)?.get(sourceKey(review.sourceAttemptId, review.sourceInputRevision))
        if (!paths) continue
        for (const { path } of review.observations) paths.add(path)
      }
    }
  }
  return [...plans.values()]
}

export async function readCurrentFacts(observer, paths) {
  if (!paths.length) return new Map()
  let observations
  try {
    observations = await observer.observeArtifacts({ paths })
  } catch {
    // An unreadable path cannot establish absence or suppress another path's fact.
    observations = []
    for (const path of paths) {
      try { observations.push(...await observer.observeArtifacts({ paths: [path] })) } catch {}
    }
  }
  return new Map(observations.map(fact => [fact.path, fact]))
}

export function sourceFacts(plans, current) {
  return plans.map(({ paths, ...source }) => ({
    ...source, observations: [...paths].flatMap(path => current.has(path) ? [current.get(path)] : []),
  }))
}

function* projectFileContexts(tasks, bindingId) {
  for (const task of tasks) {
    const matching = new Set(task.attempts.filter(attempt => attempt.bindingId === bindingId).map(attempt => attempt.id))
    for (const attempt of task.contextFacts) {
      if (!matching.has(attempt.attemptId)) continue
      for (const entry of attempt.refs) if (entry.ref.kind === 'PROJECT_FILE') yield entry
    }
  }
}

export function projectContextFacts(tasks, bindingId, current) {
  for (const entry of projectFileContexts(tasks, bindingId)) {
    const observed = current.get(entry.ref.projectPath)
    if (observed) entry.status = observed.contentVersion === entry.ref.contentVersion ? 'CURRENT' : 'REVIEW_REQUIRED'
  }
}

export function contextPaths(tasks, bindingId) {
  const paths = new Set()
  for (const { ref } of projectFileContexts(tasks, bindingId)) paths.add(ref.projectPath)
  return paths
}

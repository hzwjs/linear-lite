declare module 'frappe-gantt' {
  export interface FrappeGanttTask {
    id: string
    name: string
    start: string | Date
    end: string | Date
    progress?: number
    /** 前置任务 id，逗号分隔，与官网 dependencies 一致 */
    dependencies?: string
  }

  export interface FrappeGanttOptions {
    view_mode?: string
    infinite_padding?: boolean
    scroll_to?: 'start' | 'end' | 'today' | string | Date | null
    today_button?: boolean
    readonly_progress?: boolean
    popup?: false | ((...args: unknown[]) => string | false | void)
    on_date_change?: (task: FrappeGanttTask, start: Date, end: Date) => void
  }

  export default class Gantt {
    constructor(
      element: string | HTMLElement | SVGElement,
      tasks: FrappeGanttTask[],
      options?: FrappeGanttOptions
    )

    refresh(tasks: FrappeGanttTask[]): void
    clear(): void
  }
}

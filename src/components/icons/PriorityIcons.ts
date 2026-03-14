/**
 * Linear 官方优先级图标（从 Linear 产品 DOM 提取的 SVG path）
 */
import { defineComponent, h } from 'vue'

const sizeProp = { type: Number, default: 16 }

/** Urgent — Linear 官方 path（方框 + 感叹号） */
export const PriorityUrgentIcon = defineComponent({
  name: 'PriorityUrgentIcon',
  props: { size: sizeProp },
  setup(props) {
    const s = props.size
    return () =>
      h('svg', {
        width: s,
        height: s,
        viewBox: '0 0 16 16',
        fill: 'currentColor',
        'aria-hidden': 'true',
        role: 'img',
        class: 'priority-icon priority-icon--urgent',
        xmlns: 'http://www.w3.org/2000/svg'
      }, [
        h('path', {
          d: 'M3 1C1.91067 1 1 1.91067 1 3V13C1 14.0893 1.91067 15 3 15H13C14.0893 15 15 14.0893 15 13V3C15 1.91067 14.0893 1 13 1H3ZM7 4L9 4L8.75391 8.99836H7.25L7 4ZM9 11C9 11.5523 8.55228 12 8 12C7.44772 12 7 11.5523 7 11C7 10.4477 7.44772 10 8 10C8.55228 10 9 10.4477 9 11Z'
        })
      ])
  }
})

/** High — Linear 官方（三竖条左低→右高） */
export const PriorityHighIcon = defineComponent({
  name: 'PriorityHighIcon',
  props: { size: sizeProp },
  setup(props) {
    const s = props.size
    return () =>
      h('svg', {
        width: s,
        height: s,
        viewBox: '0 0 16 16',
        fill: 'currentColor',
        'aria-hidden': 'true',
        role: 'img',
        class: 'priority-icon',
        xmlns: 'http://www.w3.org/2000/svg'
      }, [
        h('rect', { x: 1.5, y: 8, width: 3, height: 6, rx: 1 }),
        h('rect', { x: 6.5, y: 5, width: 3, height: 9, rx: 1 }),
        h('rect', { x: 11.5, y: 2, width: 3, height: 12, rx: 1 })
      ])
  }
})

/** Medium — Linear 官方（三竖条，最右条 40% 透明） */
export const PriorityMediumIcon = defineComponent({
  name: 'PriorityMediumIcon',
  props: { size: sizeProp },
  setup(props) {
    const s = props.size
    return () =>
      h('svg', {
        width: s,
        height: s,
        viewBox: '0 0 16 16',
        fill: 'currentColor',
        'aria-hidden': 'true',
        role: 'img',
        class: 'priority-icon',
        xmlns: 'http://www.w3.org/2000/svg'
      }, [
        h('rect', { x: 1.5, y: 8, width: 3, height: 6, rx: 1 }),
        h('rect', { x: 6.5, y: 5, width: 3, height: 9, rx: 1 }),
        h('rect', { x: 11.5, y: 2, width: 3, height: 12, rx: 1, 'fill-opacity': 0.4 })
      ])
  }
})

/** Low — Linear 官方（三竖条，中、右条 40% 透明） */
export const PriorityLowIcon = defineComponent({
  name: 'PriorityLowIcon',
  props: { size: sizeProp },
  setup(props) {
    const s = props.size
    return () =>
      h('svg', {
        width: s,
        height: s,
        viewBox: '0 0 16 16',
        fill: 'currentColor',
        'aria-hidden': 'true',
        role: 'img',
        class: 'priority-icon',
        xmlns: 'http://www.w3.org/2000/svg'
      }, [
        h('rect', { x: 1.5, y: 8, width: 3, height: 6, rx: 1 }),
        h('rect', { x: 6.5, y: 5, width: 3, height: 9, rx: 1, 'fill-opacity': 0.4 }),
        h('rect', { x: 11.5, y: 2, width: 3, height: 12, rx: 1, 'fill-opacity': 0.4 })
      ])
  }
})

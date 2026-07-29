---
version: 1
slug: "src-views-analyticsview-vue"
primary_target: "src/views/AnalyticsView.vue"
related_targets: ["src/components/analytics/AnalyticsFilters.vue","src/components/analytics/TrendChart.vue","src/components/analytics/TaskSnapshotList.vue"]
---

Scope: desktop `/analytics`; Operate mode.

Audience and job: project leads running daily checks and weekly reviews. In under 30 seconds they must decide whether delivery is healthy, locate the dominant risk, and open the tasks that need action.

Content and action: current overdue count, period created/completed/due totals, net flow, delivery trend, global workflow-state composition, period assignee throughput, and a metric-linked task queue. Every headline metric that represents tasks is a filter, not decoration.

Direction: an operating-review ledger rather than a gallery of equal-weight charts. The first viewport states the health conclusion and exposes the evidence; the page then moves through delivery rhythm, risk ownership, and the actionable task ledger. Preserve the existing light Linear-Lite token system and compact desktop controls.

Constraints: use only existing analytics fields plus an explicit overdue task scope; keep Chinese and English parity; support keyboard focus, loading/error/empty states, reduced motion, and responsive layouts without converting this desktop-only module into a mobile surface.

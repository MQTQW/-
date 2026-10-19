package com.example.myplan.focus

/** Completes lagging UsageStats snapshots with the union of foreground activity intervals. */
object ForegroundUsage {
    enum class Kind { RESUME, PAUSE, STOP, SCREEN_OFF, SHUTDOWN, STARTUP }

    data class Event(
        val timestamp: Long,
        val kind: Kind,
        val packageName: String? = null,
        val activityClass: String? = null,
    )

    private data class Session(val startedAt: Long, val activities: MutableSet<String>)

    /**
     * Events may begin before [startTime], so a session spanning midnight is clipped to today.
     * Overlapping activities in one package count once. The public UsageEvents API identifies
     * activities by class name, so duplicate resumes for that class do not open another interval.
     * Taking the larger total avoids adding an ongoing session twice on systems whose snapshot
     * already includes it. Empty or unavailable events leave the system totals intact.
     */
    fun merge(
        systemTotals: Map<String, Long>,
        events: List<Event>,
        startTime: Long,
        endTime: Long,
    ): Map<String, Long> {
        val result = systemTotals.filterValues { it > 0 }.toMutableMap()
        if (endTime <= startTime || events.isEmpty()) return result

        val active = mutableMapOf<String, Session>()
        val eventTotals = mutableMapOf<String, Long>()

        fun finish(packageName: String, timestamp: Long) {
            val session = active.remove(packageName) ?: return
            val duration = (minOf(timestamp, endTime) - maxOf(session.startedAt, startTime)).coerceAtLeast(0)
            if (duration > 0) eventTotals[packageName] = eventTotals.getOrDefault(packageName, 0) + duration
        }

        events.asSequence().filter { it.timestamp <= endTime }.sortedBy { it.timestamp }.forEach { event ->
            when (event.kind) {
                Kind.SCREEN_OFF, Kind.SHUTDOWN -> {
                    active.keys.toList().forEach { finish(it, event.timestamp) }
                }
                Kind.STARTUP -> {
                    // If shutdown was not recorded, its actual time is unknown. Do not count
                    // a dangling session through the powered-off interval; snapshots remain a fallback.
                    active.clear()
                }
                Kind.RESUME -> {
                    val packageName = event.packageName?.takeIf { it.isNotBlank() } ?: return@forEach
                    val session = active.getOrPut(packageName) { Session(event.timestamp, mutableSetOf()) }
                    session.activities.add(event.activityClass.orEmpty())
                }
                Kind.PAUSE, Kind.STOP -> {
                    val packageName = event.packageName ?: return@forEach
                    val session = active[packageName] ?: return@forEach
                    val activity = event.activityClass
                    if (activity.isNullOrBlank()) {
                        // A package-level end event must not leave an unidentifiable session open.
                        session.activities.clear()
                    } else if (!session.activities.remove(activity) && session.activities == setOf("")) {
                        session.activities.clear()
                    }
                    if (session.activities.isEmpty()) finish(packageName, event.timestamp)
                }
            }
        }
        active.keys.toList().forEach { finish(it, endTime) }
        eventTotals.forEach { (packageName, duration) ->
            result[packageName] = maxOf(result.getOrDefault(packageName, 0), duration)
        }
        return result
    }
}

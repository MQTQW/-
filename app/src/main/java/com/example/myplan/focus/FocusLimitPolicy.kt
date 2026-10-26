package com.example.myplan.focus

import com.example.myplan.focus.data.entity.AppLimitEntity
import com.example.myplan.focus.data.entity.DailyUsageEntity

/** Rules are evaluated from current data, rather than remembered as irreversible lock events. */
object FocusLimitPolicy {
    /** A running focus session cannot be bypassed by either kind of reward entitlement. */
    fun effectiveLockedPackages(
        quotaLockedPackages: Set<String>,
        sessionPackages: Set<String>,
        timedUnlockedPackages: Set<String>
    ): Set<String> = (quotaLockedPackages - timedUnlockedPackages) + sessionPackages

    fun lockedPackages(usage: List<DailyUsageEntity>, rules: List<AppLimitEntity>): Set<String> {
        val enabled = rules.filter { it.isEnabled && it.dailyLimitMillis > 0 }
        val locked = mutableSetOf<String>()
        for (rule in enabled) {
            if (rule.packageName != null) {
                if (usage.any { it.packageName == rule.packageName && it.totalTimeMillis >= rule.dailyLimitMillis })
                    locked.add(rule.packageName)
            } else if (rule.categoryId != null) {
                val members = usage.filter { it.categoryId == rule.categoryId }
                if (members.sumOf { it.totalTimeMillis } >= rule.dailyLimitMillis)
                    locked.addAll(members.map { it.packageName })
            }
        }
        return locked - usage.filter { it.isUnlocked }.map { it.packageName }.toSet()
    }
}

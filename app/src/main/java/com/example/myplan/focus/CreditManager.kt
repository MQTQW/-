package com.example.myplan.focus

import com.example.myplan.MyPlanApp

/**
 * Centralized credit economy — all credit awards/revocations go through here.
 * Prevents duplicate same-day awards and keeps the logic in one place.
 */
object CreditManager {

    /**
     * Award 1 credit for completing a MY PLAN plan today.
     * Returns true if this is the first time completing this plan today.
     */
    fun awardPlanCompletion(app: MyPlanApp, planId: Long): Boolean {
        if (app.planCreditsAwardedToday.add(planId)) {
            app.addCredits(1)
            return true
        }
        return false
    }

    /**
     * Revoke the credit for un-completing a plan (if not yet consumed).
     */
    fun revokePlanCompletion(app: MyPlanApp, planId: Long): Boolean {
        if (app.planCreditsAwardedToday.remove(planId)) {
            // Only revoke if user hasn't already spent the credit pool below 1
            if (app.unlockCredits.value > 0) {
                app.addCredits(-1)
            }
            return true
        }
        return false
    }

    /**
     * Award credits for completing a FocusFlow task.
     * Returns true if first time completing this task today.
     */
    fun awardTaskCompletion(app: MyPlanApp, taskId: Long, count: Int): Boolean {
        if (app.taskCreditsAwardedToday.add(taskId)) {
            app.addCredits(count)
            return true
        }
        return false
    }

    /**
     * Consume 1 credit to unlock a locked app.
     */
    fun consumeCredit(app: MyPlanApp): Boolean {
        return app.consumeCredit()
    }

    /**
     * Reset all credits at midnight.
     */
    fun resetDaily(app: MyPlanApp) {
        app.resetCredits()
    }
}

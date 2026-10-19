package com.example.myplan.focus

import com.example.myplan.focus.ForegroundUsage.Event
import com.example.myplan.focus.ForegroundUsage.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundUsageTest {
    private fun resume(at: Long, activity: String = "Main", pkg: String = "app") = Event(at, Kind.RESUME, pkg, activity)
    private fun pause(at: Long, activity: String = "Main", pkg: String = "app") = Event(at, Kind.PAUSE, pkg, activity)

    @Test fun anOngoingSessionKeepsGrowingWithoutWaitingForPause() {
        val events = listOf(resume(1_100))
        assertEquals(mapOf("app" to 400L), ForegroundUsage.merge(emptyMap(), events, 1_000, 1_500))
        assertEquals(mapOf("app" to 700L), ForegroundUsage.merge(emptyMap(), events, 1_000, 1_800))
    }

    @Test fun combinesCompletedAndOngoingSessionsWithoutAddingSystemSnapshotTwice() {
        val events = listOf(resume(1_050), pause(1_150), resume(1_250))
        assertEquals(mapOf("app" to 350L), ForegroundUsage.merge(mapOf("app" to 100L), events, 1_000, 1_500))
        assertEquals(mapOf("app" to 500L), ForegroundUsage.merge(mapOf("app" to 500L), events, 1_000, 1_500))
    }

    @Test fun duplicateResumesAndOverlappingActivitiesCountAsOnePackageInterval() {
        val events = listOf(resume(1_000, "A"), resume(1_050, "A"), resume(1_100, "B"),
            pause(1_200, "A"), Event(1_250, Kind.STOP, "app", "A"), pause(1_400, "B"))
        assertEquals(mapOf("app" to 400L), ForegroundUsage.merge(emptyMap(), events, 1_000, 2_000))
    }

    @Test fun clipsAnOvernightSessionToTheRequestedDay() {
        val events = listOf(resume(800), pause(1_200), resume(1_500))
        assertEquals(mapOf("app" to 700L), ForegroundUsage.merge(emptyMap(), events, 1_000, 2_000))
        assertEquals(mapOf("app" to 300L), ForegroundUsage.merge(emptyMap(), listOf(resume(800)), 1_000, 1_300))
    }

    @Test fun aSessionFinishedBeforeMidnightDoesNotCountToday() {
        val events = listOf(resume(700), pause(900))
        assertEquals(emptyMap<String, Long>(), ForegroundUsage.merge(emptyMap(), events, 1_000, 2_000))
    }

    @Test fun screenOffEndsAllPackagesAndLaterResumeStartsANewSession() {
        val events = listOf(resume(1_000), resume(1_100, pkg = "other"),
            Event(1_300, Kind.SCREEN_OFF), resume(1_500), pause(1_600))
        assertEquals(mapOf("app" to 400L, "other" to 200L), ForegroundUsage.merge(emptyMap(), events, 1_000, 2_000))
    }

    @Test fun shutdownAndStartupDoNotAccruePoweredOffTime() {
        val events = listOf(resume(800), Event(900, Kind.SHUTDOWN), Event(1_100, Kind.STARTUP), resume(1_200))
        assertEquals(mapOf("app" to 300L), ForegroundUsage.merge(emptyMap(), events, 1_000, 1_500))
        val missingShutdown = listOf(resume(800), Event(1_100, Kind.STARTUP), resume(1_200))
        assertEquals(mapOf("app" to 300L), ForegroundUsage.merge(emptyMap(), missingShutdown, 1_000, 1_500))
    }

    @Test fun stopWithoutPauseAlsoEndsTheSessionAndDuplicateEndsAreIgnored() {
        val events = listOf(resume(1_000), Event(1_250, Kind.STOP, "app", "Main"), pause(1_300))
        assertEquals(mapOf("app" to 250L), ForegroundUsage.merge(emptyMap(), events, 1_000, 2_000))
    }

    @Test fun emptyEventsRetainSystemUsageAndFutureEventsDoNotExtendTheWindow() {
        val snapshot = mapOf("app" to 123L, "other" to 456L)
        assertEquals(snapshot, ForegroundUsage.merge(snapshot, emptyList(), 1_000, 2_000))
        assertEquals(mapOf("app" to 100L), ForegroundUsage.merge(emptyMap(), listOf(resume(1_400), pause(2_000)), 1_000, 1_500))
    }

    @Test fun aPackageLevelPauseClosesAnActivityWithoutItsClassName() {
        val events = listOf(resume(1_000), Event(1_200, Kind.PAUSE, "app"))
        assertEquals(mapOf("app" to 200L), ForegroundUsage.merge(emptyMap(), events, 1_000, 2_000))
    }
}

package com.example.myplan.data

import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One observable local date, with a controllable clock for tests. */
class DateProvider(private val clock: Clock? = null) {
    private val _today = MutableStateFlow(currentDate())
    val today = _today.asStateFlow()

    fun currentDate(): LocalDate = LocalDate.now(clock ?: Clock.systemDefaultZone())
    fun refresh() { _today.value = currentDate() }
}

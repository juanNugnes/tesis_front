package com.juan.habitos.diagnostics

import com.juan.habitos.data.device.*
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** Resolve once before capture: midnight or a zone change must not relabel a sample. */
data class DiagnosticExportDay(val date: LocalDate, val zone: ZoneId) {
    companion object {
        fun resolve(yesterday: Boolean, clock: Clock = Clock.systemDefaultZone()): DiagnosticExportDay =
            DiagnosticExportDay(LocalDate.now(clock).minusDays(if (yesterday) 1 else 0), clock.zone)
    }
}

data class DiagnosticExportSample(
    val historical: DailyHistoricalUsageDiagnostic,
    val historicalJson: String,
    val observation: DailyDeviceInteractiveScreenObservation?,
) {
    companion object {
        fun from(historical: DailyHistoricalUsageDiagnostic, includeObservation: Boolean): DiagnosticExportSample =
            DiagnosticExportSample(
                historical,
                HistoricalUsageDiagnosticJson.encode(historical),
                if (includeObservation) InteractiveScreenObservationCalculator.calculate(historical) else null,
            )
    }
}

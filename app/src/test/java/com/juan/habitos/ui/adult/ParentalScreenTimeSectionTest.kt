package com.juan.habitos.ui.adult

import com.juan.habitos.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class ParentalScreenTimeSectionTest {
    @Test fun `shared form uses approved considerations question and explicit non persistence notice`() {
        assertEquals("¿Tu hijo/a tiene alguna dificultad de movimiento, visual, auditiva o sensibilidad a los sonidos que debamos considerar?", CHILD_CONSIDERATIONS_QUESTION)
        assertEquals("Demostración · Estas respuestas todavía no se almacenan ni modifican las recomendaciones.", PARENTAL_DEMO_NOTICE)
    }
    @Test fun `questions and all device scope use approved exact wording`() {
        assertEquals("Pensando en los últimos 30 días, ¿cuánto tiempo total pasó tu hijo/a frente a pantallas en un día habitual de lunes a viernes?", PARENTAL_WEEKDAY_QUESTION)
        assertEquals("Pensando en los últimos 30 días, ¿cuánto tiempo total pasó tu hijo/a frente a pantallas en un día habitual de fin de semana?", PARENTAL_WEEKEND_QUESTION)
        assertEquals("Considerá todos los dispositivos: celular, tablet, computadora, consola y televisión", PARENTAL_DEVICES_HINT)
        assertEquals("Elegí la opción que mejor represente el tiempo habitual", PARENTAL_CHOICE_HINT)
        assertEquals("Estas respuestas nos ayudan a conocer los hábitos digitales del niño/a. Son opcionales y no reemplazan las estadísticas registradas por el dispositivo", PARENTAL_EXPERIMENTAL_NOTICE)
    }
    @Test fun `original ordinal labels preserve category gaps and optional unavailable response`() {
        assertEquals(listOf("Menos de 1 hora", "Entre 1 y 2 horas", "Entre 3 y 4 horas", "Entre 5 y 6 horas", "7 horas o más", "No sé / Prefiero no responder"), ParentalTimeChoice.entries.map { it.label })
        assertEquals(listOf("Femenino", "Masculino", "No sé / Prefiero no responder"), ParentalSexChoice.entries.map { it.label })
        assertEquals(ParentalScreenTimeAnswers(null, null, null), ParentalScreenTimeSelections().answers())
    }
}

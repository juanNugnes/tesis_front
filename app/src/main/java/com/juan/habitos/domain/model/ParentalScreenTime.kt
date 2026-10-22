package com.juan.habitos.domain.model

// PHI-CSU CHGender (child, not caregiver): source audit + PMUM_C1_CONTRACT_2026-10-12.
// 1=Fiú (male), 2=Lány (female). Never infer this value from names or Android data.
enum class ParentalSexChoice(val code: Int?, val label: String) {
    FEMALE(2, "Femenino"), MALE(1, "Masculino"), UNAVAILABLE(null, "No sé / Prefiero no responder"),
}

enum class ParentalTimeChoice(val code: Int?, val label: String) {
    UNDER_ONE(1, "Menos de 1 hora"), ONE_TWO(2, "Entre 1 y 2 horas"), THREE_FOUR(3, "Entre 3 y 4 horas"),
    FIVE_SIX(4, "Entre 5 y 6 horas"), SEVEN_PLUS(5, "7 horas o más"), UNAVAILABLE(null, "No sé / Prefiero no responder"),
}

data class ParentalScreenTimeAnswers(val sexCode: Int?, val weekdayCategory: Int?, val weekendCategory: Int?)
data class ParentalScreenTimeSelections(
    val sex: ParentalSexChoice? = null,
    val weekday: ParentalTimeChoice? = null,
    val weekend: ParentalTimeChoice? = null,
) {
    fun answers() = ParentalScreenTimeAnswers(sex?.code, weekday?.code, weekend?.code)
    companion object {
        // The server cannot distinguish omission from an explicit unavailable answer.
        fun from(answers: ParentalScreenTimeAnswers) = ParentalScreenTimeSelections(
            ParentalSexChoice.entries.firstOrNull { answers.sexCode != null && it.code == answers.sexCode },
            ParentalTimeChoice.entries.firstOrNull { answers.weekdayCategory != null && it.code == answers.weekdayCategory },
            ParentalTimeChoice.entries.firstOrNull { answers.weekendCategory != null && it.code == answers.weekendCategory },
        )
    }
}

data class ParentalScreenTimeRecord(
    val id: String, val profileId: String, val revision: Int, val age: Int,
    val answers: ParentalScreenTimeAnswers,
)

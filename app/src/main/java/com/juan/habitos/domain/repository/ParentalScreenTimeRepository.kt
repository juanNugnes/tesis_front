package com.juan.habitos.domain.repository

import com.juan.habitos.domain.model.ParentalScreenTimeAnswers
import com.juan.habitos.domain.model.ParentalScreenTimeRecord

interface ParentalScreenTimeRepository {
    suspend fun latest(profileId: String): ParentalScreenTimeRecord?
    suspend fun save(profileId: String, answers: ParentalScreenTimeAnswers): ParentalScreenTimeRecord
}

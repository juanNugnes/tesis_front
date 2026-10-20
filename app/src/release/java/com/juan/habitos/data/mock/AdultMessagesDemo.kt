package com.juan.habitos.data.mock

import com.juan.habitos.data.remote.AdultMessagesRepository

/** The release artifact contains no simulated messages. */
internal fun createAdultMessagesDemoRepository(): AdultMessagesRepository =
    error("Adult message examples are unavailable in release")

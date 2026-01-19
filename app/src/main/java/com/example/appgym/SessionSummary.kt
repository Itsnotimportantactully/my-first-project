package com.example.appgym

data class SessionSummary(
    val sessionId: String,
    val startedAt: Long,
    val endedAt: Long,
    val setCount: Int
)

package com.ai.assistance.operit.host

data class OperitHostServiceManagerResult(
    val success: Boolean,
    val code: Int,
    val url: String,
    val body: String,
    val message: String,
    val serviceId: String,
    val state: String,
    val provider: String,
    val pid: Int,
    val serviceUrl: String,
    val error: String,
    val durationMs: Long
)

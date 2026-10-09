package com.hackpuntes.fridagate.data.models
 
import java.util.UUID
 
data class FridaScript(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val code: String,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val supportsIL2CPP: Boolean = false,
    val tags: List<String> = emptyList(),
    val isExecuting: Boolean = false,
    /** When true, include this user script in the next Launch Application action. */
    val enabledForLaunch: Boolean = false
)

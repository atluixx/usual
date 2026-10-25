package com.okoda.usual

import java.net.URI

data class AppDestination(val type: String, val coupleId: String? = null, val resourceId: String? = null) {
    companion object {
        fun parse(value: String?): AppDestination? = try {
            val uri = value?.let(::URI)
            val parts = uri?.path?.removePrefix("/")?.split("/").orEmpty()
            val validId = Regex("[A-Za-z0-9_-]{1,128}")
            when {
                uri == null || uri.scheme != "usual" || uri.rawQuery != null || uri.rawFragment != null || uri.userInfo != null || uri.port != -1 -> null
                uri.host == "action" && parts.size == 1 && parts[0] in listOf("draw", "photo", "game") -> AppDestination(parts[0])
                uri.host in listOf("content", "game") && parts.size == 2 && parts.all { validId.matches(it) } && uri.rawPath == uri.path -> AppDestination(uri.host, parts[0], parts[1])
                else -> null
            }
        } catch (_: Exception) { null }
    }
    fun link(): String = if (coupleId != null && resourceId != null) "usual://$type/$coupleId/$resourceId" else "usual://action/$type"
}

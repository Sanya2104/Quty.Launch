// *** api/model/SystemInfo.kt *** //
package by.quty.launch.api.model

import kotlinx.serialization.Serializable

/** Модель и версия Android. */
@Serializable
data class SystemInfo(
    val device: String,
    val version: String
)
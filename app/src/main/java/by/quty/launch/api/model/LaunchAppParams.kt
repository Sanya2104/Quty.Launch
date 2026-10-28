// *** api/model/LaunchAppParams.kt *** //
package by.quty.launch.api.model

import kotlinx.serialization.Serializable

/** Параметры LaunchApp. */
@Serializable
data class LaunchAppParams(
    val packageName: String
)
// *** api/base/ApiResponse.kt *** //
package by.quty.launch.api.base

import kotlinx.serialization.Serializable

@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: String? = null
)

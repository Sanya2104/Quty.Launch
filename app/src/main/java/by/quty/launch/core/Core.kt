// *** core/Core.kt *** //
package by.quty.launch.core

import by.quty.launch.api.router.ApiRouter

class Core {

    suspend fun execute(
        method: String,
        params: String?
    ): String {

        return ApiRouter.execute(method, params)
    }
}


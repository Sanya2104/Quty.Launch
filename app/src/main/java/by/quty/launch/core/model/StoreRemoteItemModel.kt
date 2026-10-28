// *** core/model/StoreRemoteItemModel.kt *** //
package by.quty.launch.core.model

import kotlinx.serialization.Serializable

/** Элемент каталога с сервера (store/shells.json). */
@Serializable
data class StoreRemoteItemModel(
    val id: String,
    val type: String = StoreItemTypeModel.SHELL.id,
    val name: String,
    val displayName: String,
    val author: String,
    val version: String,
    val previewUrl: String,
    val downloadUrl: String,
    val fileSize: String,
    val minQutyLaunchVersion: String? = null,
    val orientation: String? = null,
    val repoUrl: String? = null,
    val telegram: String? = null,
    val datePublished: String = ""
)
// *** core/model/TerminalCommandModel.kt *** //
package by.quty.launch.core.model

import androidx.annotation.StringRes

/**
 * Модель команды терминала.
 *
 * Используется реестром команд в [by.quty.launch.core.managers.TerminalManager].
 * Каждая команда описывается:
 * - именем (например, "version", "status", "set");
 * - локализованным описанием для вывода ошибок и справки;
 * - флагом DevMode (write-команды доступны только в DevMode);
 * - флагом isWrite (команда изменяет состояние и требует перезапуска);
 * - лямбдой-обработчиком, которая получает список аргументов
 *   и возвращает текст ответа.
 *
 * @property name имя команды без аргументов (например, "version")
 * @property descriptionRes ресурс описания (используется в help и ошибках)
 * @property requireDevMode true — команда доступна только при активном DevMode
 * @property isWrite true — команда изменяет состояние и требует перезапуска
 * @property handler обработчик: принимает аргументы, возвращает текст ответа
 */
data class TerminalCommandModel(
    val name: String,
    @StringRes val descriptionRes: Int,
    val requireDevMode: Boolean = false,
    val isWrite: Boolean = false,
    val handler: suspend (args: List<String>) -> String
)
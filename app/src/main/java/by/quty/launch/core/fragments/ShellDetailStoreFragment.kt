// *** core/fragments/ShellDetailStoreFragment.kt *** //
package by.quty.launch.core.fragments

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import by.quty.launch.R
import by.quty.launch.StoreActivity
import by.quty.launch.core.managers.StoreManager
import by.quty.launch.core.model.ShellStoreModel
import com.bumptech.glide.Glide
import kotlinx.coroutines.launch

/**
 * Фрагмент детального просмотра оболочки
 */
class ShellDetailStoreFragment : Fragment() {

    private lateinit var shellId: String
    private var storeManager: StoreManager? = null
    private var shell: ShellStoreModel? = null

    companion object {
        fun newInstance(shellId: String): ShellDetailStoreFragment {
            return ShellDetailStoreFragment().apply {
                arguments = Bundle().apply {
                    putString("shell_id", shellId)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shellId = arguments?.getString("shell_id") ?: ""
    }

    fun setStoreManager(manager: StoreManager) {
        storeManager = manager
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_store_shell_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        shell = storeManager?.getShellById(shellId)

        if (shell == null) {
            // Если оболочка не найдена — закрываем
            (activity as? StoreActivity)?.hideDetail()
            return
        }

        setupUI(view)
    }

    private fun setupUI(view: View) {
        val previewImage: ImageView = view.findViewById(R.id.shell_preview)
        val nameText: TextView = view.findViewById(R.id.shell_name)
        val authorText: TextView = view.findViewById(R.id.shell_author)
        val versionText: TextView = view.findViewById(R.id.shell_version)
        val dateText: TextView = view.findViewById(R.id.shell_date)
        val sizeText: TextView = view.findViewById(R.id.shell_size)
        val descriptionText: TextView = view.findViewById(R.id.shell_description)
        val tagsText: TextView = view.findViewById(R.id.shell_tags)
        val compatText: TextView = view.findViewById(R.id.shell_compat)
        val installButton: Button = view.findViewById(R.id.btn_install)
        val backButton: View = view.findViewById(R.id.btn_back)
        val closeButton: View = view.findViewById(R.id.btn_close)

        val shell = this.shell ?: return

        // Информация
        nameText.text = shell.displayName
        authorText.text = shell.author
        versionText.text = shell.version
        dateText.text = shell.datePublished
        sizeText.text = shell.fileSize
        descriptionText.text = shell.description

        // Теги
        tagsText.text = if (shell.tags.isNotEmpty()) {
            shell.tags.joinToString("  •  ", "#", "")
        } else {
            ""
        }

        // Совместимость
        compatText.text = getString(R.string.store_compat_required, shell.minQutyLaunchVersion)

        // Превью
        Glide.with(this)
            .load(shell.previewUrl)
            .placeholder(R.drawable.ic_settings)
            .error(R.drawable.ic_settings)
            .centerCrop()
            .into(previewImage)

        // Кнопка установки
        updateInstallButton(installButton, shell)

        installButton.setOnClickListener {
            if (shell.isInstalled) {
                // Уже установлена — ничего не делаем
                return@setOnClickListener
            }
            startInstallation(shell)
        }

        // Кнопка "Назад" — возвращает к списку оболочек
        backButton.setOnClickListener {
            (activity as? StoreActivity)?.hideDetail()
        }

        // Кнопка "Закрыть" — закрывает магазин полностью
        closeButton.setOnClickListener {
            activity?.finish()
        }
    }

    private fun updateInstallButton(button: Button, shell: ShellStoreModel) {
        val context = requireContext()

        if (shell.isInstalled) {
            button.text = getString(R.string.store_installed)
            button.isEnabled = false
            button.setBackgroundColor(
                ContextCompat.getColor(context, R.color.status_granted)
            )
        } else {
            button.text = getString(R.string.store_install)
            button.isEnabled = true
            button.setBackgroundColor(
                getColorFromAttribute(context, R.attr.buttonPrimaryColor)
            )
        }
    }

    private fun startInstallation(shell: ShellStoreModel) {
        val manager = storeManager ?: return

        val progressDialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.store_installing))
            .setMessage(getString(R.string.store_preparing))
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            manager.installShell(shell, object : StoreManager.DownloadListener {
                override fun onProgress(percent: Int) {
                    progressDialog.setMessage(getString(R.string.store_progress, percent))
                }

                override fun onSuccess() {
                    progressDialog.dismiss()
                    // Обновляем кнопку
                    updateInstallButton(
                        requireView().findViewById(R.id.btn_install),
                        shell.copy(isInstalled = true)
                    )
                    android.widget.Toast.makeText(
                        requireContext(),
                        getString(R.string.store_install_success, shell.displayName),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }

                override fun onError(message: String) {
                    progressDialog.dismiss()
                    android.widget.Toast.makeText(
                        requireContext(),
                        message,
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            })
        }
    }

    /**
     * Получает цвет из атрибута темы
     */
    private fun getColorFromAttribute(context: android.content.Context, attr: Int): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }
}
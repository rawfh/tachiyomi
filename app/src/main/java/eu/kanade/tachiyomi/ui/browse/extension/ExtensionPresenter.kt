package eu.kanade.tachiyomi.ui.browse.extension

import android.app.Application
import android.os.Bundle
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.ui.base.presenter.BasePresenter
import eu.kanade.tachiyomi.util.lang.launchIO
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private typealias ExtensionTuple =
    Triple<List<Extension.Installed>, List<Extension.Untrusted>, List<Extension.Available>>

/**
 * Presenter of [ExtensionController].
 */
open class ExtensionPresenter(
    private val extensionManager: ExtensionManager = Injekt.get(),
    private val preferences: PreferencesHelper = Injekt.get()
) : BasePresenter<ExtensionController>() {

    private var extensions = emptyList<ExtensionItem>()

    private var currentDownloads = hashMapOf<String, InstallStep>()

    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)

        extensionManager.findAvailableExtensions()

        launchIO {
            val installedFlow = extensionManager.installedExtensionsFlow
            val untrustedFlow = extensionManager.untrustedExtensionsFlow
            val availableFlow = extensionManager.availableExtensionsFlow

            combine(
                installedFlow,
                untrustedFlow,
                availableFlow
            ) { installed, untrusted, available ->
                Triple(installed, untrusted, available)
            }
                .debounce(100)
                .map(::toItems)
                .collectLatest { withContext(Dispatchers.Main) { view?.setExtensions(extensions) } }
        }
    }

    @Synchronized
    private fun toItems(tuple: ExtensionTuple): List<ExtensionItem> {
        val context = Injekt.get<Application>()
        val activeLangs = preferences.enabledLanguages().get()
        val showNsfwExtensions = preferences.showNsfwExtension().get()

        val (installed, untrusted, available) = tuple

        val items = mutableListOf<ExtensionItem>()

        val updatesSorted = installed.filter { it.hasUpdate && (showNsfwExtensions || !it.isNsfw) }.sortedBy { it.pkgName }
        val installedSorted = installed.filter { !it.hasUpdate && (showNsfwExtensions || !it.isNsfw) }.sortedWith(compareBy({ !it.isObsolete }, { it.pkgName }))
        val untrustedSorted = untrusted.sortedBy { it.pkgName }
        val availableSorted = available
            // Filter out already installed extensions and disabled languages
            .filter { avail ->
                installed.none { it.pkgName == avail.pkgName } &&
                    untrusted.none { it.pkgName == avail.pkgName } &&
                    (avail.lang in activeLangs || avail.lang == "all") &&
                    (showNsfwExtensions || !avail.isNsfw)
            }
            .sortedBy { it.pkgName }

        if (updatesSorted.isNotEmpty()) {
            val header = ExtensionGroupItem(context.getString(R.string.ext_updates_pending), updatesSorted.size, true)
            items += updatesSorted.map { extension ->
                ExtensionItem(extension, header, currentDownloads[extension.pkgName])
            }
        }
        if (installedSorted.isNotEmpty() || untrustedSorted.isNotEmpty()) {
            val header = ExtensionGroupItem(context.getString(R.string.ext_installed), installedSorted.size + untrustedSorted.size)
            items += installedSorted.map { extension ->
                ExtensionItem(extension, header, currentDownloads[extension.pkgName])
            }
            items += untrustedSorted.map { extension ->
                ExtensionItem(extension, header)
            }
        }
        if (availableSorted.isNotEmpty()) {
            val availableGroupedByLang = availableSorted
                .groupBy { LocaleHelper.getSourceDisplayName(it.lang, context) }
                .toSortedMap()

            availableGroupedByLang
                .forEach {
                    val header = ExtensionGroupItem(it.key, it.value.size)
                    items += it.value.map { extension ->
                        ExtensionItem(extension, header, currentDownloads[extension.pkgName])
                    }
                }
        }

        this.extensions = items
        return items
    }

    @Synchronized
    private fun updateInstallStep(extension: Extension, state: InstallStep): ExtensionItem? {
        val extensions = extensions.toMutableList()
        val position = extensions.indexOfFirst { it.extension.pkgName == extension.pkgName }

        return if (position != -1) {
            val item = extensions[position].copy(installStep = state)
            extensions[position] = item

            this.extensions = extensions
            item
        } else {
            null
        }
    }

    fun installExtension(extension: Extension.Available) {
        extensionManager.installExtension(extension).subscribeToInstallUpdate(extension)
    }

    fun updateExtension(extension: Extension.Installed) {
        extensionManager.updateExtension(extension).subscribeToInstallUpdate(extension)
    }

    private fun Observable<InstallStep>.subscribeToInstallUpdate(extension: Extension) {
        this.doOnNext { currentDownloads[extension.pkgName] = it }
            .doOnUnsubscribe { currentDownloads.remove(extension.pkgName) }
            .map { state -> updateInstallStep(extension, state) }
            .subscribeWithView({ view, item ->
                if (item != null) {
                    view.downloadUpdate(item)
                }
            })
    }

    fun uninstallExtension(pkgName: String) {
        extensionManager.uninstallExtension(pkgName)
    }

    fun findAvailableExtensions() {
        extensionManager.findAvailableExtensions()
    }

    fun trustSignature(signatureHash: String) {
        extensionManager.trustSignature(signatureHash)
    }
}

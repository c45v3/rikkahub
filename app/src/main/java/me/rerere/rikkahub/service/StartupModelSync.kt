package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import me.rerere.ai.provider.ModelSyncMode
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.planModelSync
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import kotlin.uuid.Uuid

private const val TAG = "StartupModelSync"

/**
 * Called once per app foreground entry, not from a provider settings screen.
 *
 * A provider is eligible only after its initial manual preview has been accepted.
 * Requests happen off the UI thread. Model changes are committed against the latest
 * settings so a model edit or a settings change during the request is not overwritten.
 */
suspend fun syncModelsOnAppForeground(
    settingsStore: SettingsStore,
    providerManager: ProviderManager,
) {
    val providers = settingsStore.awaitLoaded().providers.filter { provider ->
        val options = provider.modelSyncOptions
        options.automatic && options.initialized && options.mode != ModelSyncMode.OFF
    }
    supervisorScope {
        val dispatcher = Dispatchers.IO.limitedParallelism(3)
        providers.map { requested ->
            launch(dispatcher) {
                try {
                    val remote = providerManager.getProviderByType(requested)
                        .listModels(requested)
                    if (remote.isEmpty()) {
                        Log.w(TAG, "Skipping empty upstream list for ${requested.name}")
                        return@launch
                    }

                    settingsStore.update { latest ->
                        val current = latest.providers.firstOrNull { it.id == requested.id }
                            ?: return@update latest
                        val options = current.modelSyncOptions
                        if (!options.automatic || !options.initialized ||
                            options.mode == ModelSyncMode.OFF ||
                            !current.sameSyncConfiguration(requested)
                        ) {
                            return@update latest
                        }

                        val plan = planModelSync(
                            local = current.models,
                            remote = remote,
                            mode = options.mode,
                            referencedModelIds = latest.referencedModelIds(),
                        )
                        if (plan.added.isEmpty() && plan.removed.isEmpty()) {
                            return@update latest
                        }
                        Log.i(TAG, "Synced ${current.name}: +${plan.added.size} -${plan.removed.size}")
                        latest.copy(
                            providers = latest.providers.map { provider ->
                                if (provider.id == current.id) {
                                    current.copyProvider(models = plan.models)
                                } else {
                                    provider
                                }
                            }
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A failure on one provider must not block another provider or erase models.
                    Log.w(TAG, "Failed to synchronize ${requested.name}", e)
                }
            }
        }.joinAll()
    }
}

// Ignores only the local model list: edits to credentials, connection settings,
// or the synchronization options while a request is in flight invalidate that result.
private fun ProviderSetting.sameSyncConfiguration(other: ProviderSetting): Boolean =
    copyProvider(models = emptyList()) == other.copyProvider(models = emptyList())

private fun Settings.referencedModelIds(): Set<Uuid> = buildSet {
    add(chatModelId)
    add(fastModelId)
    add(imageGenerationModelId)
    add(ocrModelId)
    addAll(favoriteModels)
    assistants.mapNotNullTo(this) { it.chatModelId }
}

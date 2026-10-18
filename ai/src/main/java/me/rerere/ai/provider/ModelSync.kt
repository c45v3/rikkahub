package me.rerere.ai.provider

import me.rerere.ai.registry.ModelRegistry
import kotlin.uuid.Uuid

/**
 * A non-destructive preview of model synchronization. Existing Model instances are retained,
 * including their UUID, user-defined capabilities, context length, headers and overrides.
 */
data class ModelSyncPlan(
    val models: List<Model>,
    val added: List<Model>,
    val removed: List<Model>,
    val protectedMissing: List<Model>,
)

fun planModelSync(
    local: List<Model>,
    remote: List<Model>,
    mode: ModelSyncMode,
    referencedModelIds: Set<Uuid> = emptySet(),
): ModelSyncPlan {
    if (mode == ModelSyncMode.OFF) {
        return ModelSyncPlan(local, emptyList(), emptyList(), emptyList())
    }

    val upstream = remote.filter { it.modelId.isNotBlank() }.distinctBy { it.modelId }
    val upstreamIds = upstream.mapTo(mutableSetOf()) { it.modelId }
    val localIds = local.mapTo(mutableSetOf()) { it.modelId }

    val protectedMissing = if (mode == ModelSyncMode.MIRROR) {
        local.filter {
            it.modelId !in upstreamIds && (it.keepOnSync || it.id in referencedModelIds)
        }
    } else {
        emptyList()
    }

    val removed = if (mode == ModelSyncMode.MIRROR) {
        local.filter {
            it.modelId !in upstreamIds && !it.keepOnSync && it.id !in referencedModelIds
        }
    } else {
        emptyList()
    }
    val removedIds = removed.mapTo(mutableSetOf()) { it.id }
    val added = upstream.filter { it.modelId !in localIds }.map { model ->
        model.copy(
            inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(model.modelId),
            outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(model.modelId),
            abilities = ModelRegistry.MODEL_ABILITIES.getData(model.modelId),
        )
    }

    return ModelSyncPlan(
        models = local.filterNot { it.id in removedIds } + added,
        added = added,
        removed = removed,
        protectedMissing = protectedMissing,
    )
}

package me.rerere.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSyncTest {
    @Test
    fun mirrorPreservesExistingConfigurationAndIdentity() {
        val customized = Model(modelId = "keep", displayName = "Custom", contextLength = 42)
        val obsolete = Model(modelId = "old")
        val remote = listOf(Model(modelId = "keep"), Model(modelId = "new"))
        val plan = planModelSync(listOf(customized, obsolete), remote, ModelSyncMode.MIRROR)
        assertEquals(listOf("keep", "new"), plan.models.map { it.modelId })
        assertEquals(customized, plan.models.first())
        assertEquals(listOf(obsolete), plan.removed)
        assertEquals(listOf("new"), plan.added.map { it.modelId })
    }

    @Test
    fun protectedAndReferencedModelsSurviveRemoval() {
        val protected = Model(modelId = "manual", keepOnSync = true)
        val used = Model(modelId = "active")
        val plan = planModelSync(listOf(protected, used), listOf(Model(modelId = "new")),
            ModelSyncMode.MIRROR, setOf(used.id))
        assertTrue(plan.removed.isEmpty())
        assertEquals(2, plan.protectedMissing.size)
        assertEquals(3, plan.models.size)
    }

    @Test
    fun addOnlyNeverRemovesExistingModels() {
        val existing = Model(modelId = "old")
        val plan = planModelSync(listOf(existing), listOf(Model(modelId = "new")), ModelSyncMode.ADD_ONLY)
        assertEquals(listOf("old", "new"), plan.models.map { it.modelId })
        assertTrue(plan.removed.isEmpty())
    }

    @Test
    fun noDuplicatesOnRepeatedSynchronization() {
        val first = planModelSync(emptyList(), listOf(Model(modelId = "same")),
            ModelSyncMode.MIRROR)
        val second = planModelSync(first.models, listOf(Model(modelId = "same")),
            ModelSyncMode.MIRROR)
        assertTrue(second.added.isEmpty())
        assertTrue(second.removed.isEmpty())
        assertEquals(first.models, second.models)
    }
}

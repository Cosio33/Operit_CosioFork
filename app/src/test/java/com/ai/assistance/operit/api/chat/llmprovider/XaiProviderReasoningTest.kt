package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.data.collects.ApiProviderConfigs
import com.ai.assistance.operit.data.collects.ModelThinkingConfigDefaults
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.util.AppLogger
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * xAI exposes `reasoning_effort` for the Grok family. The mapping is driven by the shared
 * thinking-configuration system (the "xai-grok-reasoning-effort" rule), not by a
 * provider-specific mapper.
 */
class XaiProviderReasoningTest {
    private var previousSystemLogEnabled = true
    private var previousFileLogEnabled = true

    @Before
    fun disableAndroidLogging() {
        previousSystemLogEnabled = AppLogger.enableSystemLog
        previousFileLogEnabled = AppLogger.enableFileLogging
        AppLogger.enableSystemLog = false
        AppLogger.enableFileLogging = false
    }

    @After
    fun restoreAndroidLogging() {
        AppLogger.enableSystemLog = previousSystemLogEnabled
        AppLogger.enableFileLogging = previousFileLogEnabled
    }


    private fun mapping(modelName: String): ThinkingQualityMapping =
        ThinkingQualityMappingRegistry.resolve(
            providerTypeId = ApiProviderType.XAI.name,
            modelName = modelName,
            thinkingConfigurations = ModelThinkingConfigDefaults.forProvider(ApiProviderType.XAI.name)
        )

    /** Mirrors what XaiProvider.createRequestBody sends for a seeded config. */
    private fun requestBody(modelName: String, optionId: String, enableThinking: Boolean): JSONObject {
        val requestJson = JSONObject().put("model", modelName).put("stream", true)
        ThinkingConfigurationApplier.apply(
            requestJson = requestJson,
            providerTypeId = ApiProviderType.XAI.name,
            modelName = modelName,
            apiEndpoint = "https://api.x.ai/v1/chat/completions",
            thinkingConfigurations = ModelThinkingConfigDefaults.forProvider(ApiProviderType.XAI.name),
            enableThinking = enableThinking,
            optionId = optionId,
        )
        return requestJson
    }

    @Test
    fun defaultConfigUsesTheOfficialXaiEndpointAndModel() {
        assertEquals(
            "grok-4.6",
            ApiProviderConfigs.getDefaultModelName(ApiProviderType.XAI)
        )
        assertEquals(
            "https://api.x.ai/v1/chat/completions",
            ApiProviderConfigs.getDefaultApiEndpoint(ApiProviderType.XAI)
        )
        assertEquals(
            "https://api.x.ai/v1/models",
            ModelListFetcher.getModelsListUrl(
                "https://api.x.ai/v1/chat/completions",
                ApiProviderType.XAI
            )
        )
    }

    @Test
    fun enabledOptionsMapToXaiEfforts() {
        val mapping = mapping("grok-4.6")
        assertEquals(ThinkingQualityControl.LEVELS, mapping.control)
        assertEquals(
            listOf("low", "medium", "high", "xhigh"),
            mapping.options.map { it.id }
        )
        for (effort in listOf("low", "medium", "high", "xhigh")) {
            assertEquals(
                effort,
                requestBody("grok-4.6", optionId = effort, enableThinking = true)
                    .getString("reasoning_effort")
            )
        }
    }

    @Test
    fun mapperPreservesTheSelectedEffort() {
        assertEquals(
            "high",
            requestBody("grok-4.6", optionId = "high", enableThinking = true)
                .getString("reasoning_effort")
        )
    }

    @Test
    fun reasoningEffortUsesTheGrokFamilyRule() {
        for (model in listOf("grok-4.6", "grok-4.5-latest", "grok-3-mini")) {
            assertEquals(model, ThinkingQualityControl.LEVELS, mapping(model).control)
        }
        assertEquals(
            ThinkingQualityControl.UNSUPPORTED,
            mapping("gpt-4o").control
        )
    }

    @Test
    fun grokReasoningEffortIsAlwaysPresentBecauseTheRuleIsRequired() {
        // The xai-grok-reasoning-effort rule is marked as required, so the parameter
        // reaches the wire even when thinking is turned off.
        val body = requestBody("grok-4.6", optionId = "medium", enableThinking = false)
        assertTrue(body.has("reasoning_effort"))
        assertEquals("medium", body.getString("reasoning_effort"))
        assertFalse(body.has("thinking"))
    }
}

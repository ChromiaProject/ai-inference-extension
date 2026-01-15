package net.postchain.gtx.extensions.ai_inference

import net.postchain.PostchainContext
import net.postchain.config.app.AppConfig
import net.postchain.core.BlockchainConfiguration
import net.postchain.core.EContext
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.mapper.GtvObjectMapper
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

open class AiInferenceBaseTest {
    protected fun createEngine(model: String? = null, maxTokens: Long = 100L, timeout: Long = 10L): AiInferenceComputeEngine {
        val modelToUse = model
                ?: System.getenv("AI_SERVICE_MODEL")
                ?: throw IllegalArgumentException("AI_SERVICE_MODEL environment variable not set")

        val engine = AiInferenceComputeEngine()
        val blockchainConfig = AiInferenceConfig(
                model = modelToUse,
                inferenceTimeoutSeconds = timeout,
                verificationTimeoutSeconds = timeout,
                maxCompletionTokens = maxTokens,
        )
        val configuration = mock<BlockchainConfiguration> {
            on { rawConfig } doReturn gtv(mapOf(AiInferenceComputeEngine.NAME to GtvObjectMapper.toGtvDictionary(blockchainConfig)))
        }
        val postchainContext = mock<PostchainContext> {
            on { appConfig } doReturn AppConfig.fromEnvironment()
        }
        val ctx = mock<EContext>()
        engine.initializeContext(configuration, postchainContext, ctx)
        engine.load()
        return engine
    }
}

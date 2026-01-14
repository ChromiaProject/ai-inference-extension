package net.postchain.gtx.extensions.ai_inference

import assertk.assertFailure
import assertk.assertions.isInstanceOf
import mu.KLogging
import net.postchain.PostchainContext
import net.postchain.common.exception.UserMistake
import net.postchain.config.app.AppConfig
import net.postchain.core.BlockchainConfiguration
import net.postchain.core.EContext
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtx.extensions.ai_inference.rell.lib.ai_inference.Request
import net.postchain.gtx.extensions.ai_inference.rell.lib.ai_inference.Response
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class AiInferenceIT {
    companion object : KLogging() {
        lateinit var engine: AiInferenceComputeEngine

        @BeforeAll
        @JvmStatic
        fun setup() {
            val model = System.getenv("AI_SERVICE_MODEL")
                    ?: throw IllegalArgumentException("AI_SERVICE_MODEL environment variable not set")

            engine = AiInferenceComputeEngine()
            val blockchainConfig = AiInferenceConfig(
                    model = model,
                    inferenceTimeoutSeconds = 10L,
                    verificationTimeoutSeconds = 10L,
                    maxCompletionTokens = 100L,
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
        }
    }

    @Test
    @Disabled // for manual testing
    fun `text inference`() {
        val prompt = "Translate 'hello' to French:"
        val (response, points) = engine.generateText(prompt, "simple")
        println(response)
        println("Generated ${response.textTokens.size} tokens, cost $points points")
    }

    @Test
    @Disabled // for manual testing
    fun `chat inference`() {
        val messages = listOf(ChatMessage(role = "user", content = "What is the capital of France?"))
        val (response, points) = engine.generateChat(messages, null)
        println(response)
        println("Generated ${response.textTokens.size} tokens, cost $points points")
    }

    @Test
    @Disabled // for manual testing
    fun validation() {
        engine.verifyTextGeneration(
                promptTokens = listOf(1, 9690, 198, 2683, 359, 253, 5356, 5646, 11173, 3365, 3511, 308, 34519, 28, 7018, 411, 407, 19712, 8182, 2, 198, 1, 4093, 198, 1780, 314, 260, 3575, 282, 4649, 47, 2, 198, 1, 520, 9531, 198),
                textTokens = listOf(504, 3575, 282, 4649, 314, 7042, 30, 2)
        )
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "Hello, world! My name is",
        "Hello, how are you?",
        "How is the weather in Stockholm?",
        "What is Kotlin used for?",
        "What is the capital of France?",
        "Translate 'hello' to French:"])
    fun `text inference and validation`(prompt: String) {
        val input = GtvObjectMapper.toGtvDictionary(Request(prompt, messages = null, stop = null))
        engine.compute(input)
    }

    @Test
    fun `text inference and validation with stop sequence`() {
        val input = GtvObjectMapper.toGtvDictionary(Request("What is the capital of France?", messages = null, stop = "."))
        engine.compute(input)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "Hello, world! My name is",
        "Hello, how are you?",
        "How is the weather in Stockholm?",
        "What is Kotlin used for?",
        "What is the capital of France?",
        "Translate 'hello' to French:"])
    fun `chat inference and validation`(prompt: String) {
        val input = GtvObjectMapper.toGtvDictionary(Request(prompt = null, messages = listOf(
                net.postchain.gtx.extensions.ai_inference.rell.lib.ai_inference.ChatMessage(role = "user", message = prompt)
        ), stop = null))
        engine.compute(input)
    }

    @Test
    fun `negative validation`() {
        assertFailure {
            val input = GtvObjectMapper.toGtvDictionary(Request("Some prompt", messages = null, stop = null))
            val invalidOutput = GtvObjectMapper.toGtvDictionary(Response(
                    promptTokens = listOf(1, 9690, 198, 2683, 359, 253, 5356, 5646, 11173, 3365, 3511, 308, 34519, 28, 7018, 411, 407, 19712, 8182, 2, 198, 1, 4093, 198, 1780, 314, 260, 3575, 282, 4649, 47, 2, 198, 1, 520, 9531, 198),
                    textTokens = listOf(504, 3575, 282, 4649, 314, 7042, 30, 3),
                    text = "", // not used
            ))
            engine.validate(input, invalidOutput)
        }.isInstanceOf(UserMistake::class.java)
    }
}

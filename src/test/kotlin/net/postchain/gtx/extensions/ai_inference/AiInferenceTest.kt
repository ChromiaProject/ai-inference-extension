package net.postchain.gtx.extensions.ai_inference

import assertk.assertFailure
import assertk.assertions.isInstanceOf
import mu.KLogging
import net.postchain.common.BlockchainRid
import net.postchain.common.exception.UserMistake
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtx.extensions.ai_inference.rell.lib.ai_inference.Request
import net.postchain.gtx.extensions.ai_inference.rell.lib.ai_inference.Response
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

const val URL = "http://localhost:5000" // TODO test URL
const val MODEL = "the-model" // TODO test model

class AiInferenceTest {
    companion object : KLogging() {
        lateinit var engine: AiInferenceComputeEngine

        @BeforeAll
        @JvmStatic
        fun setup() {
            engine = AiInferenceComputeEngine()
            engine.nodeConfig = AiInferenceNodeConfig(url = URL, basicAuth = null)
            val testConfig = AiInferenceConfig(
                    model = MODEL,
                    timeoutSeconds = 10.toLong(),
            )
            engine.init(
                    gtv(mapOf(AiInferenceComputeEngine.NAME to GtvObjectMapper.toGtvDictionary(testConfig))),
                    BlockchainRid.ZERO_RID
            )
            engine.load()
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            engine.shutdown()
        }
    }

    @Test
    @Disabled // for manual testing
    fun `text inference`() {
        val prompt = "Translate 'hello' to French:"
        val response = engine.generateText(prompt)
        println(response)
    }

    @Test
    @Disabled // for manual testing
    fun `chat inference`() {
        val messages = listOf(ChatMessage(role = "user", content = "What is the capital of France?"))
        val response = engine.generateChat(messages)
        println(response)
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
        "Hello, how are you?",
        "How is the weather in Stockholm?",
        "What is Kotlin used for?",
        "What is the capital of France?",
        "Translate 'hello' to French:"])
    @Disabled // TODO enable test
    fun `text inference and validation`(prompt: String) {
        val input = GtvObjectMapper.toGtvDictionary(Request(prompt, messages = null))
        val output = engine.compute(input)
        engine.validate(output)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "Hello, how are you?",
        "How is the weather in Stockholm?",
        "What is Kotlin used for?",
        "What is the capital of France?",
        "Translate 'hello' to French:"])
    @Disabled // TODO enable test
    fun `chat inference and validation`(prompt: String) {
        val input = GtvObjectMapper.toGtvDictionary(Request(prompt = null, messages = listOf(
                net.postchain.gtx.extensions.ai_inference.rell.lib.ai_inference.ChatMessage(role = "user", message = prompt)
        )))
        val output = engine.compute(input)
        engine.validate(output)
    }

    @Test
    @Disabled // TODO enable test
    fun `negative validation`() {
        assertFailure {
            val invalidOutput = GtvObjectMapper.toGtvDictionary(Response(
                    promptTokens = listOf(1, 9690, 198, 2683, 359, 253, 5356, 5646, 11173, 3365, 3511, 308, 34519, 28, 7018, 411, 407, 19712, 8182, 2, 198, 1, 4093, 198, 1780, 314, 260, 3575, 282, 4649, 47, 2, 198, 1, 520, 9531, 198),
                    textTokens = listOf(504, 3575, 282, 4649, 314, 7042, 30, 3),
                    text = "", // not used
            ))
            engine.validate(invalidOutput)
        }.isInstanceOf(UserMistake::class.java)
    }
}

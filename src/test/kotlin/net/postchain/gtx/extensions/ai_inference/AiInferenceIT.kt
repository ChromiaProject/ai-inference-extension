package net.postchain.gtx.extensions.ai_inference

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.messageContains
import com.sun.net.httpserver.HttpServer.create
import net.postchain.common.createLogCaptor
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.exception.UserMistake
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtx.extensions.ai_inference.rell.lib.ai_inference.Request
import net.postchain.gtx.extensions.ai_inference.rell.lib.ai_inference.Response
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables
import java.net.InetSocketAddress
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

class AiInferenceIT : AiInferenceBaseTest() {

    @Test
    @Disabled // for manual testing
    fun `text inference`() {
        val engine = createEngine()
        val prompt = "Translate 'hello' to French:"
        val (response, points) = engine.generateText(prompt, "simple")
        println(response)
        println("Generated ${response.textTokens.size} tokens, cost $points points")
    }

    @Test
    @Disabled // for manual testing
    fun `chat inference`() {
        val engine = createEngine()
        val messages = listOf(ChatMessage(role = "user", content = "What is the capital of France?"))
        val (response, points) = engine.generateChat(messages, null)
        println(response)
        println("Generated ${response.textTokens.size} tokens, cost $points points")
    }

    @Test
    @Disabled // for manual testing
    fun verification() {
        val engine = createEngine()
        engine.verifyTextGeneration(
                promptTokens = listOf(1, 9690, 198, 2683, 359, 253, 5356, 5646, 11173, 3365, 3511, 308, 34519, 28, 7018, 411, 407, 19712, 8182, 2, 198, 1, 4093, 198, 1780, 314, 260, 3575, 282, 4649, 47, 2, 198, 1, 520, 9531, 198),
                textTokens = listOf(504, 3575, 282, 4649, 314, 7042, 30, 2)
        )
    }

    @Test
    fun `estimate points for text`() {
        val engine = createEngine()
        val prompt = "Hello, how are you?"
        val input = GtvObjectMapper.toGtvDictionary(Request(prompt, messages = null, stop = null))
        val points = engine.estimatePoints(input)
        assertThat(points).isGreaterThan(0)
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
        val engine = createEngine()
        val input = GtvObjectMapper.toGtvDictionary(Request(prompt, messages = null, stop = null))
        engine.compute(input)
    }

    @Test
    fun `text inference and validation with stop sequence`() {
        val engine = createEngine()
        val input = GtvObjectMapper.toGtvDictionary(Request("What is the capital of France?", messages = null, stop = "."))
        engine.compute(input)
    }

    @Test
    fun `estimate points for chat`() {
        val engine = createEngine()
        val prompt = "Hello, how are you?"
        val input = GtvObjectMapper.toGtvDictionary(Request(null, messages = listOf(
                net.postchain.gtx.extensions.ai_inference.rell.lib.ai_inference.ChatMessage(role = "user", message = prompt)
        ), stop = null))
        val points = engine.estimatePoints(input)
        assertThat(points).isGreaterThan(0)
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
        val engine = createEngine()
        val input = GtvObjectMapper.toGtvDictionary(Request(prompt = null, messages = listOf(
                net.postchain.gtx.extensions.ai_inference.rell.lib.ai_inference.ChatMessage(role = "user", message = prompt)
        ), stop = null))
        engine.compute(input)
    }

    @Test
    fun `negative validation with cache`() {
        val appender = createLogCaptor(AiInferenceComputeEngine::class.java, "Engine")

        val engine = createEngine()
        val input = GtvObjectMapper.toGtvDictionary(Request("Some prompt", messages = null, stop = null))
        val invalidOutput = GtvObjectMapper.toGtvDictionary(Response(
                promptTokens = listOf(1, 9690, 198, 2683, 359, 253, 5356, 5646, 11173, 3365, 3511, 308, 34519, 28, 7018, 411, 407, 19712, 8182, 2, 198, 1, 4093, 198, 1780, 314, 260, 3575, 282, 4649, 47, 2, 198, 1, 520, 9531, 198),
                textTokens = listOf(504, 3575, 282, 4649, 314, 7042, 30, 3),
                text = "", // not used
        ))
        assertFailure {
            engine.validate(input, invalidOutput)
        }.isInstanceOf(UserMistake::class.java).messageContains("Generated text does not match")
        assertFailure {
            engine.validate(input, invalidOutput)
        }.isInstanceOf(UserMistake::class.java).messageContains("Generated text does not match")

        assertThat(appender.events.find { it.message.toString().contains("is_verified_greedy=false") }).isNotNull()
        assertThat(appender.events.find { it.message.toString() == "Returning cached verification failure" }).isNotNull()
    }

    @Test
    fun `non existing model`() {
        val engine = createEngine(model = "bogus-model")
        val input = GtvObjectMapper.toGtvDictionary(Request("What is the capital of France?", messages = null, stop = "."))
        assertFailure {
            engine.compute(input)
        }.isInstanceOf(UserMistake::class.java).messageContains("The model `bogus-model` does not exist")
    }

    @Test
    fun `too many max tokens`() {
        val engine = createEngine(maxTokens = 10000L)
        val input = GtvObjectMapper.toGtvDictionary(Request("What is the capital of France?", messages = null, stop = "."))
        assertFailure {
            engine.compute(input)
        }.isInstanceOf(UserMistake::class.java).messageContains("is too large: 10000. This model's maximum context length is 4068 tokens")
    }

    @Test
    fun `too many input tokens`() {
        val engine = createEngine()
        val input = GtvObjectMapper.toGtvDictionary(Request("What is the capital of France? ".repeat(1000), messages = null, stop = null))
        assertFailure {
            engine.compute(input)
        }.isInstanceOf(UserMistake::class.java).messageContains("This model's maximum context length is 4068 tokens. However, your request has")
    }

    @Test
    fun `auth error`() {
        val engine = EnvironmentVariables(AiInferenceNodeConfig.CONFIG_ENV_PREFIX + AiInferenceNodeConfig.BASIC_AUTH_PASSWORD, "wrong_password").execute(Callable {
            createEngine()
        })
        val input = GtvObjectMapper.toGtvDictionary(Request("What is the capital of France?", messages = null, stop = "."))
        assertFailure {
            engine.compute(input)
        }.isInstanceOf(ProgrammerMistake::class.java).messageContains("Failed to generate text: 401 Unauthorized")
    }

    @Test
    fun `wrong URL`() {
        val actualUrl = System.getenv(AiInferenceNodeConfig.CONFIG_ENV_PREFIX + AiInferenceNodeConfig.URL)
        val engine = EnvironmentVariables(AiInferenceNodeConfig.CONFIG_ENV_PREFIX + AiInferenceNodeConfig.URL, "$actualUrl/bogus").execute(Callable {
            createEngine()
        })
        val input = GtvObjectMapper.toGtvDictionary(Request("What is the capital of France?", messages = null, stop = "."))
        assertFailure {
            engine.compute(input)
        }.isInstanceOf(ProgrammerMistake::class.java).messageContains("Failed to generate text: Not Found")
    }

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun retries() {
        var attempts = 0
        val server = create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            attempts++
            val response = "{\"error\": {\"message\": \"the_error\"}}".toByteArray()
            exchange.sendResponseHeaders(500, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val engine = EnvironmentVariables(AiInferenceNodeConfig.CONFIG_ENV_PREFIX + AiInferenceNodeConfig.URL, "http://localhost:${server.address.port}").execute(Callable {
                createEngine()
            })
            val input = GtvObjectMapper.toGtvDictionary(Request("What is the capital of France?", messages = null, stop = "."))
            assertFailure {
                engine.compute(input)
            }.isInstanceOf(ProgrammerMistake::class.java).messageContains("the_error")
            assertThat(attempts).isEqualTo(5)
        } finally {
            server.stop(0)
        }
    }
}

package net.postchain.gtx.extensions.ai_inference

import assertk.assertFailure
import assertk.assertions.isInstanceOf
import mu.KLogging
import net.postchain.PostchainContext
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.config.app.AppConfig
import net.postchain.core.BlockchainConfiguration
import net.postchain.core.EContext
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtx.extensions.ai_inference.rell.lib.ai_inference.Request
import net.postchain.gtx.extensions.ai_inference.rell.lib.ai_inference.Response
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.nio.channels.ServerSocketChannel
import java.util.concurrent.TimeUnit

class TimeoutIT {
    companion object : KLogging()

    val unroutableInternetUrl = "http://10.255.255.1:1"

    @Test
    @Timeout(15, unit = TimeUnit.SECONDS)
    fun `connect timeout`() {
        val engine = createEngine(unroutableInternetUrl)
        assertFailure {
            engine.compute(GtvObjectMapper.toGtvDictionary(Request(prompt = "hello", messages = null)))
        }.isInstanceOf(ProgrammerMistake::class)
    }

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `request timeout compute`() {
        withRequestTimeoutServer { url ->
            val engine = createEngine(url)
            assertFailure {
                engine.compute(GtvObjectMapper.toGtvDictionary(Request(prompt = "hello", messages = null)))
            }.isInstanceOf(ProgrammerMistake::class)
        }
    }

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `request timeout validate`() {
        withRequestTimeoutServer { url ->
            val engine = createEngine(url)
            assertFailure {
                engine.validate(
                        GtvObjectMapper.toGtvDictionary(Request(prompt = "hello", messages = null)),
                        GtvObjectMapper.toGtvDictionary(Response(listOf(), listOf(), "hello")),
                )
            }.isInstanceOf(ProgrammerMistake::class)
        }
    }

    private fun withRequestTimeoutServer(block: (url: String) -> Unit) {
        ServerSocketChannel.open(StandardProtocolFamily.INET).use { serverSocketChannel ->
            serverSocketChannel.configureBlocking(false)
            serverSocketChannel.bind(null)
            serverSocketChannel.accept()
            val localAddress = (serverSocketChannel.localAddress as InetSocketAddress)
            val url = "http://${localAddress.hostName}:${localAddress.port}"
            block(url)
        }
    }

    private fun createEngine(url: String): AiInferenceComputeEngine {
        val engine = AiInferenceComputeEngine()
        val blockchainConfig = AiInferenceConfig(
                model = "no-model",
                inferenceTimeoutSeconds = 5L,
                verificationTimeoutSeconds = 5L,
                maxCompletionTokens = 0L,
        )
        val configuration = mock<BlockchainConfiguration> {
            on { rawConfig } doReturn gtv(mapOf(AiInferenceComputeEngine.NAME to GtvObjectMapper.toGtvDictionary(blockchainConfig)))
        }
        val postchainContext = mock<PostchainContext> {
            on { appConfig } doReturn AppConfig.fromEnvironment(mapOf("extension.ai_inference.url" to url))
        }
        val ctx = mock<EContext>()
        engine.initializeContext(configuration, postchainContext, ctx)
        engine.load()
        return engine
    }
}

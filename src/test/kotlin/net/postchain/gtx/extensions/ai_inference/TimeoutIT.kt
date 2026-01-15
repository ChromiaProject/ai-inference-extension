package net.postchain.gtx.extensions.ai_inference

import assertk.assertFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.messageContains
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtx.extensions.ai_inference.rell.lib.ai_inference.Request
import net.postchain.gtx.extensions.ai_inference.rell.lib.ai_inference.Response
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.nio.channels.ServerSocketChannel
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

class TimeoutIT : AiInferenceBaseTest() {
    val unroutableInternetUrl = "http://10.255.255.1:1"

    @Test
    @Timeout(15, unit = TimeUnit.SECONDS)
    fun `connect timeout`() {
        val engine = EnvironmentVariables(AiInferenceNodeConfig.URL, unroutableInternetUrl).execute(Callable {
            createEngine(timeout = 5L)
        })
        assertFailure {
            engine.compute(GtvObjectMapper.toGtvDictionary(Request(prompt = "hello", messages = null, stop = null)))
        }.isInstanceOf(ProgrammerMistake::class).messageContains("504 Client Error: Client Timeout caused by Connect")
    }

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `request timeout compute`() {
        withRequestTimeoutServer { url ->
            val engine = EnvironmentVariables(AiInferenceNodeConfig.URL, url).execute(Callable {
                createEngine(timeout = 5L)
            })
            assertFailure {
                engine.compute(GtvObjectMapper.toGtvDictionary(Request(prompt = "hello", messages = null, stop = null)))
            }.isInstanceOf(ProgrammerMistake::class).messageContains("504 Client Error: Client Timeout caused by Read timed out")
        }
    }

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `request timeout validate`() {
        withRequestTimeoutServer { url ->
            val engine = EnvironmentVariables(AiInferenceNodeConfig.URL, url).execute(Callable {
                createEngine(timeout = 5L)
            })
            assertFailure {
                engine.validate(
                        GtvObjectMapper.toGtvDictionary(Request(prompt = "hello", messages = null, stop = null)),
                        GtvObjectMapper.toGtvDictionary(Response(listOf(), listOf(), "hello")),
                )
            }.isInstanceOf(ProgrammerMistake::class).messageContains("504 Client Error: Client Timeout caused by Read timed out")
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
}

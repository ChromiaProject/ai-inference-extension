package net.postchain.gtx.extensions.ai_inference

import org.http4k.core.HttpHandler
import org.http4k.core.MemoryResponse
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import java.io.Closeable
import javax.net.ssl.SSLException
import kotlin.time.Duration

class AiServiceRequestStrategy(val endpoint: String, val retryCount: Int, val retryDelay: Duration, val httpClient: HttpHandler, val closeable: Closeable)
    : RequestStrategy {
    override fun <R> request(createRequest: (String) -> Request,
                             success: (Response, String) -> R,
                             failure: (Response, String) -> R): R {
        val request = createRequest(endpoint)
        var response: Response? = null
        repeat(retryCount) {
            response = makeRequest(request)
            when {
                isSuccess(response.status) -> return success(response, endpoint)

                isClientFailure(response.status) -> return failure(response, endpoint)

                // else retry
            }
            Thread.sleep(retryDelay.inWholeMilliseconds)
        }
        return failure(response!!, endpoint)
    }

    fun makeRequest(request: Request) = try {
        httpClient(request)
    } catch (_: SSLException) {
        MemoryResponse(Status.CONNECTION_REFUSED)
    }

    override fun close() {
        closeable.close()
    }
}

fun isSuccess(status: Status) = status.successful

fun isClientFailure(status: Status) = status == Status.BAD_REQUEST || status == Status.NOT_FOUND || status == Status.UNAUTHORIZED

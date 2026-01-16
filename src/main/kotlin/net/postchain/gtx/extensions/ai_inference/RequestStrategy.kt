package net.postchain.gtx.extensions.ai_inference

import org.http4k.core.Request
import org.http4k.core.Response

interface RequestStrategy {
    fun <R> request(createRequest: (String) -> Request,
                    success: (Response, String) -> R,
                    failure: (Response, String) -> R): R
}

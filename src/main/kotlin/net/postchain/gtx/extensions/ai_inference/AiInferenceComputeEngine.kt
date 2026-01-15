package net.postchain.gtx.extensions.ai_inference

import mu.KLogging
import net.postchain.PostchainContext
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.exception.UserMistake
import net.postchain.config.app.AppConfig
import net.postchain.core.BlockchainConfiguration
import net.postchain.core.EContext
import net.postchain.gtv.Gtv
import net.postchain.gtv.mapper.DefaultValue
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtv.mapper.Name
import net.postchain.gtv.mapper.toObject
import net.postchain.gtx.PostchainContextAware
import net.postchain.gtx.extensions.ai_inference.rell.lib.ai_inference.Request
import net.postchain.gtx.extensions.ai_inference.rell.lib.ai_inference.Response
import net.postchain.hybridcompute.HybridComputeEngine
import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.cookie.StandardCookieSpec
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.core5.util.Timeout
import org.http4k.client.ApacheClient
import org.http4k.core.Credentials
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.then
import org.http4k.core.with
import org.http4k.filter.ClientFilters
import org.http4k.filter.GzipCompressionMode
import org.http4k.lens.basicAuthentication
import org.http4k.core.Request as HttpRequest

data class AiInferenceNodeConfig(
        val url: String,
        val basicAuth: Credentials? = null,
) {
    companion object {
        const val CONFIG_ENV_PREFIX = "POSTCHAIN_EXTENSION_AI_INFERENCE_"
        const val URL = "${CONFIG_ENV_PREFIX}URL"
        const val BASIC_AUTH_USER = "${CONFIG_ENV_PREFIX}BASIC_AUTH_USER"
        const val BASIC_AUTH_PASSWORD = "${CONFIG_ENV_PREFIX}BASIC_AUTH_PASSWORD"

        @JvmStatic
        fun fromAppConfig(config: AppConfig): AiInferenceNodeConfig {
            val basicAuthUser = config.getEnvOrString(BASIC_AUTH_USER, "extension.ai_inference.basic_auth_user")
            val basicAuthPassword = config.getEnvOrString(BASIC_AUTH_PASSWORD, "extension.ai_inference.basic_auth_password")
            if (basicAuthUser != null && basicAuthPassword == null) {
                throw UserMistake("If $BASIC_AUTH_USER is set, $BASIC_AUTH_PASSWORD must be set as well")
            }
            if (basicAuthUser == null && basicAuthPassword != null) {
                throw UserMistake("If $BASIC_AUTH_PASSWORD is set, $BASIC_AUTH_USER must be set as well")
            }
            return AiInferenceNodeConfig(
                    url = config.getEnvOrString(URL, "extension.ai_inference.url")
                            ?: throw UserMistake("AI inference URL must be configured"),
                    basicAuth = if (basicAuthUser != null && basicAuthPassword != null)
                        Credentials(basicAuthUser, basicAuthPassword)
                    else null,
            )
        }
    }
}

data class AiInferenceConfig(
        @param:Name("model")
        val model: String,

        @param:Name("inference_timeout_seconds")
        @param:DefaultValue(defaultLong = AiInferenceComputeEngine.DEFAULT_INFERENCE_TIMEOUT_SECONDS.toLong())
        val inferenceTimeoutSeconds: Long,

        @param:Name("verification_timeout_seconds")
        @param:DefaultValue(defaultLong = AiInferenceComputeEngine.DEFAULT_VERIFICATION_TIMEOUT_SECONDS.toLong())
        val verificationTimeoutSeconds: Long,

        @param:Name("max_completion_tokens")
        val maxCompletionTokens: Long,
)

class AiInferenceComputeEngine : HybridComputeEngine, PostchainContextAware {
    companion object : KLogging() {
        const val NAME = "ai_inference"
        const val CONNECT_TIMEOUT_SECONDS = 10
        const val DEFAULT_INFERENCE_TIMEOUT_SECONDS = 60
        const val DEFAULT_VERIFICATION_TIMEOUT_SECONDS = 10
        const val BASE_REQUEST_COST = 1000L
    }

    override val name = NAME

    internal lateinit var nodeConfig: AiInferenceNodeConfig
    internal lateinit var config: AiInferenceConfig
    internal lateinit var inferenceClient: HttpHandler
    internal lateinit var verificationClient: HttpHandler

    override fun initializeContext(configuration: BlockchainConfiguration, postchainContext: PostchainContext, ctx: EContext) {
        nodeConfig = AiInferenceNodeConfig.fromAppConfig(postchainContext.appConfig)
        config = configuration.rawConfig.asDict()[NAME]?.toObject<AiInferenceConfig>()
                ?: throw UserMistake("$NAME configuration not found")
        if (config.model.isBlank()) {
            throw UserMistake("$NAME configuration invalid: no model specified")
        }
        if (config.inferenceTimeoutSeconds < 1 || config.inferenceTimeoutSeconds > Integer.MAX_VALUE) {
            throw UserMistake("$NAME configuration invalid: inference_timeout_seconds must be between 1 and ${Integer.MAX_VALUE}")
        }
        if (config.verificationTimeoutSeconds < 1 || config.verificationTimeoutSeconds > Integer.MAX_VALUE) {
            throw UserMistake("$NAME configuration invalid: verification_timeout_seconds must be between 1 and ${Integer.MAX_VALUE}")
        }
        inferenceClient = createClientWithTimeout(Timeout.ofSeconds(config.inferenceTimeoutSeconds))
        verificationClient = createClientWithTimeout(Timeout.ofSeconds(config.verificationTimeoutSeconds))
    }

    private fun createClientWithTimeout(timeout: Timeout): HttpHandler = ClientFilters.AcceptGZip(GzipCompressionMode.Streaming())
            .then(
                    ClientFilters.RequestTracing(
                            startReportFn = { request, _ ->
                                logger.debug { "\n$request" }
                            },
                            endReportFn = { _, response, _ ->
                                logger.debug { "\n$response" }
                            }
                    ).then(
                            ApacheClient(HttpClients.custom()
                                    .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                                            .setDefaultConnectionConfig(ConnectionConfig.custom()
                                                    .setConnectTimeout(Timeout.ofSeconds(CONNECT_TIMEOUT_SECONDS.toLong()))
                                                    .build())
                                            .build())
                                    .setDefaultRequestConfig(
                                            RequestConfig.custom()
                                                    .setRedirectsEnabled(false)
                                                    .setCookieSpec(StandardCookieSpec.IGNORE)
                                                    .setResponseTimeout(timeout)
                                                    .build())
                                    .build())
                    ))

    override fun load() {
        // nothing to do here
    }

    override fun estimatePoints(input: Gtv): Long {
        val request = input.toObject<Request>()
        return if (!request.prompt.isNullOrEmpty() && request.messages.isNullOrEmpty()) {
            estimateText(request.prompt)
        } else if (request.prompt.isNullOrEmpty() && !request.messages.isNullOrEmpty()) {
            estimateChat(request.messages.map { ChatMessage(role = it.role, content = it.message) })
        } else {
            throw UserMistake("Invalid request: either prompt or messages must be set, but not both.")
        }
    }

    override fun compute(input: Gtv): Pair<Gtv, Long> {
        val request = input.toObject<Request>()
        if (!request.prompt.isNullOrEmpty() && request.messages.isNullOrEmpty()) {
            val (response, cost) = generateText(request.prompt, request.stop)
            verifyTextGeneration(response.promptTokens, response.textTokens)
            return GtvObjectMapper.toGtvDictionary(response) to cost
        } else if (request.prompt.isNullOrEmpty() && !request.messages.isNullOrEmpty()) {
            if (request.stop != null) {
                throw UserMistake("Invalid request: stop is not supported for chat inference")
            }
            val (response, cost) = generateChat(request.messages.map { ChatMessage(role = it.role, content = it.message) }, null)
            verifyTextGeneration(response.promptTokens, response.textTokens)
            return GtvObjectMapper.toGtvDictionary(response) to cost
        } else {
            throw UserMistake("Invalid request: either prompt or messages must be set, but not both")
        }
    }

    fun estimateText(prompt: String): Long = BASE_REQUEST_COST +
            prompt.length +
            config.maxCompletionTokens * 2

    fun generateText(prompt: String, stopSequence: String?): Pair<Response, Long> {
        val httpResponse = inferenceClient(HttpRequest(Method.POST, "${nodeConfig.url}/v1/completions/verified")
                .with(verifiedCompletionRequest of VerifiedCompletionRequest(
                        model = config.model,
                        prompt = prompt,
                        max_tokens = config.maxCompletionTokens,
                        stop = stopSequence,
                )).let { if (nodeConfig.basicAuth != null) it.basicAuthentication(nodeConfig.basicAuth!!) else it })
        if (!httpResponse.status.successful) {
            handleError(httpResponse, "generate text")
        }
        val response = verifiedCompletionResponse(httpResponse)
        val choice = response.choices.firstOrNull() ?: throw UserMistake("No choices found in response")
        if (response.choices.size > 1) {
            logger.warn("More than one (${response.choices.size}) choice found in response. Using first one.")
        }
        logger.info("Generated id ${response.id} at ${response.created} with model ${response.model} with finish reason ${choice.finish_reason}")
        return Response(
                promptTokens = choice.prompt_token_ids,
                textTokens = choice.completion_token_ids,
                text = choice.text,
        ) to BASE_REQUEST_COST + response.usage.prompt_tokens + response.usage.completion_tokens * 2
    }

    fun estimateChat(messages: List<ChatMessage>): Long = BASE_REQUEST_COST +
            messages.sumOf { it.role.length + it.content.length } +
            config.maxCompletionTokens * 2

    fun generateChat(messages: List<ChatMessage>, stopSequence: String?): Pair<Response, Long> {
        val httpResponse = inferenceClient(HttpRequest(Method.POST, "${nodeConfig.url}/v1/chat/completions/verified")
                .with(verifiedChatCompletionRequest of VerifiedChatCompletionRequest(
                        model = config.model,
                        messages = messages,
                        max_completion_tokens = config.maxCompletionTokens,
                        stop = stopSequence,
                )).let { if (nodeConfig.basicAuth != null) it.basicAuthentication(nodeConfig.basicAuth!!) else it })
        if (!httpResponse.status.successful) {
            handleError(httpResponse, "generate chat")
        }
        val response = verifiedChatCompletionResponse(httpResponse)
        val choice = response.choices.firstOrNull() ?: throw UserMistake("No choices found in chat response")
        if (response.choices.size > 1) {
            logger.warn("More than one (${response.choices.size}) choice found in chat response. Using first one.")
        }
        logger.info("Generated chat id ${response.id} at ${response.created} with model ${response.model} with finish reason ${choice.finish_reason}")
        return Response(
                promptTokens = choice.prompt_token_ids,
                textTokens = choice.completion_token_ids,
                text = choice.message.content,
        ) to BASE_REQUEST_COST + response.usage.prompt_tokens + response.usage.completion_tokens * 2
    }

    override fun validate(input: Gtv, output: Gtv) {
        val response = output.toObject<Response>()
        verifyTextGeneration(response.promptTokens, response.textTokens)
    }

    fun verifyTextGeneration(promptTokens: List<Long>, textTokens: List<Long>) {
        val httpResponse = verificationClient(HttpRequest(Method.POST, "${nodeConfig.url}/v1/verify_decoding")
                .with(verifyDecodingRequest of VerifyDecodingRequest(
                        model = config.model,
                        prompt = promptTokens,
                        completion = textTokens,
                        prompt_logprobs = 0,
                        check_greedy = true,
                        greedy_logprob_threshold = 0.001,
                )).let { if (nodeConfig.basicAuth != null) it.basicAuthentication(nodeConfig.basicAuth!!) else it })
        if (!httpResponse.status.successful) {
            handleError(httpResponse, "verify")
        }
        val response = verifyDecodingResponse(httpResponse)
        logger.info("Verified id ${response.id} at ${response.created} with model ${response.model} is_verified_greedy=${response.is_verified_greedy}")
        if (!response.is_verified_greedy) {
            throw UserMistake("Generated text does not match")
        }
    }

    private fun handleError(httpResponse: org.http4k.core.Response, what: String): Nothing {
        val response = try {
            errorResponse(httpResponse)
        } catch (_: Exception) {
            throw ProgrammerMistake("Failed to $what: ${httpResponse.status} ${httpResponse.bodyString()}")
        }
        if (response.error != null) {
            if (httpResponse.status == org.http4k.core.Status.BAD_REQUEST || httpResponse.status == org.http4k.core.Status.NOT_FOUND) {
                throw UserMistake(response.error.message)
            } else {
                throw ProgrammerMistake("Failed to $what: ${response.error.message}")
            }
        } else if (response.detail != null) {
            throw ProgrammerMistake("Failed to $what: ${response.detail}")
        } else {
            throw ProgrammerMistake("Failed to $what: ${httpResponse.status} ${httpResponse.bodyString()}")
        }
    }
}

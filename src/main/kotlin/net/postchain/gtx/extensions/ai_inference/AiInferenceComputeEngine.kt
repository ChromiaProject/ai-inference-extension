package net.postchain.gtx.extensions.ai_inference

import mu.KLogging
import net.postchain.PostchainContext
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.exception.UserMistake
import net.postchain.common.types.WrappedByteArray
import net.postchain.common.wrap
import net.postchain.config.app.AppConfig
import net.postchain.core.BlockchainConfiguration
import net.postchain.core.EContext
import net.postchain.core.Shutdownable
import net.postchain.crypto.sha256Digest
import net.postchain.gtv.Gtv
import net.postchain.gtv.mapper.DefaultValue
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtv.mapper.Name
import net.postchain.gtv.mapper.toObject
import net.postchain.gtv.merkle.GtvMerkleHashCalculatorV2
import net.postchain.gtv.merkleHash
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
import java.io.Closeable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.http4k.core.Request as HttpRequest

data class AiInferenceNodeConfig(
        val url: String,
        val retryCount: Int,
        val retryDelay: Duration,
        val basicAuth: Credentials? = null,
) {
    companion object {
        const val CONFIG_ENV_PREFIX = "POSTCHAIN_EXTENSION_AI_INFERENCE_"
        const val CONFIG_PROPERTY_PREFIX = "extension.ai_inference."
        const val URL = "URL"
        const val RETRY_COUNT = "RETRY_COUNT"
        const val RETRY_DELAY_MILLIS = "RETRY_DELAY_MILLIS"
        const val BASIC_AUTH_USER = "BASIC_AUTH_USER"
        const val BASIC_AUTH_PASSWORD = "BASIC_AUTH_PASSWORD"

        @JvmStatic
        fun fromAppConfig(config: AppConfig, bcModel: String): AiInferenceNodeConfig {
            val (envPrefix, propertyPrefix) = getConfigPrefixes(config, bcModel)

            val basicAuthUser = config.getEnvOrString(envPrefix + BASIC_AUTH_USER, propertyPrefix + BASIC_AUTH_USER.lowercase())
            val basicAuthPassword = config.getEnvOrString(envPrefix + BASIC_AUTH_PASSWORD, propertyPrefix + BASIC_AUTH_PASSWORD.lowercase())
            if (basicAuthUser != null && basicAuthPassword == null) {
                throw UserMistake("If $BASIC_AUTH_USER is set, $BASIC_AUTH_PASSWORD must be set as well")
            }
            if (basicAuthUser == null && basicAuthPassword != null) {
                throw UserMistake("If $BASIC_AUTH_PASSWORD is set, $BASIC_AUTH_USER must be set as well")
            }
            return AiInferenceNodeConfig(
                    url = config.getEnvOrString(envPrefix + URL, propertyPrefix + URL.lowercase())
                            ?: throw UserMistake("AI inference URL must be configured"),
                    retryCount = config.getEnvOrInt(envPrefix + RETRY_COUNT, propertyPrefix + RETRY_COUNT.lowercase(), 5),
                    retryDelay = config.getEnvOrLong(envPrefix + RETRY_DELAY_MILLIS, propertyPrefix + RETRY_DELAY_MILLIS.lowercase(), 1000).milliseconds,
                    basicAuth = if (basicAuthUser != null && basicAuthPassword != null)
                        Credentials(basicAuthUser, basicAuthPassword)
                    else null,
            )
        }

        private fun getConfigPrefixes(config: AppConfig, bcModel: String): Pair<String, String> {
            val modelSpecificPropertyPrefix = "${CONFIG_PROPERTY_PREFIX}${bcModel}."
            val modelSpecificEnvPrefix = "${CONFIG_ENV_PREFIX}${bcModel.uppercase()}_"

            // Check if there is any model-specific config, otherwise fallback to the default config
            return if (config.getEnvOrString(modelSpecificEnvPrefix + URL, modelSpecificPropertyPrefix + URL.lowercase()) != null) {
                modelSpecificEnvPrefix to modelSpecificPropertyPrefix
            } else {
                CONFIG_ENV_PREFIX to CONFIG_PROPERTY_PREFIX
            }
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

class AiInferenceComputeEngine : HybridComputeEngine, PostchainContextAware, Shutdownable {
    companion object : KLogging() {
        const val NAME = "ai_inference"
        const val CONNECT_TIMEOUT_SECONDS = 10
        const val DEFAULT_INFERENCE_TIMEOUT_SECONDS = 60
        const val DEFAULT_VERIFICATION_TIMEOUT_SECONDS = 10
        const val BASE_REQUEST_COST = 1000L
        const val VERIFICATION_CACHE_SIZE = 100
    }

    override val name = NAME

    internal lateinit var nodeConfig: AiInferenceNodeConfig
    internal lateinit var config: AiInferenceConfig
    internal lateinit var inferenceRequestStrategy: RequestStrategy
    internal lateinit var verificationRequestStrategy: RequestStrategy

    internal val merkleHashCalculator = GtvMerkleHashCalculatorV2(::sha256Digest)
    internal val verificationFailureCache: MutableMap<WrappedByteArray, Boolean> = object : LinkedHashMap<WrappedByteArray, Boolean>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<WrappedByteArray, Boolean>?): Boolean {
            return size > VERIFICATION_CACHE_SIZE
        }
    }

    override fun initializeContext(configuration: BlockchainConfiguration, postchainContext: PostchainContext, ctx: EContext) {
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
        nodeConfig = AiInferenceNodeConfig.fromAppConfig(postchainContext.appConfig, config.model)

        // Configurable retires for inference
        val (inferenceClient, inferenceCloseable) = createClientWithTimeout(Timeout.ofSeconds(config.inferenceTimeoutSeconds))
        inferenceRequestStrategy = AiServiceRequestStrategy(nodeConfig.url,
                retryCount = nodeConfig.retryCount, retryDelay = nodeConfig.retryDelay, inferenceClient, inferenceCloseable)

        // No retries for verification
        val (verificationClient, verificationCloseable) = createClientWithTimeout(Timeout.ofSeconds(config.verificationTimeoutSeconds))
        verificationRequestStrategy = AiServiceRequestStrategy(nodeConfig.url,
                retryCount = 1, retryDelay = Duration.ZERO, verificationClient, verificationCloseable)
    }

    private fun createClientWithTimeout(timeout: Timeout): Pair<HttpHandler, Closeable> {
        val closeableHttpClient = HttpClients.custom()
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
                .build()
        return ClientFilters.AcceptGZip(GzipCompressionMode.Streaming())
                .then(
                        ClientFilters.RequestTracing(
                                startReportFn = { request, _ ->
                                    logger.debug { "\n$request" }
                                },
                                endReportFn = { _, response, _ ->
                                    logger.debug { "\n$response" }
                                }
                        ).then(
                                ApacheClient(closeableHttpClient)
                        )) to closeableHttpClient
    }

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
            val output = GtvObjectMapper.toGtvDictionary(response)
            verifyTextGeneration(output)
            return output to cost
        } else if (request.prompt.isNullOrEmpty() && !request.messages.isNullOrEmpty()) {
            if (request.stop != null) {
                throw UserMistake("Invalid request: stop is not supported for chat inference")
            }
            val (response, cost) = generateChat(request.messages.map { ChatMessage(role = it.role, content = it.message) }, null)
            val output = GtvObjectMapper.toGtvDictionary(response)
            verifyTextGeneration(output)
            return output to cost
        } else {
            throw UserMistake("Invalid request: either prompt or messages must be set, but not both")
        }
    }

    fun estimateText(prompt: String): Long = BASE_REQUEST_COST +
            prompt.length +
            config.maxCompletionTokens * 2

    fun generateText(prompt: String, stopSequence: String?): Pair<Response, Long> = inferenceRequestStrategy.request({ endpoint ->
        HttpRequest(Method.POST, "${endpoint}/v1/completions/verified")
                .with(verifiedCompletionRequest of VerifiedCompletionRequest(
                        model = config.model,
                        prompt = prompt,
                        max_tokens = config.maxCompletionTokens,
                        stop = stopSequence,
                )).let { if (nodeConfig.basicAuth != null) it.basicAuthentication(nodeConfig.basicAuth!!) else it }
    }, { httpResponse, _ ->
        val response = verifiedCompletionResponse(httpResponse)
        val choice = response.choices.firstOrNull() ?: throw UserMistake("No choices found in response")
        if (response.choices.size > 1) {
            logger.warn("More than one (${response.choices.size}) choice found in response. Using first one.")
        }
        logger.info("Generated id ${response.id} at ${response.created} with model ${response.model} with finish reason ${choice.finish_reason}")
        Response(
                promptTokens = choice.prompt_token_ids,
                textTokens = choice.completion_token_ids,
                text = choice.text,
        ) to BASE_REQUEST_COST + response.usage.prompt_tokens + response.usage.completion_tokens * 2
    }, { httpResponse, _ ->
        handleError(httpResponse, "generate text")
    })

    fun estimateChat(messages: List<ChatMessage>): Long = BASE_REQUEST_COST +
            messages.sumOf { it.role.length + it.content.length } +
            config.maxCompletionTokens * 2

    fun generateChat(messages: List<ChatMessage>, stopSequence: String?): Pair<Response, Long> = inferenceRequestStrategy.request({ endpoint ->
        HttpRequest(Method.POST, "${endpoint}/v1/chat/completions/verified")
                .with(verifiedChatCompletionRequest of VerifiedChatCompletionRequest(
                        model = config.model,
                        messages = messages,
                        max_completion_tokens = config.maxCompletionTokens,
                        stop = stopSequence,
                )).let { if (nodeConfig.basicAuth != null) it.basicAuthentication(nodeConfig.basicAuth!!) else it }
    }, { httpResponse, _ ->
        val response = verifiedChatCompletionResponse(httpResponse)
        val choice = response.choices.firstOrNull() ?: throw UserMistake("No choices found in chat response")
        if (response.choices.size > 1) {
            logger.warn("More than one (${response.choices.size}) choice found in chat response. Using first one.")
        }
        logger.info("Generated chat id ${response.id} at ${response.created} with model ${response.model} with finish reason ${choice.finish_reason}")
        Response(
                promptTokens = choice.prompt_token_ids,
                textTokens = choice.completion_token_ids,
                text = choice.message.content,
        ) to BASE_REQUEST_COST + response.usage.prompt_tokens + response.usage.completion_tokens * 2

    }, { httpResponse, _ ->
        handleError(httpResponse, "generate chat")
    })

    override fun validate(input: Gtv, output: Gtv) {
        verifyTextGeneration(output)
    }

    fun verifyTextGeneration(output: Gtv) {
        val cacheKey = output.merkleHash(merkleHashCalculator).wrap()
        if (synchronized(verificationFailureCache) {
                    verificationFailureCache.containsKey(cacheKey)
                }) {
            logger.info("Returning cached verification failure")
            verificationFailure()
        }
        val response = output.toObject<Response>()
        val isVerified = verifyTextGeneration(response.promptTokens, response.textTokens)
        if (!isVerified) {
            synchronized(verificationFailureCache) {
                verificationFailureCache[cacheKey] = true
            }
            verificationFailure()
        }
    }

    private fun verificationFailure(): Nothing {
        throw UserMistake("Generated text does not match")
    }

    fun verifyTextGeneration(promptTokens: List<Long>, textTokens: List<Long>): Boolean = verificationRequestStrategy.request({ endpoint ->
        HttpRequest(Method.POST, "${endpoint}/v1/verify_decoding")
                .with(verifyDecodingRequest of VerifyDecodingRequest(
                        model = config.model,
                        prompt = promptTokens,
                        completion = textTokens,
                        prompt_logprobs = 0,
                        check_greedy = true,
                        greedy_logprob_threshold = 0.001,
                )).let { if (nodeConfig.basicAuth != null) it.basicAuthentication(nodeConfig.basicAuth!!) else it }
    }, { httpResponse, _ ->
        val response = verifyDecodingResponse(httpResponse)
        logger.info("Verified id ${response.id} at ${response.created} with model ${response.model} is_verified_greedy=${response.is_verified_greedy}")
        response.is_verified_greedy
    }, { httpResponse, _ ->
        handleError(httpResponse, "verify")
    })

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

    override fun shutdown() {
        inferenceRequestStrategy.close()
        verificationRequestStrategy.close()
    }
}

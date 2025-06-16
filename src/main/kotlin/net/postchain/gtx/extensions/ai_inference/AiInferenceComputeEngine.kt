package net.postchain.gtx.extensions.ai_inference

import mu.KLogging
import net.postchain.PostchainContext
import net.postchain.common.BlockchainRid
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.exception.UserMistake
import net.postchain.config.app.AppConfig
import net.postchain.core.BlockchainConfiguration
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
        private const val CONFIG_ENV_PREFIX = "POSTCHAIN_EXTENSION_AI_INFERENCE_"
        private const val URL = "${CONFIG_ENV_PREFIX}URL"
        private const val BASIC_AUTH_USER = "${CONFIG_ENV_PREFIX}BASIC_AUTH_USER"
        private const val BASIC_AUTH_PASSWORD = "${CONFIG_ENV_PREFIX}BASIC_AUTH_PASSWORD"

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
        @Name("model")
        val model: String,

        @Name("timeout_seconds")
        @DefaultValue(defaultLong = AiInferenceComputeEngine.DEFAULT_TIMEOUT_SECONDS.toLong())
        val timeoutSeconds: Long,

        @Name("max_completion_tokens")
        val maxCompletionTokens: Long,
)

class AiInferenceComputeEngine : HybridComputeEngine, PostchainContextAware {
    companion object : KLogging() {
        const val NAME = "ai_inference"
        const val CONNECT_TIMEOUT_SECONDS = 10
        const val DEFAULT_TIMEOUT_SECONDS = 60
    }

    override val name = NAME

    lateinit var nodeConfig: AiInferenceNodeConfig
    lateinit var config: AiInferenceConfig
    lateinit var client: HttpHandler

    override fun initializeContext(configuration: BlockchainConfiguration, postchainContext: PostchainContext) {
        nodeConfig = AiInferenceNodeConfig.fromAppConfig(postchainContext.appConfig)
    }

    override fun init(blockchainConfig: Gtv, blockchainRID: BlockchainRid) {
        config = blockchainConfig.asDict()[NAME]?.toObject<AiInferenceConfig>()
                ?: throw UserMistake("$NAME configuration not found")
        if (config.model.isBlank()) {
            throw UserMistake("$NAME configuration invalid: no model specified")
        }
        if (config.timeoutSeconds < 1 || config.timeoutSeconds > Integer.MAX_VALUE) {
            throw UserMistake("$NAME configuration invalid: timeout_seconds must be between 1 and ${Integer.MAX_VALUE}")
        }
        client = ClientFilters.AcceptGZip(GzipCompressionMode.Streaming())
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
                                                        .setResponseTimeout(Timeout.ofSeconds(config.timeoutSeconds))
                                                        .build())
                                        .build())
                        ))
    }

    override fun load() {
        // nothing to do here
    }

    override fun compute(input: Gtv): Gtv {
        val request = input.toObject<Request>()
        if (!request.prompt.isNullOrEmpty() && request.messages.isNullOrEmpty()) {
            val response = generateText(request.prompt)
            return GtvObjectMapper.toGtvDictionary(response)
        } else if (request.prompt.isNullOrEmpty() && !request.messages.isNullOrEmpty()) {
            val response = generateChat(request.messages.map { ChatMessage(role = it.role, content = it.message) })
            return GtvObjectMapper.toGtvDictionary(response)
        } else {
            throw UserMistake("Invalid request: either prompt or messages must be set, but not both.")
        }
    }

    fun generateText(prompt: String): Response {
        val httpResponse = client(HttpRequest(Method.POST, "${nodeConfig.url}/v1/completions/verified")
                .with(verifiedCompletionRequest of VerifiedCompletionRequest(
                        model = config.model,
                        prompt = prompt,
                        max_completion_tokens = config.maxCompletionTokens,
                )).let { if (nodeConfig.basicAuth != null) it.basicAuthentication(nodeConfig.basicAuth!!) else it })
        if (!httpResponse.status.successful) {
            throw ProgrammerMistake("Failed to generate text: ${httpResponse.status} ${httpResponse.bodyString()}")
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
        )
    }

    fun generateChat(messages: List<ChatMessage>): Response {
        val httpResponse = client(HttpRequest(Method.POST, "${nodeConfig.url}/v1/chat/completions/verified")
                .with(verifiedChatCompletionRequest of VerifiedChatCompletionRequest(
                        model = config.model,
                        messages = messages,
                        max_completion_tokens = config.maxCompletionTokens,
                )).let { if (nodeConfig.basicAuth != null) it.basicAuthentication(nodeConfig.basicAuth!!) else it })
        if (!httpResponse.status.successful) {
            throw ProgrammerMistake("Failed to generate chat: ${httpResponse.status} ${httpResponse.bodyString()}")
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
        )
    }

    override fun validate(output: Gtv) {
        val response = output.toObject<Response>()
        verifyTextGeneration(response.promptTokens, response.textTokens)
    }

    fun verifyTextGeneration(promptTokens: List<Long>, textTokens: List<Long>) {
        val httpResponse = client(HttpRequest(Method.POST, "${nodeConfig.url}/v1/verify_decoding")
                .with(verifyDecodingRequest of VerifyDecodingRequest(
                        model = config.model,
                        prompt = promptTokens,
                        completion = textTokens,
                        prompt_logprobs = 0,
                        check_greedy = true,
                        greedy_logprob_threshold = 0.001,
                )).let { if (nodeConfig.basicAuth != null) it.basicAuthentication(nodeConfig.basicAuth!!) else it })
        if (!httpResponse.status.successful) {
            throw ProgrammerMistake("Failed to verify text: ${httpResponse.status} ${httpResponse.bodyString()}")
        }
        val response = verifyDecodingResponse(httpResponse)
        logger.info("Verified id ${response.id} at ${response.created} with model ${response.model} is_verified_greedy=${response.is_verified_greedy}")
        if (!response.is_verified_greedy) {
            throw UserMistake("Generated text does not match")
        }
    }

    override fun shutdown() {
        // nothing to do here
    }
}

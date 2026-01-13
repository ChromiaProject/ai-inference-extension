package net.postchain.gtx.extensions.ai_inference

import net.postchain.api.rest.json.GtvJsonFactory.auto
import org.http4k.core.Body

val verifiedCompletionRequest = Body.auto<VerifiedCompletionRequest>().toLens()
val verifiedCompletionResponse = Body.auto<VerifiedCompletionResponse>().toLens()
val verifiedChatCompletionRequest = Body.auto<VerifiedChatCompletionRequest>().toLens()
val verifiedChatCompletionResponse = Body.auto<VerifiedChatCompletionResponse>().toLens()
val verifyDecodingRequest = Body.auto<VerifyDecodingRequest>().toLens()
val verifyDecodingResponse = Body.auto<VerifyDecodingResponse>().toLens()

data class VerifiedCompletionRequest(
        /**
         * The model to use for the completion.
         */
        val model: String,

        /**
         * The input prompt.
         */
        val prompt: String,

        /**
         * The maximum number of tokens that can be generated in the completion.
         */
        val max_tokens: Long? = null,

        /**
         * A sequence where it will stop generating further tokens. The returned text will not contain the stop sequence,
         * or `null` to generate exactly `max_tokens` tokens.
         */
        val stop: String? = null,
)

data class VerifiedCompletionResponse(
        /**
         * A unique identifier for the completion response (e.g., `cmpl-verified-xxx`).
         */
        val id: String,

        /**
         * The Unix timestamp (in seconds) of when the completion was created.
         */
        val created: Long,

        /**
         * The model used for the completion.
         */
        val model: String,

        val choices: List<VerifiedCompletionResponseChoice>,

        /**
         * Represents token usage details.
         */
        val usage: UsageInfo,
)

data class VerifiedCompletionResponseChoice(
        /**
         * Choice index.
         */
        val index: Int,

        /**
         * The generated completion text.
         */
        val text: String,

        /**
         * Token IDs for the input prompt.
         */
        val prompt_token_ids: List<Long>,

        /**
         * Token IDs for the generated completion text.
         */
        val completion_token_ids: List<Long>,

        /**
         * Detailed logprob information for each token in `completion_token_ids`.
         */
        // val completion_token_details: List<VerifiedTokenDetail>,

        /**
         * Detailed logprob information for each token in `prompt_token_ids`. Included if requested.
         */
        // val prompt_token_details: List<VerifiedTokenDetail>?,

        /**
         * Reason why the generation finished (e.g., `"length"`, `"stop"`).
         */
        val finish_reason: String?,
)

data class VerifiedChatCompletionRequest(
        /**
         * The model to use for the completion.
         */
        val model: String,

        /**
         * A list of messages comprising the conversation so far.
         */
        val messages: List<ChatMessage>,

        /**
         * An upper bound for the number of tokens that can be generated for a completion,
         * including visible output tokens and reasoning tokens.
         */
        val max_completion_tokens: Long? = null,

        /**
         * A sequence where it will stop generating further tokens. The returned text will not contain the stop sequence,
         * or `null` to stop automatically.
         */
        val stop: String? = null,
)

data class VerifiedChatCompletionResponse(
        /**
         * A unique identifier for the chat completion response (e.g., `chatcmpl-verified-xxx`).
         */
        val id: String,

        /**
         * The Unix timestamp (in seconds) of when the chat completion was created.
         */
        val created: Long,

        /**
         * The model used for the chat completion.
         */
        val model: String,

        val choices: List<VerifiedChatCompletionResponseChoice>,

        /**
         * Represents token usage details.
         */
        val usage: UsageInfo,
)

data class VerifiedChatCompletionResponseChoice(
        /**
         * Choice index.
         */
        val index: Int,

        /**
         * The generated completion text.
         */
        val message: ChatMessage,

        /**
         * Token IDs for the input prompt.
         */
        val prompt_token_ids: List<Long>,

        /**
         * Token IDs for the generated completion text.
         */
        val completion_token_ids: List<Long>,

        /**
         * Detailed logprob information for each token in `completion_token_ids`.
         */
        // val completion_token_details: List<VerifiedTokenDetail>,

        /**
         * Detailed logprob information for each token in `prompt_token_ids`. Included if requested.
         */
        // val prompt_token_details: List<VerifiedTokenDetail>?,

        /**
         * Reason why the generation finished (e.g., `"length"`, `"stop"`).
         */
        val finish_reason: String?,
)

data class ChatMessage(
        /**
         * The role of the message author.
         */
        val role: String,

        /**
         * The contents of the message.
         */
        val content: String,
)

/**
 * Represents token usage details.
 */
data class UsageInfo(
        val prompt_tokens: Long,

        val completion_tokens: Long,

        val total_tokens: Long,
)

data class VerifyDecodingRequest(
        /**
         * The model to use for verification.
         */
        val model: String,

        /**
         * The input prompt tokens.
         */
        val prompt: List<Long>,

        /**
         * The completion tokens to verify against the prompt.
         */
        val completion: List<Long>,

        /**
         * The number of logprobs to return for the prompt tokens.
         * Defaults to `0` (no prompt token details).
         */
        val prompt_logprobs: Int,

        /**
         * If `true`, the verification checks if each token in the completion was the greedy choice.
         * If `false`, it simply returns logprob information without strict greedy checking.
         */
        val check_greedy: Boolean,

        /**
         * A small tolerance for comparing log probabilities when `check_greedy` is `true`.
         * The actual token's logprob must be `>= (top_logprob - greedy_logprob_threshold)`
         * to be considered greedy if it's not the absolute top choice
         * (handles potential floating point inaccuracies or identically scored top tokens).
         */
        val greedy_logprob_threshold: Double,
)

data class VerifyDecodingResponse(
        /**
         * A unique identifier for the verification response (e.g., `vd-xxx`).
         */
        val id: String,

        /**
         * The Unix timestamp (in seconds) of when the completion was created of when the response was created.
         */
        val created: Long,

        /**
         * The model used for the verification.
         */
        val model: String,

        /**
         * `true` if all tokens in the provided `completion` were determined to be greedy choices according
         *  to the model and `greedy_logprob_threshold`. `false` otherwise.
         */
        val is_verified_greedy: Boolean,

        /**
         * Token IDs for the input prompt.
         */
        val prompt_token_ids: List<Long>?,

        /**
         * Token IDs for the input completion.
         */
        val completion_token_ids: List<Long>,
)

package net.postchain.gtx.extensions.ai_inference

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.djl.inference.Predictor
import ai.djl.modality.nlp.generate.CausalLMOutput
import ai.djl.modality.nlp.generate.SearchConfig
import ai.djl.ndarray.NDArray
import ai.djl.ndarray.NDList
import ai.djl.ndarray.index.NDIndex
import ai.djl.ndarray.types.DataType
import ai.djl.ndarray.types.Shape
import mu.KLogging

/**
 * Supports only "greedy" search.
 */
class TextGeneratorVerifier(val predictor: Predictor<NDList, CausalLMOutput>,
                            val config: SearchConfig,
                            val tokenizer: HuggingFaceTokenizer) {

    companion object : KLogging()

    /**
     * Verifies the generated text by comparing model predictions with the actual tokens,
     * excluding the prompt tokens from verification.
     *
     * @param inputIds The NDArray containing the token IDs to verify
     * @param promptLength Length of the prompt text that was used to generate the text
     * @return `null` if the model predictions match the input tokens (excluding prompt tokens),
     *      an error message if not
     */
    fun verify(inputIds: NDArray, promptLength: Int): String? {
        // Prepare the attention mask and compute the positionOffset.
        val (attentionMask, positionOffset) = prepareAttentionMaskAndPositionOffset(inputIds, config)
        val modelInput = prepareInput(inputIds, attentionMask, positionOffset, pastSeqLength = 0, repeat = 1)
        // Perform a single forward pass.
        val modelOutput = predictor.predict(modelInput)
        // Get predicted token IDs by taking argMax over the vocabulary dimension.
        val predicted = modelOutput.logits.argMax(2)

        // Get the original input and predicted tokens as arrays
        val inputTokens = inputIds.toLongArray()
        val predictedTokens = predicted.toLongArray()

        logger.debug { "Input tokens: ${inputTokens.contentToString()}" }
        logger.debug { "Predicted tokens: ${predictedTokens.contentToString()}" }

        logger.debug { "Input text: <${tokenizer.decode(inputTokens)}>" }
        logger.debug { "Predicted text: <${tokenizer.decode(predictedTokens)}>" }

        // If the input is shorter than the prompt (shouldn't happen normally), return false
        if (inputTokens.size <= promptLength) {
            return "Input is shorter than or equal to prompt length, verification failed"
        }

        // Verify text length is correct (check for EOS token or max sequence length)
        val hasCorrectLength = verifyTextLength(predictedTokens, promptLength)
        if (!hasCorrectLength) {
            return "Text length verification failed"
        }

        if (inputTokens.size != predictedTokens.size) {
            // note: this cannot happen if model is correct
            return "Input and predicted tokens sizes do not match"
        }

        // For a sequence [A, B, C], the model predicts [B, C, X] 
        // So we need to compare input[i] with predicted[i-1]
        // We start from the prompt length to skip the prompt tokens

        for (i in maxOf(promptLength, 1) until predictedTokens.size) {
            if (predictedTokens[i - 1] != inputTokens[i]) {
                return "Mismatch at position $i: predicted=${predictedTokens[i - 1]}, expected=${inputTokens[i]}"
            }
        }

        logger.debug { "All predicted tokens match the expected next tokens" }
        return null
    }

    /**
     * Verifies that the predicted text length is correct based on termination criteria.
     *
     * @param predictedTokens The array of predicted token IDs (including prompt if present)
     * @param promptLength The number of prompt tokens to skip
     * @return True if the predicted text length is correct
     */
    private fun verifyTextLength(predictedTokens: LongArray, promptLength: Int): Boolean {
        val eosTokenId = config.eosTokenId
        val maxSequenceLength = config.maxSeqLength

        if (eosTokenId != -1L) {
            for (i in promptLength until predictedTokens.size) {
                if (predictedTokens[i] == eosTokenId) {
                    logger.debug { "EOS token found at position $i" }
                    // Verify that generation stopped exactly at the EOS token
                    return i == predictedTokens.size - 1
                }
            }
        }

        val expectedLength = minOf(promptLength + maxSequenceLength, predictedTokens.size)
        val isMaxLengthReached = predictedTokens.size == expectedLength

        logger.debug {
            "Max sequence length: $maxSequenceLength, Expected length: $expectedLength, Actual length: ${predictedTokens.size}"
        }
        return isMaxLengthReached
    }

    /**
     * Prepare attentionMask and positionOffset.
     *
     * Used to initialize the search.
     */
    private fun prepareAttentionMaskAndPositionOffset(inputIds: NDArray, config: SearchConfig): Pair<NDArray, NDArray> {
        val suffixPadding = config.isSuffixPadding
        val manager = inputIds.manager
        val numBatch = Math.toIntExact(inputIds.shape[0])
        val initSeqSize = Math.toIntExact(inputIds.shape[1])
        val attentionMask =
                manager.ones(Shape(1, inputIds.shape.lastDimension), DataType.INT64)
                        .reshape(1, -1)
                        .repeat(0, numBatch.toLong())

        // Linear search from left to find the first position that's not padTokenId.
        val offset = Array(numBatch) { LongArray(1) }
        for (i in 0 until numBatch) {
            val aSequence = inputIds["{},:", i].toLongArray()
            var idx = 0
            while (idx < initSeqSize) {
                if (suffixPadding && aSequence[idx] == config.padTokenId
                        || !suffixPadding && aSequence[idx] != config.padTokenId) {
                    break
                }
                idx++
            }
            attentionMask[NDIndex(
                    "{},{}:{}",
                    i,
                    if (suffixPadding) idx else 0,
                    if (suffixPadding) initSeqSize else idx)] = 0
            if (!suffixPadding) {
                offset[i][0] = idx.toLong()
            }
        }
        val positionOffset = manager.create(offset)
        return attentionMask to positionOffset
    }

    private fun prepareInput(
            inputIds: NDArray,
            attentionMask: NDArray,
            positionOffset: NDArray,
            pastSeqLength: Long,
            repeat: Int
    ): NDList {
        // Pack the model input
        var positionIds =
                inputIds.manager
                        .arange(
                                pastSeqLength.toFloat(),
                                (pastSeqLength + inputIds.shape.lastDimension).toFloat(),
                                1f,
                                DataType.INT64)
                        .expandDims(0)
                        .repeat(0, inputIds.shape[0])

        val positionIdsShifted = positionIds.subi(positionOffset.repeat(0, repeat.toLong()))
        positionIds = positionIdsShifted.maximum(positionIdsShifted.zerosLike())

        return NDList(inputIds, positionIds, attentionMask)
    }
}

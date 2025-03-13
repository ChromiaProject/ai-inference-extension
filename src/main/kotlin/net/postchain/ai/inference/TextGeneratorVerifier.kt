package net.postchain.ai.inference

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
 * `TextGenerator` is an LMSearch (language model search) which contains multiple
 * autoregressive search methods.
 *
 * It has a Predictor from NDList to CausalLMOutput, which is called inside an autoregressive
 * inference loop.
 */
class TextGeneratorVerifier(val predictor: Predictor<NDList, CausalLMOutput>?,
                            val searchName: String?,
                            val config: SearchConfig?,
                            val tokenizer: HuggingFaceTokenizer) {
    
    companion object: KLogging()
    
    /**
     * Returns the value of the positionOffset.
     *
     * @return the value of positionOffset
     */
    var positionOffset: NDArray? = null
        private set

    /**
     * Verifies the generated text by comparing model predictions with the actual tokens,
     * excluding the prompt tokens from verification.
     *
     * @param inputIds The NDArray containing the token IDs to verify
     * @param prompt The prompt text that was used to generate the text
     * @return True if the model predictions match the input tokens (excluding prompt tokens)
     */
    fun verify(inputIds: NDArray, prompt: String): Boolean {
        // Prepare the attention mask and compute the positionOffset.
        val attentionMask = prepareAttentionMaskOffset(inputIds, config)
        val pastSeqLength = 0L
        val modelInput = prepareInput(inputIds, attentionMask, pastSeqLength, 1)

        // Perform a single forward pass.
        val modelOutput = predictor!!.predict(modelInput)
        // Get predicted token IDs by taking argMax over the vocabulary dimension.
        val predicted = modelOutput.logits.argMax(2)

        // Get the original input and predicted tokens as arrays
        val inputTokens = inputIds.toLongArray()
        val predictedTokens = predicted.toLongArray()

        logger.info("Input tokens: ${inputTokens.contentToString()}")
        logger.info("Predicted tokens: ${predictedTokens.contentToString()}")

        logger.info("Input text: ${tokenizer.decode(inputTokens)}")
        logger.info("Predicted text: ${tokenizer.decode(predictedTokens)}")

        // Get prompt tokens
        val promptTokens = tokenizer.encode(prompt).getIds()
        val promptLength = promptTokens.size

        // If the input is shorter than the prompt (shouldn't happen normally), return false
        if (inputTokens.size <= promptLength) {
            logger.info("Input is shorter than or equal to prompt length, verification failed")
            return false
        }

        // Verify text length is correct (check for EOS token or max sequence length)
        val hasCorrectLength = verifyTextLength(predictedTokens, promptLength)
        if (!hasCorrectLength) {
            logger.info("Text length verification failed")
            return false
        }

        if (inputTokens.size != predictedTokens.size) {
            // note: this cannot happen if model is correct
            logger.info("Input and predicted tokens sizes do not match")
            return false
        }

        // For a sequence [A, B, C], the model predicts [B, C, EOS]
        // So we need to compare input[i] with predicted[i-1]
        // We start from the prompt length to skip the prompt tokens

        // Check if there are enough tokens to compare
        if (inputTokens.size == promptLength) {
            logger.info("Not enough tokens to compare after prompt")
            return true // No generated tokens to verify
        }

        for (i in maxOf(promptLength, 1) until predictedTokens.size) {
            if (predictedTokens[i - 1] != inputTokens[i]) {
                logger.info("Mismatch at position $i: predicted=${predictedTokens[i - 1]}, expected=${inputTokens[i]}")
                return false
            }
        }

        logger.info("All predicted tokens match the expected next tokens")
        return true
    }

    /**
     * Verifies that the predicted text length is correct based on termination criteria.
     *
     * @param predictedTokens The array of predicted token IDs (including prompt if present)
     * @param promptLength The number of prompt tokens to skip
     * @return True if the predicted text length is correct
     */
    private fun verifyTextLength(predictedTokens: LongArray, promptLength: Int): Boolean {
        val eosTokenId = config?.eosTokenId ?: -1L
        val maxSequenceLength = config?.maxSeqLength ?: 60

        if (eosTokenId != -1L) {
            for (i in promptLength until predictedTokens.size) {
                if (predictedTokens[i] == eosTokenId) {
                    logger.info("EOS token found at position $i")
                    // Verify that generation stopped exactly at the EOS token
                    return i == predictedTokens.size - 1
                }
            }
        }

        val expectedLength = minOf(promptLength + maxSequenceLength, predictedTokens.size)
        val isMaxLengthReached = predictedTokens.size == expectedLength

        logger.info("Max sequence length: $maxSequenceLength, "
                + "Expected length: $expectedLength, "
                + "Actual length: ${predictedTokens.size}")
        return isMaxLengthReached
    }

    private fun prepareAttentionMaskOffset(inputIds: NDArray, config: SearchConfig?): NDArray {
        // prepare attentionMask and positionOffset
        // Used to initialize the search
        val suffixPadding = config!!.isSuffixPadding
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
        positionOffset = manager.create(offset)
        return attentionMask
    }

    private fun prepareInput(
            inputIds: NDArray, attentionMask: NDArray, pastSeqLength: Long, repeat: Int): NDList {
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

        val positionIdsShifted = positionIds.subi(positionOffset!!.repeat(0, repeat.toLong()))
        positionIds = positionIdsShifted.maximum(positionIdsShifted.zerosLike())

        return NDList(inputIds, positionIds, attentionMask)
    }
}

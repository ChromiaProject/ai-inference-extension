package net.postchain.ai.inference

import ai.djl.huggingface.tokenizers.Encoding
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.djl.inference.Predictor
import ai.djl.modality.nlp.generate.CausalLMOutput
import ai.djl.modality.nlp.generate.SearchConfig
import ai.djl.modality.nlp.generate.TextGenerator
import ai.djl.ndarray.NDArray
import ai.djl.ndarray.NDList
import ai.djl.ndarray.NDManager
import ai.djl.repository.zoo.Criteria
import ai.djl.repository.zoo.ZooModel
import ai.djl.translate.DeferredTranslatorFactory
import mu.KLogging
import net.postchain.ai.inference.rell.lib.ai_inference.Request
import net.postchain.ai.inference.rell.lib.ai_inference.Response
import net.postchain.common.exception.UserMistake
import net.postchain.gtv.GtvDecoder
import net.postchain.gtv.GtvEncoder
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtv.mapper.toObject
import net.postchain.hybridcompute.HybridComputeEngine
import kotlin.time.measureTimedValue

class AIInferenceComputeEngine : HybridComputeEngine {
    companion object : KLogging() {
        const val NAME = "ai_inference"
    }

    override val name = NAME

    val model: ZooModel<NDList, CausalLMOutput>
    val predictor: Predictor<NDList, CausalLMOutput>
    val manager: NDManager
    val tokenizer: HuggingFaceTokenizer
    val config = SearchConfig()

    init {
        config.maxSeqLength = 60
        val url = "https://djl-misc.s3.amazonaws.com/test/models/gpt2/gpt2_pt.zip"
        val criteria: Criteria<NDList, CausalLMOutput> = Criteria.builder()
                .setTypes(NDList::class.java, CausalLMOutput::class.java)
                .optModelUrls(url)
                .optEngine("PyTorch")
                .optTranslatorFactory(DeferredTranslatorFactory())
                .build()

        model = criteria.loadModel()
        predictor = model.newPredictor()
        manager = model.ndManager.newSubManager()
        tokenizer = HuggingFaceTokenizer.newInstance("gpt2")
    }


    override fun compute(input: ByteArray): ByteArray {
        val request = GtvDecoder.decodeGtv(input).toObject<Request>()
        logger.info("Generating text for prompt <${request.prompt}>")
        val (generatedText, duration) = measureTimedValue { generateText(request.prompt) }
        logger.info("Generated in $duration <$generatedText>")
        return GtvEncoder.encodeGtv(GtvObjectMapper.toGtvDictionary(Response(request.prompt, generatedText)))
    }

    /**
     * Generates a text string using PyTorch with greedy search.
     */
    fun generateText(input: String): String {
        val generator = TextGenerator(predictor, "greedy", config)
        val encoding: Encoding = tokenizer.encode(input)
        val inputIds: LongArray = encoding.ids
        val inputIdArray: NDArray = manager.create(inputIds).expandDims(0)
        val output: NDArray = generator.generate(inputIdArray)
        return tokenizer.decode(output.toLongArray())
    }

    override fun validate(output: ByteArray) {
        val response = GtvDecoder.decodeGtv(output).toObject<Response>()
        logger.info("Verifying generated text <${response.generated}> for prompt <${response.prompt}>")
        val (isValid, duration) = measureTimedValue { verifyTextGeneration(response.generated, response.prompt) }
        logger.info("Verified in $duration $isValid")
        if (!isValid) {
            throw UserMistake("Generated text does not match the prompt")
        }
    }

    /**
     * Verifies a generated text by re-encoding it into token IDs and verifying via the verifier,
     * excluding the prompt tokens from verification.
     *
     * @param generatedText The complete generated text (including prompt)
     * @param prompt The prompt text that was used to generate the text
     * @return True if the model predictions match the generated tokens (excluding prompt tokens)
     */
    fun verifyTextGeneration(generatedText: String, prompt: String): Boolean {
        val encoding: Encoding = tokenizer.encode(generatedText)
        val outputIds: LongArray = encoding.ids
        val outputIdArray: NDArray = manager.create(outputIds).expandDims(0)
        val verifier = TextGeneratorVerifier(predictor, "greedy", config, tokenizer)
        return verifier.verify(outputIdArray, prompt)
    }

    override fun shutdown() {
        tokenizer.close()
        manager.close()
        predictor.close()
        model.close()
    }
}

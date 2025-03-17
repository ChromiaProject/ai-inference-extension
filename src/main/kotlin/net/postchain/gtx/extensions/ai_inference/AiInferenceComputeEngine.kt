package net.postchain.gtx.extensions.ai_inference

import ai.djl.Device
import ai.djl.huggingface.tokenizers.Encoding
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.djl.inference.Predictor
import ai.djl.modality.nlp.generate.CausalLMOutput
import ai.djl.modality.nlp.generate.SearchConfig
import ai.djl.modality.nlp.generate.TextGenerator
import ai.djl.ndarray.NDList
import ai.djl.repository.zoo.Criteria
import ai.djl.repository.zoo.ZooModel
import ai.djl.translate.DeferredTranslatorFactory
import mu.KLogging
import net.postchain.common.BlockchainRid
import net.postchain.common.exception.UserMistake
import net.postchain.gtv.Gtv
import net.postchain.gtv.mapper.DefaultValue
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtv.mapper.Name
import net.postchain.gtv.mapper.toObject
import net.postchain.gtx.extensions.ai_inference.rell.lib.ai_inference.Request
import net.postchain.gtx.extensions.ai_inference.rell.lib.ai_inference.Response
import net.postchain.hybridcompute.HybridComputeEngine
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

data class AiInferenceConfig(
        @Name("model_url")
        val modelUrl: String,

        @Name("tokenizer_name")
        val tokenizerName: String,

        @Name("max_sequence_length")
        @DefaultValue(defaultLong = AiInferenceComputeEngine.DEFAULT_SEQUENCE_LENGTH.toLong())
        val maxSequenceLength: Long,

        @Name("max_length")
        @DefaultValue(defaultLong = AiInferenceComputeEngine.MAX_LENGTH.toLong())
        val maxLength: Long,
)

class AiInferenceComputeEngine : HybridComputeEngine {
    companion object : KLogging() {
        const val NAME = "ai_inference"
        const val DEFAULT_SEQUENCE_LENGTH = 60
        const val MAX_LENGTH = 512
    }

    override val name = NAME

    lateinit var model: ZooModel<NDList, CausalLMOutput>
    lateinit var predictor: Predictor<NDList, CausalLMOutput>
    lateinit var tokenizer: HuggingFaceTokenizer
    lateinit var searchConfig: SearchConfig

    override fun init(blockchainConfig: Gtv, blockchainRID: BlockchainRid) {
        val config = blockchainConfig.asDict()[NAME]?.toObject<AiInferenceConfig>()
                ?: throw UserMistake("$NAME configuration not found")
        if (config.modelUrl.isBlank()) {
            throw UserMistake("$NAME configuration invalid: no model_url specified")
        }
        if (config.tokenizerName.isBlank()) {
            throw UserMistake("$NAME configuration invalid: no tokenizer_name specified")
        }
        if (config.maxSequenceLength < 1 || config.maxSequenceLength > Integer.MAX_VALUE) {
            throw UserMistake("$NAME configuration invalid: max_sequence_length must be between 1 and ${Integer.MAX_VALUE}")
        }
        if (config.maxLength < 1 || config.maxLength > MAX_LENGTH) {
            throw UserMistake("$NAME configuration invalid: max_length must be between 1 and $MAX_LENGTH")
        }

        logger.info("Initializing engine...")
        try {
            val duration = measureTime {
                System.setProperty("ai.djl.offline", "true")

                val criteria: Criteria<NDList, CausalLMOutput> = Criteria.builder()
                        .setTypes(NDList::class.java, CausalLMOutput::class.java)
                        .optModelUrls(config.modelUrl)
                        .optEngine("PyTorch")
                        .optDevice(Device.cpu())
                        .optTranslatorFactory(DeferredTranslatorFactory())
                        .build()

                model = criteria.loadModel()
                predictor = model.newPredictor()
                tokenizer = HuggingFaceTokenizer.builder()
                        .optTokenizerName(config.tokenizerName)
                        .optMaxLength(config.maxLength.toInt())
                        .build()
                searchConfig = SearchConfig()
                searchConfig.maxSeqLength = config.maxSequenceLength.toInt()
            }
            logger.info("Initialized engine in $duration")
        } catch (e: Exception) {
            logger.error("Failed to initialize engine: $e", e)
            throw UserMistake("Failed to initialize engine")
        }
    }

    override fun compute(input: Gtv): Gtv {
        val request = input.toObject<Request>()
        logger.info("Generating text...")
        val (generatedText, duration) = measureTimedValue { generateText(request.prompt) }
        logger.info("Generated in $duration")
        return GtvObjectMapper.toGtvDictionary(Response(request.prompt, generatedText))
    }

    /**
     * Generates a text string using PyTorch with greedy search.
     */
    fun generateText(input: String): String {
        val generator = TextGenerator(predictor, "greedy", searchConfig)
        val encoding: Encoding = tokenizer.encode(input)
        val inputIds: LongArray = encoding.ids
        return model.ndManager.newSubManager().use { manager ->
            val inputIdArray = manager.create(inputIds).expandDims(0)
            val output = generator.generate(inputIdArray)
            tokenizer.decode(output.toLongArray())
        }
    }

    override fun validate(output: Gtv) {
        val response = output.toObject<Response>()
        logger.info("Verifying generated text...")
        val (error, duration) = measureTimedValue { verifyTextGeneration(response.generated, response.prompt) }
        logger.info("Verified in $duration $error")
        if (error != null) {
            throw UserMistake(error)
        }
    }

    /**
     * Verifies a generated text by re-encoding it into token IDs and verifying via the verifier,
     * excluding the prompt tokens from verification.
     *
     * @param generatedText The complete generated text (including prompt)
     * @param prompt The prompt text that was used to generate the text
     * @return `null` if the model predictions match the input tokens (excluding prompt tokens),
     *      an error message if not
     */
    fun verifyTextGeneration(generatedText: String, prompt: String): String? {
        val verifier = TextGeneratorVerifier(predictor, searchConfig, tokenizer)
        val encoding: Encoding = tokenizer.encode(generatedText)
        val outputIds: LongArray = encoding.ids
        return model.ndManager.newSubManager().use { manager ->
            val outputIdArray = manager.create(outputIds).expandDims(0)
            verifier.verify(outputIdArray, prompt)
        }
    }

    override fun shutdown() {
        logger.info("Shutting down...")
        val duration = measureTime {
            if (::tokenizer.isInitialized) tokenizer.close()
            if (::predictor.isInitialized) predictor.close()
            if (::model.isInitialized) model.close()
        }
        logger.info("Shutdown in $duration")
    }
}

package net.postchain.gtx.extensions.ai_inference

import mu.KLogging
import net.postchain.common.BlockchainRid
import net.postchain.common.exception.UserMistake
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtv.mapper.toObject
import net.postchain.gtx.extensions.ai_inference.rell.lib.ai_inference.Request
import net.postchain.gtx.extensions.ai_inference.rell.lib.ai_inference.Response
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class AiInferenceTest {
    companion object : KLogging() {
        lateinit var engine: AiInferenceComputeEngine

        @BeforeAll
        @JvmStatic
        fun setup() {
            engine = AiInferenceComputeEngine()
            val testConfig = AiInferenceConfig(
                    modelUrl = "https://djl-misc.s3.amazonaws.com/test/models/gpt2/gpt2_pt.zip",
                    tokenizerName = "gpt2",
                    maxSequenceLength = AiInferenceComputeEngine.DEFAULT_SEQUENCE_LENGTH.toLong(),
                    maxLength = AiInferenceComputeEngine.MAX_LENGTH.toLong()
            )
            engine.init(
                    gtv(mapOf(AiInferenceComputeEngine.NAME to GtvObjectMapper.toGtvDictionary(testConfig))),
                    BlockchainRid.ZERO_RID
            )

            engine.offline = false
            engine.load()
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            engine.shutdown()
        }
    }

    @Test
    @Disabled
    fun inference() {
        val prompt = "How is the weather in Stockholm?"
        val input = GtvObjectMapper.toGtvDictionary(Request(prompt))
        val output = engine.compute(input)
        val response = output.toObject<Response>()
        println(response.text)
    }

    @ParameterizedTest
    @ValueSource(strings = ["Hello, how are you?", "How is the weather in Stockholm?", "What is Kotlin used for?"])
    fun `inference and validation`(prompt: String) {
        val input = GtvObjectMapper.toGtvDictionary(Request(prompt))
        val output = engine.compute(input)
        engine.validate(output)
    }

    @Test
    fun `negative validation`() {
        try {
            val invalidOutput = GtvObjectMapper.toGtvDictionary(Response(
                    promptLength = 6,
                    tokens = listOf(15496, 11, 703, 389, 345, 30, 198, 198, 40, 1101, 257, 1310, 1643, 286,
                            257, 34712, 13, 314, 1101, 257, 1263, 34712, 13, 314, 1101, 257, 1263, 34712, 13, 314,
                            1101, 257, 1263, 34712, 13, 314, 1101, 257, 1263, 34712, 13, 314, 1101, 257, 1263, 34712,
                            13, 314, 1101, 257, 1263, 34712, 13, 314, 1101, 257, 1263, 34712, 13, 315),
                    text = "", // not used
            ))
            engine.validate(invalidOutput)

            assert(false) { "Expected validation to fail" }
        } catch (e: UserMistake) {
            logger.debug { "Validation failed as expected: ${e.message}" }
        }
    }
}

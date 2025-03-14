package net.postchain.ai.inference

import assertk.assertThat
import assertk.assertions.isEqualTo
import net.postchain.ai.inference.rell.lib.ai_inference.Request
import net.postchain.ai.inference.rell.lib.ai_inference.Response
import net.postchain.common.BlockchainRid
import net.postchain.common.exception.UserMistake
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtv.mapper.toObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class AiInferenceIT {
    @Test
    fun `inference and validation`() {
        val engine = AiInferenceComputeEngine()
        val testConfig = AiInferenceConfig(
                modelUrl = "https://djl-misc.s3.amazonaws.com/test/models/gpt2/gpt2_pt.zip",
                tokenizerName = "gpt2",
                maxSequenceLength = AiInferenceComputeEngine.DEFAULT_SEQUENCE_LENGTH.toLong(),
                maxLength = AiInferenceComputeEngine.MAX_MAX_LENGTH.toLong()
        )
        engine.init(
                gtv(mapOf(AiInferenceComputeEngine.NAME to GtvObjectMapper.toGtvDictionary(testConfig))),
                BlockchainRid.ZERO_RID
        )

        val prompt = "Hello, how are you?"
        val input = GtvObjectMapper.toGtvDictionary(Request(prompt))
        val output = engine.compute(input)
        val response = output.toObject<Response>()
        assertThat(response.prompt).isEqualTo(prompt)

        assertDoesNotThrow {
            engine.validate(output)
        }

        assertThrows<UserMistake> {
            val invalidOutput = GtvObjectMapper.toGtvDictionary(Response(
                    prompt = prompt,
                    generated = """$prompt
                        
                    Bogus text""".trimMargin()
            ))
            engine.validate(invalidOutput)
        }

        engine.shutdown()
    }
}

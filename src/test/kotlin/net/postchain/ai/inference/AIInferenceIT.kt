package net.postchain.ai.inference

import assertk.assertThat
import assertk.assertions.isEqualTo
import mu.KLogging
import net.postchain.ai.inference.rell.lib.ai_inference.Request
import net.postchain.ai.inference.rell.lib.ai_inference.Response
import net.postchain.common.exception.UserMistake
import net.postchain.gtv.GtvDecoder
import net.postchain.gtv.GtvEncoder
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtv.mapper.toObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import kotlin.time.measureTimedValue

class AIInferenceIT {
    companion object : KLogging()

    @Test
    fun testInference() {
        logger.info("Initializing engine...")
        val (engine, duration) = measureTimedValue { AIInferenceComputeEngine() }
        logger.info("Initialized engine in $duration")
        val prompt = "Hello, how are you?"
        val input = GtvEncoder.encodeGtv(GtvObjectMapper.toGtvDictionary(Request(prompt)))
        val output = engine.compute(input)
        val response = GtvDecoder.decodeGtv(output).toObject<Response>()
        assertThat(response.prompt).isEqualTo(prompt)

        assertDoesNotThrow {
            engine.validate(output)
        }

        assertThrows<UserMistake> {
            val invalidOutput = GtvEncoder.encodeGtv(GtvObjectMapper.toGtvDictionary(Response(
                    prompt = prompt,
                    generated = """$prompt
                        
                    Bogus text""".trimMargin()
            )))
            engine.validate(invalidOutput)
        }
    }
}

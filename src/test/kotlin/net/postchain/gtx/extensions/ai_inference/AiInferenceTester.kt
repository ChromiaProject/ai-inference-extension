package net.postchain.gtx.extensions.ai_inference

import net.postchain.common.BlockchainRid
import net.postchain.common.exception.UserMistake
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtv.mapper.toObject
import net.postchain.gtx.extensions.ai_inference.rell.lib.ai_inference.Request
import net.postchain.gtx.extensions.ai_inference.rell.lib.ai_inference.Response

fun test() {
    val engine = AiInferenceComputeEngine()
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

    engine.load()

    val prompt = "Hello, how are you?"
    val input = GtvObjectMapper.toGtvDictionary(Request(prompt))
    val output = engine.compute(input)
    val response = output.toObject<Response>()
    assert(response.prompt == prompt) { "Expected prompt to be $prompt, but got ${response.prompt}" }

    engine.validate(output)

    try {
        val invalidOutput = GtvObjectMapper.toGtvDictionary(Response(
                prompt = prompt,
                generated = """$prompt
                    
                Bogus text""".trimMargin()
        ))
        engine.validate(invalidOutput)

        assert(false) { "Expected validation to fail" }
    } catch (_: UserMistake) {
        // expected
    }

    engine.shutdown()
}

fun main() {
    test()
    println("Container test succeeded")
}

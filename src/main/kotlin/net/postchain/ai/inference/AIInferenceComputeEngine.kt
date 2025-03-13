package net.postchain.ai.inference

import mu.KLogging
import net.postchain.hybridcompute.HybridComputeEngine

class AIInferenceComputeEngine : HybridComputeEngine {
    companion object : KLogging() {
        const val NAME = "ai_inference"
    }

    override val name = NAME

    override fun compute(input: ByteArray): ByteArray {
        return ByteArray(0)
        // TODO POS-1721
    }

    override fun validate(output: ByteArray) {
        // TODO POS-1721
    }

    override fun shutdown() {
        // TODO POS-1721
    }
}

# AI inference extension user guide

This extension uses the Hybrid Compute framework.

## Custom subnode image

Ensure you pick the AI extension image when leasing your container.

## Blockchain configuration

You will need to enable the Hybrid Compute framework, configure it to use the AI inference engine provided by this 
extension, set appropriate timeout for inference computations and configure the model to use for inference. 

```yaml
config:
  sync_ext:
    - "net.postchain.hybridcompute.HybridComputeSynchronizationInfrastructureExtension"
  gtx:
    modules:
      - "net.postchain.hybridcompute.HybridComputeGTXModule"
  hybridcompute:
    engine: "net.postchain.gtx.extensions.ai_inference.AiInferenceComputeEngine"
    load_timeout_seconds: 600 # Timeout in seconds for initial model loading 
    compute_timeout_seconds: 600 # Timeout in seconds for each inference
  ai_inference:
    model_url: "https://djl-misc.s3.amazonaws.com/test/models/gpt2/gpt2_pt.zip" # Specify model URL here
    tokenizer_name: gpt2 # Specify tokenizer name here
    max_sequence_length: 60 # Max sequence length, see https://javadoc.io/static/ai.djl/api/0.32.0/ai/djl/modality/nlp/generate/SearchConfig.html#setMaxSeqLength(int)
    max_length: 512 # The length to truncate and/or pad sequences to, see https://javadoc.io/static/ai.djl.huggingface/tokenizers/0.32.0/ai/djl/huggingface/tokenizers/HuggingFaceTokenizer.Builder.html#optMaxLength(int)
```

## Rell

Install the Rell libraries:

```yaml
libs:
  hybridcompute:
    registry: https://gitlab.com/chromaway/postchain-chromia
    path: chromia-infrastructure/rell/src/lib/hybridcompute
    tagOrBranch: 3.27.5
    rid: x"FBDB9DF68FF14B6E47DAA1B6FBDC9370AA0106AF57D378D6FA2270E4B2BB69CE"
    insecure: false
  ai_inference:
    registry: https://gitlab.com/chromaway/core/ai-inference-extension 
    path: rell/src/lib/ai_inference
    tagOrBranch: 0.1.7
    rid: x"AA53CAB29510CB6CA0E27018497217A349BB15D68105797416F6485126670CD6"
    insecure: false
```

Import the module:

```rell
import ai: lib.ai_inference;
```

Use these two functions from your Rell code:

```rell
/**
 * Submits an inference request.
 * 
 * @param id A unique identifier for the inference request.
 * @param prompt The prompt to generate text for.
 */
function submit_inference_request(id: text, prompt: text)
```

```rell
struct inference_result {
    /** The result of the inference, or null if an error occurred. */
    result: text?;

    /** An error message if the inference failed, or null if no error occurred. */
    error: text?;

    /** RID of the transaction where the result was reported. */
    tx_rid: byte_array;

    /** Index of the operation where the result was reported. */
    op_index: integer;
}

/**
 * Fetches the result of a previously submitted inference request.
 *
 * @param id The identifier of the inference request for which the result is being fetched.
 * @return The result, or `null` if not ready yet
 */
function fetch_inference_result(id: text): inference_result?
```

Minimal example:

```rell
module;

import ai: lib.ai_inference;

// TODO should have authentication for this operation
operation submit_inference_request(id: text, prompt: text) {
    ai.submit_inference_request(id, prompt);
}

query fetch_inference_result(id: text): ai.inference_result = ai.fetch_inference_result(id);
```

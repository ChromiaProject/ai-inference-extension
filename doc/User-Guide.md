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
    tagOrBranch: 3.27.6
    rid: x"DF9EF3C9333D497F501B2D230CA0B9FD12F5288FA4E1D9F5DDBE916506543887"
    insecure: false
  ai_inference:
    registry: https://gitlab.com/chromaway/core/ai-inference-extension 
    path: rell/src/lib/ai_inference
    tagOrBranch: 0.1.10
    rid: x"F404E166F75E249DF3E40FD89777641032E36150B97820D265FD1BCF42A6C117"
    insecure: false
```

Import the module:

```rell
import ai: lib.ai_inference;
```

Submit a request with this function:

```rell
/**
 * Submits an inference request.
 * 
 * @param id A unique identifier for the inference request.
 * @param prompt The prompt to generate text for.
 */
function submit_inference_request(id: text, prompt: text)
```

Fetch result with this function:

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

Or extend this to be notified:

```rell
/**
 * Called when an inference is finished, successfully or failed.
 *
 * @param id The identifier of the inference request
 * @param inference_result The result
 */
@extendable function on_inference_result(id: text, inference_result)
```

Minimal example:

```rell
module;

import ai: lib.ai_inference;

// TODO should have authentication for this operation
operation submit_inference_request(id: text, prompt: text) {
    ai.submit_inference_request(id, prompt);
}

query fetch_inference_result(id: text): ai.inference_result? = ai.fetch_inference_result(id);
```

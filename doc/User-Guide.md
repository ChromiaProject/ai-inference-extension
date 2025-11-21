# AI inference extension user guide

This extension uses the Hybrid Compute framework.

You can either use the AI extension image when leasing your container or add the JAR extension to your container. 

## Blockchain configuration

You will need to enable the Hybrid Compute framework, configure it to use the AI inference engine provided by this 
extension, set the appropriate timeout for inference computations and configure the model to use for inference. 

```yaml
config:
  sync_ext:
    - "net.postchain.hybridcompute.HybridComputeSynchronizationInfrastructureExtension"
  gtx:
    modules:
      - "net.postchain.hybridcompute.HybridComputeGTXModule"
  hybridcompute:
    engine: "net.postchain.gtx.extensions.ai_inference.AiInferenceComputeEngine"
  ai_inference:
    model: "your-model-name" # TODO Specify model name here
    timeout_seconds: 600 # Timeout in seconds for each inference and validation
    max_completion_tokens: 100 # An upper bound for the number of tokens that can be generated for a completion,
    # including visible output tokens and reasoning tokens.
```

## Rell

Install the Rell libraries:

```yaml
libs:
  hybridcompute:
    registry: https://gitlab.com/chromaway/postchain-chromia
    path: chromia-infrastructure/rell/src/lib/hybridcompute
    tagOrBranch: 3.35.4
    rid: x"E1496FC9A2DC89353A06F5BA79CF94F2B92BAC1B9291F10EF45B739DFC977712"
    insecure: false
  ai_inference:
    registry: https://gitlab.com/chromaway/core/ai-inference-extension
    path: rell/src/lib/ai_inference
    tagOrBranch: ${VERSION}
    rid: ${RID}
    insecure: false
```

Import the module:

```rell
import ai: lib.ai_inference;
```

Submit a simple inference request with this function:

```rell
/**
 * Submits an inference request.
 * 
 * @param id A unique identifier for the inference request.
 * @param prompt The prompt to generate text for.
 */
function submit_inference_request(id: text, prompt: text)
```

Or submit a chat inference request with this function:

```rell
/**
 * Submits an chat inference request.
 *
 * @param id A unique identifier for the inference request.
 * @param messages A list of messages comprising the conversation so far.
 */
function submit_chat_inference_request(id: text, messages: list<chat_message>)
```

Fetch the result with this function:

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

// TODO should have authentication for this operation
operation submit_chat_inference_request(id: text, prompt: text) {
    ai.submit_chat_inference_request(id, [ai.chat_message(role="user", message=prompt)]);
}

query fetch_inference_result(id: text): ai.inference_result? = ai.fetch_inference_result(id);
```

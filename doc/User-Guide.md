# AI inference extension user guide

This extension uses the Hybrid Compute framework.

## Custom subnode image

Ensure you pick the AI extension image when leasing your container.

## Blockchain configuration

You will need to enable the Hybrid Compute framework, configure it to use the AI inference engine provided by this 
extension, set appropriate timeout for inference computations and configure the model to use for inference. 

```yaml
config:
  gtx:
    modules:
      - "net.postchain.hybridcompute.HybridComputeGTXModuleFactory"
  hybridcompute:
    engine: "net.postchain.gtx.extensions.ai_inference.AiInferenceComputeEngine"
    compute_timeout_seconds: 60 # Adjust this as needed
  ai_inference:
    model_url: "https://djl-misc.s3.amazonaws.com/test/models/gpt2/gpt2_pt.zip" # Specify model URL here
    tokenizer_name: gpt2 # Specify tokenizer name here
    max_sequence_length: 60 # Adjust this as needed
    max_length: 512 # Adjust this as needed
```

## Rell

Install the Rell libraries:

```yaml
libs:
  hybridcompute:
    registry: https://gitlab.com/chromaway/postchain-chromia
    path: chromia-infrastructure/rell/src/lib/hybridcompute
    tagOrBranch: 3.27.1
    rid: x"567981E58074A540774653FDBC04D275E4FA03A32331BA7B33DF456236DD9314"
    insecure: false
  ai_inference:
    registry: https://gitlab.com/chromaway/core/ai-inference-extension 
    path: rell/src/lib/ai_inference
    tagOrBranch: 0.1.1
    rid: x"92CDF1CE0AD1B95B6F4CF1AFB457587578E237362F3A2D9E64D7E93D4F57DF1C"
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
/**
 * Fetches the result of a previously submitted inference request.
 *
 * If the result is not yet ready, `(null, null)` will be returned. 
 * 
 * @param id The identifier of the inference request for which the result is being fetched.
 * @return A tuple containing:
 *         - result (optional): The generated text, or null if the result is not ready yet or an error occurred.
 *         - error (optional): An error message if the inference failed, or null if no error occurred.
 */
function fetch_inference_result(id: text): (result: text?, error: text?)
```

Minimal example:

```rell
module;

import ai: lib.ai_inference;

// TODO should have authentication for this operation
operation submit_inference_request(id: text, prompt: text) {
    ai.submit_inference_request(id, prompt);
}

query fetch_inference_result(id: text): (result: text?, error: text?) = ai.fetch_inference_result(id);
```

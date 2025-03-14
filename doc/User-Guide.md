# AI inference extension user guide

This extension uses the Hybrid Compute framework.

## Blockchain configuration

You will need to enable the Hybrid Compute framework, configure it to use the AI inference engine provided by this 
extension, set appropriate timeout for inference computations and configure the model to use for inference. 

```yaml
config:
  gtx:
    modules:
      - "net.postchain.hybridcompute.HybridComputeGTXModuleFactory"
  hybridcompute:
    engine: "net.postchain.ai.inference.AiInferenceComputeEngine"
    compute_timeout_seconds: 60 # Adjust this as needed
  ai_inference:
    model_url: "https://djl-misc.s3.amazonaws.com/test/models/gpt2/gpt2_pt.zip" # Specify model here
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
    path: src/lib/ai_inference
    tagOrBranch: 0.1.0
    rid: x"92CDF1CE0AD1B95B6F4CF1AFB457587578E237362F3A2D9E64D7E93D4F57DF1C"
    insecure: false
```

## Custom subnode image

Ensure you pick the AI extension image when leasing your container.

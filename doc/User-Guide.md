# AI inference extension user guide

This extension uses the Hybrid Compute framework.

## Blockchain configuration

You will need to enable the Hybrid Compute framework, configure it to use the AI inference engine provided by this 
extension, and set appropriate timeout for inference computations.

```yaml
config:
  gtx:
    modules:
      - "net.postchain.hybridcompute.HybridComputeGTXModuleFactory"
  hybridcompute:
    engine: "net.postchain.ai.inference.AIInferenceComputeEngine"
    compute_timeout_seconds: 60 # Adjust this as needed
```

## Rell

Install the Rell libraries:

```yaml
libs:
  hybridcompute:
    registry: https://gitlab.com/chromaway/postchain-chromia
    path: chromia-infrastructure/rell/src/lib/hybridcompute
    tagOrBranch: 3.27.0
    rid: x"73543304BEF8A61992EED0A34A2C49B604F6CDFE883E76EF21CEEB3511084794"
    insecure: false
  ai_inference:
    registry: https://gitlab.com/chromaway/core/ai-inference-extension 
    path: src/lib/ai_inference
    rid: x"02FD8373DF2CD6E9E3F29DE1F723E1FA6F3F5BC3AC2B24B806BB95CFDB3CF1D6"
    insecure: false
```

## Custom subnode image

Ensure you pick the AI extension image when leasing your container.

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

Install the Rell library:

```yaml
libs:
  hybridcompute:
    registry: https://gitlab.com/chromaway/postchain-chromia
    path: chromia-infrastructure/rell/src/lib/hybridcompute
    tagOrBranch: 3.27.0
    rid: x"73543304BEF8A61992EED0A34A2C49B604F6CDFE883E76EF21CEEB3511084794"
    insecure: false
```

## Custom subnode image

Ensure you pick the AI extension image when leasing your container.

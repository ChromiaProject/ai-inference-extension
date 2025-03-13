# AI inference extension

    Extension for making inference with an AI model.

## Registration

```shell
pmc subnode-image add --name ai_inference_extension \
  --url registry.gitlab.com/chromaway/core/ai-inference-extension/chromaway/ai-inference-extension-chromia-subnode \
  --digest sha256:xxxx \
  --image-description "Extension for making inference with an AI model" \
  -gtx net.postchain.hybridcompute.HybridComputeGTXModuleFactory
```

This will generate a proposal which need to be voted on.


[Documentation for node providers](doc/Node-Configuration.md)

[Documentation for dApp developers](doc/User-Guide.md)

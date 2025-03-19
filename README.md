# AI inference extension

Extension for making inference with an AI model.

### Register extension in directory-chain


```shell
pmc subnode-image add --name ai_inference_extension \
  --url registry.gitlab.com/chromaway/core/ai-inference-extension/chromaway/ai-inference-extension-chromia-subnode \
  --digest ${DIGEST} \
  --image-description "Extension for making inference with an AI model" \
  -sync net.postchain.hybridcompute.HybridComputeSynchronizationInfrastructureExtension \ 
  -gtx net.postchain.hybridcompute.HybridComputeGTXModule
```

Replace the `${DIGEST}` with the latest image version found [here](https://gitlab.com/chromaway/core/ai-inference-extension/container_registry/8428137).

This will generate a proposal which need to be voted on.

[Documentation for dApp developers](doc/User-Guide.md)

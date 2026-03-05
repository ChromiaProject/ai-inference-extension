# Registering the extension

The extension can be registered in the directory-chain either as a subnode image or a subnode JAR extension.

## Register subnode image extension in directory-chain

```shell
pmc subnode-image add --name ai_inference_extension \
  --url registry.gitlab.com/chromaway/core/ai-inference-extension/chromaway/ai-inference-extension-chromia-subnode \
  --digest ${DIGEST} \
  --image-description "Extension for making inference with an AI model" \
  -sync net.postchain.hybridcompute.HybridComputeSynchronizationInfrastructureExtension \
  -gtx net.postchain.hybridcompute.HybridComputeGTXModule
```

Replace the `${DIGEST}` with the latest image version
found [here](https://gitlab.com/chromaway/core/ai-inference-extension/container_registry/8428137).

This will generate a proposal that needs to be voted on.

## Register subnode JAR extension in directory-chain

```shell
curl -o ai-inference-extension.jar ${DOWNLOAD_URL}
pmc subnode-jar-extension add --name ai_inference_extension \
  --jar ai-inference-extension.jar \
  --extension-description "Extension for making inference with an AI model" \
  -sync net.postchain.hybridcompute.HybridComputeSynchronizationInfrastructureExtension \
  -gtx net.postchain.hybridcompute.HybridComputeGTXModule
```

Replace the `${DOWNLOAD_URL}` with the latest jar file found [here](https://gitlab.com/chromaway/core/ai-inference-extension/-/packages).

This will generate a proposal that needs to be voted on.

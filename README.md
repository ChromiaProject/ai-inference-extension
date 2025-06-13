# AI inference extension

Extension for making inference with an AI model.

## Register extension in directory-chain

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

## Node configuration

This extension requires the following node configuration:

| Name                                         | Description                  | Type   | Required           | Default |
|----------------------------------------------|------------------------------|--------|--------------------|---------|
| `extension.ai_inference.url`                 | AI inference API endpoint    | string | :white_check_mark: |         |
| `extension.ai_inference.basic_auth_user`     | User for HTTP basic auth     | string |                    | no auth |
| `extension.ai_inference.basic_auth_password` | Password for HTTP basic auth | string |                    | no auth |

## Developer documentation

[Documentation for dApp developers](doc/User-Guide.md)

# AI inference extension

Extension for making inference with an AI model.

## Registering the extension

[Extension registration](doc/Extension-Registration.md)

## Node configuration

[Node configuration](doc/Node-Configuration-Properties.md)

## dApp developer documentation

[Documentation for dApp developers](doc/User-Guide.md)

## Developer documentation

The base for the API this extension calls is [vLLM's OpenAI-Compatible API](https://docs.vllm.ai/en/latest/serving/openai_compatible_server/),
but we use a [derivative version with verification support](https://github.com/killerstorm/vllm/blob/042ab5d27ad0db925eb9f05c59882842f8800b19/docs/verification_endpoints.md).

## Updating the `chromia-subnode` base image

The base image is pinned by both tag and digest in the root `pom.xml` (the `<from><image>` element of the `jib-maven-plugin` configuration). The digest pin ensures reproducible builds; the version tag is kept alongside it for human readability.

To bump the base image (replace `<NEW_VERSION>` with the target tag):

```shell
docker pull registry.gitlab.com/chromaway/postchain-chromia/chromaway/chromia-subnode:<NEW_VERSION>
docker inspect --format='{{index .RepoDigests 0}}' \
  registry.gitlab.com/chromaway/postchain-chromia/chromaway/chromia-subnode:<NEW_VERSION>
```

Copy the resulting `sha256:…` digest and update the `<image>` line in `pom.xml` to:

```
registry.gitlab.com/chromaway/postchain-chromia/chromaway/chromia-subnode:<NEW_VERSION>@<DIGEST>
```

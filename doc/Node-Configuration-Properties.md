This page documents all node configuration properties related to this extension.

### AI inference models

A node can be configured to use multiple models by configuring multiple `<model>` properties. The `<model>` is
the name to be used on the blockchain, and is used for the node to lookup the model API configuration.

A blockchain is then using one of the supported models:

```yaml
ai_inference:
  model: "Qwen/Qwen2.5-1.5B-Instruct"
```

If there is no configuration for the model, the extension will fallback to config properties without any model name
in the prefix as a default.

| Name                                                 | Description                                            | Type     | Required | Default | Environment Variable                                                     |
|------------------------------------------------------|--------------------------------------------------------|----------|----------|---------|--------------------------------------------------------------------------|
| `extension.ai_inference.<model>.basic_auth_user`     | Basic http auth username.                              | String   | No       |         | `POSTCHAIN_EXTENSION_AI_INFERENCE_EMBEDDING_<model>_BASIC_AUTH_USER`     |
| `extension.ai_inference.<model>.basic_auth_password` | Basic http auth password.                              | String   | No       |         | `POSTCHAIN_EXTENSION_AI_INFERENCE_EMBEDDING_<model>_BASIC_AUTH_PASSWORD` |
| `extension.ai_inference.<model>.url`                 | The URL of the AI service API for the model.           | String   | Yes      |         | `POSTCHAIN_EXTENSION_AI_INFERENCE_EMBEDDING_<model>_URL`                 |
| `extension.ai_inference.<model>.retry_count`         | How many times to retry HTTP request if it fails.      | Integer  | No       | 5       | `POSTCHAIN_EXTENSION_AI_INFERENCE_EMBEDDING_<model>_RETRY_COUNT`         |
| `extension.ai_inference.<model>.retry_delay`         | How long to wait between each ettempt in milliseconds. | Integer  | No       | 1000    | `POSTCHAIN_EXTENSION_AI_INFERENCE_EMBEDDING_<model>_RETRY_DELAY`         |

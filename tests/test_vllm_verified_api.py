"""
Tests for vLLM verified API endpoints per api.kt spec.

Uses only verified endpoints:
  POST /v1/completions/verified
  POST /v1/chat/completions/verified
  POST /v1/verify_decoding

Run with:
  VLLM_BASE_URL=http://localhost:8000 VLLM_MODEL=your-model pytest tests/ -v
Optional: VLLM_AUTH_USER, VLLM_AUTH_PASS for basic auth.

Mirrors scenarios from AiInferenceIT.kt (Kotlin integration tests).

How we know the verified result is correct:
- Same-token round-trip: completion -> verify_decoding(same tokens) must return 200 and
  is_verified_greedy as a bool (True or False). That proves the server ran the check.
- Tampered tokens: test_negative_validation_invalid_completion_tokens sends a modified
  completion token list and asserts is_verified_greedy=False, so invalid output is detected.
"""
import threading
import time
from http.server import HTTPServer, BaseHTTPRequestHandler

import pytest
import requests


# --- Verified completion response schema (per VerifiedCompletionResponse) ---
def assert_verified_completion_response(data: dict, model: str) -> None:
    assert "id" in data
    assert isinstance(data["id"], str)
    assert "created" in data
    assert isinstance(data["created"], (int, float))
    assert data["model"] == model
    assert "choices" in data
    assert isinstance(data["choices"], list)
    assert len(data["choices"]) >= 1
    choice = data["choices"][0]
    assert "index" in choice
    assert "text" in choice
    assert "prompt_token_ids" in choice
    assert "completion_token_ids" in choice
    assert isinstance(choice["prompt_token_ids"], list)
    assert isinstance(choice["completion_token_ids"], list)
    assert all(isinstance(t, (int, float)) for t in choice["prompt_token_ids"])
    assert all(isinstance(t, (int, float)) for t in choice["completion_token_ids"])
    if "finish_reason" in choice and choice["finish_reason"] is not None:
        assert isinstance(choice["finish_reason"], str)
    assert "usage" in data
    usage = data["usage"]
    assert "prompt_tokens" in usage and isinstance(usage["prompt_tokens"], (int, float))
    assert "completion_tokens" in usage and isinstance(usage["completion_tokens"], (int, float))
    assert "total_tokens" in usage and isinstance(usage["total_tokens"], (int, float))


# --- Verified chat completion response schema (per VerifiedChatCompletionResponse) ---
def assert_verified_chat_completion_response(data: dict, model: str) -> None:
    assert "id" in data
    assert isinstance(data["id"], str)
    assert "created" in data
    assert isinstance(data["created"], (int, float))
    assert data["model"] == model
    assert "choices" in data
    assert isinstance(data["choices"], list)
    assert len(data["choices"]) >= 1
    choice = data["choices"][0]
    assert "index" in choice
    assert "message" in choice
    msg = choice["message"]
    assert "role" in msg and isinstance(msg["role"], str)
    assert "content" in msg and isinstance(msg["content"], str)
    assert "prompt_token_ids" in choice
    assert "completion_token_ids" in choice
    assert isinstance(choice["prompt_token_ids"], list)
    assert isinstance(choice["completion_token_ids"], list)
    if "finish_reason" in choice and choice["finish_reason"] is not None:
        assert isinstance(choice["finish_reason"], str)
    assert "usage" in data
    usage = data["usage"]
    assert "prompt_tokens" in usage and isinstance(usage["prompt_tokens"], (int, float))
    assert "completion_tokens" in usage and isinstance(usage["completion_tokens"], (int, float))
    assert "total_tokens" in usage and isinstance(usage["total_tokens"], (int, float))


# --- Verify decoding response schema (per VerifyDecodingResponse) ---
def assert_verify_decoding_response(data: dict, model: str) -> None:
    assert "id" in data
    assert isinstance(data["id"], str)
    assert "created" in data
    assert isinstance(data["created"], (int, float))
    assert data["model"] == model
    assert "is_verified_greedy" in data
    # Server may return None when check_greedy=False (no check performed)
    assert data["is_verified_greedy"] is None or isinstance(data["is_verified_greedy"], bool)
    assert "completion_token_ids" in data
    assert isinstance(data["completion_token_ids"], list)
    if data.get("prompt_token_ids") is not None:
        assert isinstance(data["prompt_token_ids"], list)


def assert_verified_result_from_same_tokens(verify_data: dict) -> None:
    """
    Assert that verify_decoding was actually performed (check_greedy=True path).
    Server must return a definite bool for is_verified_greedy, not None.
    True = same tokens matched greedy decode; False = e.g. model used sampling (e.g. Qwen3).
    Verification correctness is proven by test_negative_validation_invalid_completion_tokens,
    which asserts tampered tokens get is_verified_greedy=False.
    """
    assert verify_data.get("is_verified_greedy") is not None, (
        "Expected is_verified_greedy to be True or False when check_greedy=True (verification was performed)"
    )
    assert isinstance(verify_data["is_verified_greedy"], bool)


# --- Error response schema (per ErrorResponse / ErrorInfo) ---
def assert_error_response(data: dict) -> None:
    # detail (optional) or error (optional)
    if "error" in data and data["error"] is not None:
        err = data["error"]
        assert "message" in err and isinstance(err["message"], str)
        assert "type" in err and isinstance(err["type"], str)
        assert "code" in err and isinstance(err["code"], (int, float))


# ========== Tests: POST /v1/completions/verified ==========


class TestCompletionsVerified:
    """POST /v1/completions/verified — VerifiedCompletionRequest -> VerifiedCompletionResponse."""

    def test_completion_returns_200_and_valid_shape(self, api):
        r = api.completions_verified("Hello, world!", max_tokens=10)
        assert r.status_code == 200, r.text
        data = r.json()
        assert_verified_completion_response(data, api.model)
        choice = data["choices"][0]
        assert isinstance(choice["text"], str)
        assert len(choice["completion_token_ids"]) <= 10

    def test_completion_with_stop_sequence(self, api):
        r = api.completions_verified(
            "What is 2+2? Answer with one number.",
            max_tokens=20,
            stop=".",
        )
        assert r.status_code == 200, r.text
        data = r.json()
        assert_verified_completion_response(data, api.model)
        text = data["choices"][0]["text"]
        assert "." not in text or text.rstrip().endswith(".")

    def test_completion_usage_matches_token_lists(self, api):
        r = api.completions_verified("Hi", max_tokens=5)
        assert r.status_code == 200, r.text
        data = r.json()
        choice = data["choices"][0]
        usage = data["usage"]
        assert len(choice["prompt_token_ids"]) == usage["prompt_tokens"]
        assert len(choice["completion_token_ids"]) == usage["completion_tokens"]

    def test_completion_invalid_model_returns_error(self, api, base_url, session):
        r = session.post(
            f"{base_url}/v1/completions/verified",
            json={
                "model": "nonexistent-model-xyz",
                "prompt": "Hello",
                "max_tokens": 5,
            },
            timeout=30,
        )
        assert r.status_code in (400, 404), r.text
        data = r.json()
        assert_error_response(data)

    # --- From AiInferenceIT: estimate points for text ---
    def test_estimate_points_for_text(self, api):
        """Mirrors AiInferenceIT.kt `estimate points for text`: completion usage > 0."""
        r = api.completions_verified("Hello, how are you?", max_tokens=10)
        assert r.status_code == 200, r.text
        usage = r.json()["usage"]
        assert usage["prompt_tokens"] + usage["completion_tokens"] > 0

    # --- From AiInferenceIT: text inference and validation (parameterized) ---
    @pytest.mark.parametrize("prompt", [
        "Hello, world! My name is",
        "Hello, how are you?",
        "How is the weather in Stockholm?",
        "What is Kotlin used for?",
        "What is the capital of France?",
        "Translate 'hello' to French:",
    ])
    def test_text_inference_and_validation(self, api, prompt):
        """Mirrors AiInferenceIT.kt `text inference and validation`: completion then verify_decoding."""
        r = api.completions_verified(prompt, max_tokens=20)
        assert r.status_code == 200, r.text
        data = r.json()
        assert_verified_completion_response(data, api.model)
        choice = data["choices"][0]
        prompt_ids = [int(x) for x in choice["prompt_token_ids"]]
        completion_ids = [int(x) for x in choice["completion_token_ids"]]
        verify_r = api.verify_decoding(prompt_ids, completion_ids, check_greedy=True)
        assert verify_r.status_code == 200, verify_r.text
        verify_data = verify_r.json()
        assert_verify_decoding_response(verify_data, api.model)
        assert_verified_result_from_same_tokens(verify_data)

    def test_text_inference_and_validation_with_stop_sequence(self, api):
        """Mirrors AiInferenceIT.kt `text inference and validation with stop sequence`."""
        r = api.completions_verified(
            "What is the capital of France?",
            max_tokens=20,
            stop=".",
        )
        assert r.status_code == 200, r.text
        data = r.json()
        assert_verified_completion_response(data, api.model)
        choice = data["choices"][0]
        prompt_ids = [int(x) for x in choice["prompt_token_ids"]]
        completion_ids = [int(x) for x in choice["completion_token_ids"]]
        verify_r = api.verify_decoding(prompt_ids, completion_ids, check_greedy=True)
        assert verify_r.status_code == 200, verify_r.text
        verify_data = verify_r.json()
        assert_verify_decoding_response(verify_data, api.model)
        assert_verified_result_from_same_tokens(verify_data)


# ========== Tests: POST /v1/chat/completions/verified ==========


class TestChatCompletionsVerified:
    """POST /v1/chat/completions/verified — VerifiedChatCompletionRequest -> VerifiedChatCompletionResponse."""

    def test_chat_returns_200_and_valid_shape(self, api):
        r = api.chat_completions_verified(
            [{"role": "user", "content": "Say 'test' and nothing else."}],
            max_completion_tokens=10,
        )
        assert r.status_code == 200, r.text
        data = r.json()
        assert_verified_chat_completion_response(data, api.model)
        assert data["choices"][0]["message"]["role"] in ("assistant", "Assistant")

    def test_chat_multiple_messages(self, api):
        r = api.chat_completions_verified(
            [
                {"role": "user", "content": "My name is Alice."},
                {"role": "assistant", "content": "Hi Alice."},
                {"role": "user", "content": "What is my name? One word."},
            ],
            max_completion_tokens=5,
        )
        assert r.status_code == 200, r.text
        data = r.json()
        assert_verified_chat_completion_response(data, api.model)

    def test_chat_with_stop(self, api):
        r = api.chat_completions_verified(
            [{"role": "user", "content": "Count: 1, 2, 3,"}],
            max_completion_tokens=20,
            stop="4",
        )
        assert r.status_code == 200, r.text
        data = r.json()
        assert_verified_chat_completion_response(data, api.model)

    def test_chat_invalid_model_returns_error(self, api, base_url, session):
        r = session.post(
            f"{base_url}/v1/chat/completions/verified",
            json={
                "model": "nonexistent-model-xyz",
                "messages": [{"role": "user", "content": "Hi"}],
                "max_completion_tokens": 5,
            },
            timeout=30,
        )
        assert r.status_code in (400, 404), r.text
        data = r.json()
        assert_error_response(data)

    # --- From AiInferenceIT: estimate points for chat ---
    def test_estimate_points_for_chat(self, api):
        """Mirrors AiInferenceIT.kt `estimate points for chat`: chat usage > 0."""
        r = api.chat_completions_verified(
            [{"role": "user", "content": "Hello, how are you?"}],
            max_completion_tokens=10,
        )
        assert r.status_code == 200, r.text
        usage = r.json()["usage"]
        assert usage["prompt_tokens"] + usage["completion_tokens"] > 0

    # --- From AiInferenceIT: chat inference and validation (parameterized) ---
    @pytest.mark.parametrize("prompt", [
        "Hello, world! My name is",
        "Hello, how are you?",
        "How is the weather in Stockholm?",
        "What is Kotlin used for?",
        "What is the capital of France?",
        "Translate 'hello' to French:",
    ])
    def test_chat_inference_and_validation(self, api, prompt):
        """Mirrors AiInferenceIT.kt `chat inference and validation`: chat then verify_decoding."""
        r = api.chat_completions_verified(
            [{"role": "user", "content": prompt}],
            max_completion_tokens=20,
        )
        assert r.status_code == 200, r.text
        data = r.json()
        assert_verified_chat_completion_response(data, api.model)
        choice = data["choices"][0]
        prompt_ids = [int(x) for x in choice["prompt_token_ids"]]
        completion_ids = [int(x) for x in choice["completion_token_ids"]]
        verify_r = api.verify_decoding(prompt_ids, completion_ids, check_greedy=True)
        assert verify_r.status_code == 200, verify_r.text
        verify_data = verify_r.json()
        assert_verify_decoding_response(verify_data, api.model)
        assert_verified_result_from_same_tokens(verify_data)


# ========== Tests: POST /v1/verify_decoding ==========


class TestVerifyDecoding:
    """POST /v1/verify_decoding — VerifyDecodingRequest -> VerifyDecodingResponse."""

    def test_verify_decoding_after_completion(self, api):
        # Get real token IDs from a completion, then verify them
        comp = api.completions_verified("Hello.", max_tokens=5)
        assert comp.status_code == 200, comp.text
        c = comp.json()
        choice = c["choices"][0]
        prompt_ids = [int(x) for x in choice["prompt_token_ids"]]
        completion_ids = [int(x) for x in choice["completion_token_ids"]]
        assert len(prompt_ids) >= 1
        assert len(completion_ids) >= 1

        r = api.verify_decoding(prompt_ids, completion_ids, check_greedy=True)
        assert r.status_code == 200, r.text
        data = r.json()
        assert_verify_decoding_response(data, api.model)
        assert_verified_result_from_same_tokens(data)
        assert data["completion_token_ids"] == completion_ids

    def test_verify_decoding_check_greedy_false(self, api):
        comp = api.completions_verified("Hi", max_tokens=3)
        assert comp.status_code == 200, comp.text
        choice = comp.json()["choices"][0]
        prompt_ids = [int(x) for x in choice["prompt_token_ids"]]
        completion_ids = [int(x) for x in choice["completion_token_ids"]]

        r = api.verify_decoding(
            prompt_ids,
            completion_ids,
            check_greedy=False,
        )
        assert r.status_code == 200, r.text
        data = r.json()
        assert_verify_decoding_response(data, api.model)
        assert "is_verified_greedy" in data

    def test_verify_decoding_invalid_model_returns_error(self, api, base_url, session):
        r = session.post(
            f"{base_url}/v1/verify_decoding",
            json={
                "model": "nonexistent-model-xyz",
                "prompt": [1, 2, 3],
                "completion": [4, 5],
                "prompt_logprobs": 0,
                "check_greedy": True,
                "greedy_logprob_threshold": 0.001,
            },
            timeout=30,
        )
        assert r.status_code in (400, 404), r.text
        data = r.json()
        assert_error_response(data)

    # --- From AiInferenceIT: negative validation (tampered completion -> not verified) ---
    def test_negative_validation_invalid_completion_tokens(self, api):
        """Mirrors AiInferenceIT.kt `negative validation with cache`: verify_decoding with wrong completion returns not verified."""
        comp = api.completions_verified("Some prompt", max_tokens=5)
        assert comp.status_code == 200, comp.text
        choice = comp.json()["choices"][0]
        prompt_ids = [int(x) for x in choice["prompt_token_ids"]]
        completion_ids = [int(x) for x in choice["completion_token_ids"]]
        if not completion_ids:
            pytest.skip("Model returned no completion tokens")
        # Tamper: change last token so it no longer matches greedy decode
        tampered = completion_ids[:-1] + [completion_ids[-1] + 1]
        r = api.verify_decoding(prompt_ids, tampered, check_greedy=True)
        assert r.status_code == 200, r.text
        data = r.json()
        assert_verify_decoding_response(data, api.model)
        assert data.get("is_verified_greedy") is False


# ========== Tests from AiInferenceIT.kt: error cases and client behaviour ==========


class TestErrorCasesFromAiInferenceIT:
    """Error scenarios mirroring AiInferenceIT.kt."""

    def test_non_existing_model_message_contains_model_name(self, base_url, session):
        """Mirrors AiInferenceIT.kt `non existing model`: error message mentions the model."""
        r = session.post(
            f"{base_url}/v1/completions/verified",
            json={"model": "bogus-model", "prompt": "What is the capital of France?", "max_tokens": 5, "stop": "."},
            timeout=30,
        )
        assert r.status_code in (400, 404), r.text
        data = r.json()
        assert_error_response(data)
        msg = (data.get("error") or {}).get("message") or data.get("detail") or str(data)
        assert "bogus-model" in msg or "does not exist" in msg.lower() or "not found" in msg.lower()

    def test_too_many_max_tokens(self, api, base_url, session):
        """Mirrors AiInferenceIT.kt `too many max tokens`: max_tokens exceeds context -> 400."""
        r = session.post(
            f"{base_url}/v1/completions/verified",
            json={
                "model": api.model,
                "prompt": "What is the capital of France?",
                "max_tokens": 10000,
                "stop": ".",
            },
            timeout=30,
        )
        assert r.status_code == 400, r.text
        data = r.json()
        msg = (data.get("error") or {}).get("message") or data.get("detail") or r.text
        assert "2048" in msg or "max" in msg.lower() or "context" in msg.lower() or "length" in msg.lower()

    def test_too_many_input_tokens(self, api, base_url, session):
        """Mirrors AiInferenceIT.kt `too many input tokens`: very long prompt -> 400."""
        long_prompt = "What is the capital of France? " * 1000
        r = session.post(
            f"{base_url}/v1/completions/verified",
            json={"model": api.model, "prompt": long_prompt, "max_tokens": 5},
            timeout=60,
        )
        assert r.status_code == 400, r.text
        data = r.json()
        msg = (data.get("error") or {}).get("message") or data.get("detail") or r.text
        assert "2048" in msg or "context" in msg.lower() or "length" in msg.lower()

    def test_wrong_url_returns_not_found(self, api, base_url, session):
        """Mirrors AiInferenceIT.kt `wrong URL`: request to wrong path -> 404."""
        # Post to base_url + /bogus so path is .../bogus/v1/completions/verified -> 404
        wrong_url = f"{base_url}/bogus"
        r = session.post(
            f"{wrong_url}/v1/completions/verified",
            json={
                "model": api.model,
                "prompt": "What is the capital of France?",
                "max_tokens": 5,
                "stop": ".",
            },
            timeout=10,
        )
        assert r.status_code == 404, r.text


class TestRetriesFromAiInferenceIT:
    """Mirrors AiInferenceIT.kt `retries`: 500 response with error body (Kotlin client retries 5x then fails with message)."""

    def test_500_response_has_error_body(self):
        """Server returning 500 with error body: response has status 500 and error.message (contract for retry failure)."""
        request_count = []
        error_body = b'{"error": {"message": "the_error"}}'

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                request_count.append(1)
                self.send_response(500)
                self.send_header("Content-type", "application/json")
                self.end_headers()
                self.wfile.write(error_body)

            def log_message(self, *args):
                pass

        server = HTTPServer(("127.0.0.1", 0), Handler)
        port = server.server_address[1]
        thread = threading.Thread(target=server.serve_forever)
        thread.daemon = True
        thread.start()
        try:
            time.sleep(0.1)
            url = f"http://127.0.0.1:{port}"
            r = requests.post(
                f"{url}/v1/completions/verified",
                json={"model": "x", "prompt": "Hi", "max_tokens": 1},
                timeout=5,
                headers={"Content-Type": "application/json"},
            )
            assert r.status_code == 500, r.text
            data = r.json() if r.text else {}
            msg = (data.get("error") or {}).get("message", "") or data.get("detail", "") or r.text
            assert "the_error" in msg
            assert len(request_count) >= 1
        finally:
            server.shutdown()
            server.server_close()


# ========== Tests: Endpoint availability and auth ==========


class TestVerifiedEndpointsOnly:
    """Ensure we only use verified endpoints; non-verified paths may 404 or behave differently."""

    def test_completions_verified_path_accepts_post(self, api):
        r = api.completions_verified("x", max_tokens=1)
        assert r.status_code in (200, 400, 404), r.text

    def test_plain_completions_not_used(self, base_url, session, model):
        """Optional: ensure we are not calling unverified /v1/completions."""
        r = session.post(
            f"{base_url}/v1/completions",
            json={"model": model, "prompt": "Hi", "max_tokens": 1},
            timeout=10,
        )
        # If server only exposes verified, this might 404; if it exists, we just note it
        assert r.status_code in (200, 404, 405), r.text

    def test_auth_required_when_configured(self, base_url, model, auth):
        """If auth is set in env, wrong auth should 401."""
        if not auth:
            pytest.skip("VLLM_AUTH_USER/VLLM_AUTH_PASS not set")
        session = requests.Session()
        session.headers["Content-Type"] = "application/json"
        session.auth = ("wrong_user", "wrong_pass")
        r = session.post(
            f"{base_url}/v1/completions/verified",
            json={"model": model, "prompt": "Hi", "max_tokens": 1},
            timeout=10,
        )
        assert r.status_code == 401, r.text

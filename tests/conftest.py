"""
Pytest configuration and fixtures for vLLM verified API tests.

Environment variables:
  VLLM_BASE_URL   - Base URL of the vLLM server (e.g. http://localhost:8000)
  VLLM_MODEL      - Model name to use for tests
  VLLM_AUTH_USER  - Optional basic auth username
  VLLM_AUTH_PASS  - Optional basic auth password (required if VLLM_AUTH_USER is set)
"""
import os

import pytest
import requests


def _get_base_url():
    url = os.environ.get("VLLM_BASE_URL")
    if not url:
        pytest.skip("VLLM_BASE_URL not set")
    return url.rstrip("/")


def _get_model():
    model = os.environ.get("VLLM_MODEL")
    if not model:
        pytest.skip("VLLM_MODEL not set")
    return model


def _get_auth():
    user = os.environ.get("VLLM_AUTH_USER")
    passwd = os.environ.get("VLLM_AUTH_PASS")
    if user and passwd:
        return (user, passwd)
    if user or passwd:
        pytest.skip("Both VLLM_AUTH_USER and VLLM_AUTH_PASS must be set for basic auth")
    return None


@pytest.fixture(scope="session")
def base_url():
    return _get_base_url()


@pytest.fixture(scope="session")
def model():
    return _get_model()


@pytest.fixture(scope="session")
def auth():
    return _get_auth()


@pytest.fixture(scope="session")
def session(base_url, auth):
    s = requests.Session()
    s.headers["Content-Type"] = "application/json"
    if auth:
        s.auth = auth
    return s


@pytest.fixture
def api(base_url, model, session):
    """Helper to call verified endpoints."""
    return VerifiedApiClient(base_url, model, session)


class VerifiedApiClient:
    """Client for verified endpoints only: completions/verified, chat/completions/verified, verify_decoding."""

    COMPLETIONS_VERIFIED = "/v1/completions/verified"
    CHAT_COMPLETIONS_VERIFIED = "/v1/chat/completions/verified"
    VERIFY_DECODING = "/v1/verify_decoding"

    def __init__(self, base_url: str, model: str, session: requests.Session):
        self.base_url = base_url
        self.model = model
        self.session = session

    def _post(self, path: str, json: dict, timeout: int = 60):
        return self.session.post(
            f"{self.base_url}{path}",
            json=json,
            timeout=timeout,
        )

    def completions_verified(
        self,
        prompt: str,
        *,
        max_tokens: int | None = None,
        stop: str | None = None,
    ):
        body = {
            "model": self.model,
            "prompt": prompt,
        }
        if max_tokens is not None:
            body["max_tokens"] = max_tokens
        if stop is not None:
            body["stop"] = stop
        return self._post(self.COMPLETIONS_VERIFIED, body)

    def chat_completions_verified(
        self,
        messages: list[dict],
        *,
        max_completion_tokens: int | None = None,
        stop: str | None = None,
    ):
        body = {
            "model": self.model,
            "messages": messages,
        }
        if max_completion_tokens is not None:
            body["max_completion_tokens"] = max_completion_tokens
        if stop is not None:
            body["stop"] = stop
        return self._post(self.CHAT_COMPLETIONS_VERIFIED, body)

    def verify_decoding(
        self,
        prompt_token_ids: list[int],
        completion_token_ids: list[int],
        *,
        check_greedy: bool = True,
        greedy_logprob_threshold: float = 0.001,
        prompt_logprobs: int = 0,
    ):
        body = {
            "model": self.model,
            "prompt": prompt_token_ids,
            "completion": completion_token_ids,
            "prompt_logprobs": prompt_logprobs,
            "check_greedy": check_greedy,
            "greedy_logprob_threshold": greedy_logprob_threshold,
        }
        return self._post(self.VERIFY_DECODING, body, timeout=30)

"""Small Workers AI chat client. Credentials come only from the environment.

No request/response bodies or upstream exception messages are logged or exposed.
"""

from dataclasses import dataclass, field
import json
import os
import re
import threading
import time
from urllib.error import HTTPError
from urllib.request import HTTPRedirectHandler, Request, build_opener

DEFAULT_MODEL = '@cf/google/gemma-4-26b-a4b-it'
MAX_RESPONSE_BYTES = 4 * 1024 * 1024
_NO_JSON_MODE = set()
_JSON_MODE_LOCK = threading.Lock()


class CloudflareError(Exception):
    """Only fixed public codes and numeric status may cross the client boundary."""

    def __init__(self, http_status, code, *, retryable=False, response_format_accepted=None):
        super().__init__(code)
        self.http_status = http_status
        self.code = code
        self.retryable = retryable
        self.response_format_accepted = response_format_accepted
        self.latency_ms = None


@dataclass(frozen=True)
class Completion:
    content: str = field(repr=False)
    http_status: int
    response_format_accepted: bool | None
    finish_reason: str | None
    reasoning_tokens: int | None
    latency_ms: int


def extract_json(content):
    """Return the first decodable object after removing reasoning and fences.

    raw_decode handles escaped quotes and nested braces without guessing where an
    object ends. Never expose JSONDecodeError (it contains the original content).
    """
    if not isinstance(content, str):
        raise ValueError('invalid output')
    text = re.sub(r'<(think|thinking|analysis|reasoning)\b[^>]*>.*?</\1\s*>',
                  '', content, flags=re.I | re.S)
    text = re.sub(r'^\s*```(?:json)?\s*|\s*```\s*$', '', text, flags=re.I)
    decoder = json.JSONDecoder()
    for match in re.finditer(r'\{', text):
        try:
            value, end = decoder.raw_decode(text, match.start())
            if isinstance(value, dict):
                return text[match.start():end]
        except (ValueError, RecursionError):
            continue
    raise ValueError('invalid output') from None


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # Never forward the bearer token to a redirect target.


def _error(status, body, *, response_format_accepted=None):
    """Classify in memory; never retain the upstream body in an exception."""
    text = body.decode('utf-8', errors='replace').lower()
    # Cloudflare sometimes wraps an error in HTTP 200 success:false.
    if status == 200:
        if re.search(r'auth|unauthorized|forbidden|invalid.{0,20}token|\b(?:401|403|10000|9109)\b', text):
            status = 401
        elif re.search(r'rate.?limit|too many requests|\b429\b', text):
            status = 429
        elif re.search(r'\b5\d\d\b|internal server|temporarily unavailable', text):
            status = 502
        else:
            status = 400
    if status in (401, 403):
        return CloudflareError(401, 'authentication_error', response_format_accepted=response_format_accepted)
    if status == 429:
        return CloudflareError(429, 'usage_limit', retryable=True,
                               response_format_accepted=response_format_accepted)
    return CloudflareError(status, 'backend_error', retryable=status >= 500,
                           response_format_accepted=response_format_accepted)


class CloudflareBackend:
    def __init__(self, model=DEFAULT_MODEL, *, max_completion_tokens=1024,
                 temperature=0.2, timeout=120, json_schema=None,
                 chat_template_kwargs=None, opener=None):
        self.model = model
        self.max_completion_tokens = max_completion_tokens
        self.temperature = temperature
        self.timeout = timeout
        self.json_schema = json_schema
        self.chat_template_kwargs = chat_template_kwargs
        self._opener = opener

    def _request(self, payload):
        account = os.environ.get('CLOUDFLARE_ACCOUNT_ID', '')
        token = os.environ.get('CLOUDFLARE_API_TOKEN', '')
        if not account.strip() or not token.strip():
            raise CloudflareError(503, 'cloudflare_not_configured')
        if not re.fullmatch(r'[A-Za-z0-9_-]+', account):
            raise CloudflareError(503, 'cloudflare_invalid_configuration')
        try:
            request = Request(
                f'https://api.cloudflare.com/client/v4/accounts/{account}/ai/v1/chat/completions',
                data=json.dumps(payload, ensure_ascii=False, allow_nan=False).encode('utf-8'),
                headers={'Authorization': f'Bearer {token}', 'Content-Type': 'application/json'},
                method='POST')
            opener = self._opener if self._opener is not None else build_opener(_NoRedirect())
            try:
                with opener.open(request, timeout=self.timeout) as response:
                    return response.status, response.read(MAX_RESPONSE_BYTES + 1)
            except HTTPError as error:
                try:
                    return error.code, error.read(MAX_RESPONSE_BYTES + 1)
                finally:
                    error.close()
        except Exception:
            # Includes urllib errors and invalid headers; no secret-bearing chains.
            raise CloudflareError(502, 'backend_error', retryable=True) from None

    def complete(self, system, user):
        start = time.monotonic()
        try:
            return self._complete(system, user)
        except CloudflareError as error:
            error.latency_ms = round((time.monotonic() - start) * 1000)
            raise

    def _complete(self, system, user):
        start = time.monotonic()
        payload = dict(model=self.model, messages=[{'role': 'system', 'content': system},
                                                   {'role': 'user', 'content': user}],
                       max_completion_tokens=self.max_completion_tokens, temperature=self.temperature)
        if self.chat_template_kwargs is not None:
            payload['chat_template_kwargs'] = self.chat_template_kwargs
        accepted = None
        # The relay is already serialized. This also protects the process cache
        # when the client is used by other callers concurrently.
        with _JSON_MODE_LOCK:
            use_schema = self.json_schema is not None and self.model not in _NO_JSON_MODE
            if use_schema:
                payload['response_format'] = dict(type='json_schema', json_schema=self.json_schema)
            for attempt in range(2):
                try:
                    status, body = self._request(payload)
                except CloudflareError as error:
                    error.response_format_accepted = accepted
                    raise
                if len(body) > MAX_RESPONSE_BYTES:
                    raise CloudflareError(502, 'backend_error')
                try:
                    envelope = json.loads(body)
                except (ValueError, UnicodeError, RecursionError):
                    envelope = None
                failed = status >= 300 or (isinstance(envelope, dict) and envelope.get('success') is False)
                if failed:
                    error = _error(status, body, response_format_accepted=accepted)
                    if (use_schema and attempt == 0 and 400 <= error.http_status < 500
                            and error.http_status not in (401, 403, 429)
                            and re.search(rb'json[\s_-]*(?:mode|grammar|schema)|response[\s_-]*format|xgrammar',
                                          body, re.I)):
                        _NO_JSON_MODE.add(self.model)
                        payload.pop('response_format')
                        use_schema, accepted = False, False
                        continue
                    raise error from None
                break
        try:
            choice = envelope['choices'][0]
            content = choice['message']['content']
            finish = choice.get('finish_reason')
            usage = envelope.get('usage') or {}
            reasoning = (usage.get('completion_tokens_details') or {}).get('reasoning_tokens')
            if not isinstance(content, str) or (finish is not None and not isinstance(finish, str)):
                raise ValueError
            # Never print arbitrary upstream text through the probe's metadata.
            if finish is not None and finish not in ('stop', 'length', 'content_filter', 'tool_calls', 'function_call'):
                finish = 'unknown'
            if reasoning is not None and (type(reasoning) is not int or reasoning < 0):
                raise ValueError
        except (ValueError, TypeError, KeyError, IndexError, AttributeError, RecursionError):
            raise CloudflareError(502, 'backend_error') from None
        if accepted is None and self.json_schema is not None:
            accepted = use_schema
        return Completion(content=content, http_status=status, response_format_accepted=accepted,
                          finish_reason=finish, reasoning_tokens=reasoning,
                          latency_ms=round((time.monotonic() - start) * 1000))

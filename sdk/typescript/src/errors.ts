/**
 * Public error classes the SDK throws. Mirrors the Python SDK's
 * `koreafilings.errors` module so the same try/catch shape works in
 * both languages.
 */

/** Base class — every SDK-thrown error inherits from this. */
export class KoreaFilingsError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'KoreaFilingsError';
  }
}

/**
 * Thrown for any non-2xx, non-402 HTTP response from the API.
 * `status` is the HTTP status code; `body` is the parsed JSON
 * envelope (typically `{ error, message }`) or `null` if the response
 * was unparseable.
 */
export class ApiError extends KoreaFilingsError {
  readonly status: number;
  readonly body: unknown;

  constructor(status: number, body: unknown) {
    super(`API error ${status}: ${stringifyBody(body)}`);
    this.name = 'ApiError';
    this.status = status;
    this.body = body;
  }
}

/**
 * Thrown when the x402 payment flow itself fails — bad signature,
 * facilitator rejection, network mismatch between the configured
 * `network` and what the server's 402 advertises, or a settle that
 * succeeded /verify but failed /settle (HTTP 402 on the retry call
 * with a failure SettlementResponse in PAYMENT-RESPONSE).
 */
export class PaymentError extends KoreaFilingsError {
  readonly reason: string;
  readonly detail: unknown;

  constructor(reason: string, detail?: unknown) {
    super(`payment error: ${reason}${detail ? ` (${stringifyBody(detail)})` : ''}`);
    this.name = 'PaymentError';
    this.reason = reason;
    this.detail = detail;
  }
}

/** Thrown only at construction time when the caller passed a malformed argument. */
export class ConfigurationError extends KoreaFilingsError {
  constructor(message: string) {
    super(message);
    this.name = 'ConfigurationError';
  }
}

function stringifyBody(body: unknown): string {
  if (body == null) return '<no body>';
  if (typeof body === 'string') return body;
  try {
    return JSON.stringify(body);
  } catch {
    return String(body);
  }
}

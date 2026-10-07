import type { LoginResponse } from './types'

const baseUrl = (import.meta.env.VITE_API_BASE_URL || '/api/v1').replace(/\/$/, '')
const csrfName =
  import.meta.env.VITE_CSRF_COOKIE_NAME ||
  (import.meta.env.DEV ? 'ticket_csrf' : '__Host-ticket_csrf')
let accessToken: string | null = null
let expiresAt = 0
let refreshPromise: Promise<void> | null = null

const messages: Record<string, string> = {
  UNAUTHORIZED: 'Your session has ended. Please sign in again.',
  FORBIDDEN: 'Your account does not have access to this action.',
  USERNAME_UNAVAILABLE: 'That username is taken. Try another one.',
  SOLD_OUT: 'These tickets have sold out. Try another event.',
  SALE_NOT_STARTED: 'Ticket sales have not opened yet.',
  SALE_ENDED: 'Ticket sales have ended for this event.',
  USER_LIMIT_EXCEEDED: 'You have reached the ticket limit for this event.',
  RESERVATION_EXPIRED: 'Your hold has expired. Reserve tickets again to continue.',
  RESERVATION_NOT_FOUND: 'We could not find that reservation.',
  EVENT_NOT_FOUND: 'This event could not be found.',
  IDEMPOTENCY_IN_PROGRESS: 'Your reservation is still being processed. Please try again shortly.',
  IDEMPOTENCY_KEY_CONFLICT: 'This reservation request has changed. Reload the page and try again.',
  INVALID_STATE: 'This reservation has already changed. Its latest status will appear shortly.',
  PAYMENT_DECLINED: 'The booking could not be confirmed. Your hold is still available.',
  DEPENDENCY_UNAVAILABLE: 'The service is temporarily unavailable. Please try again shortly.',
  DEPENDENCY_TIMEOUT: 'The service took too long to respond. Please try again.',
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly traceId?: string
  readonly retryAfter?: number
  constructor(
    status: number,
    code: string,
    message: string,
    traceId?: string,
    retryAfter?: number,
  ) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.traceId = traceId
    this.retryAfter = retryAfter
  }
}

function csrfCookie() {
  const cookie = document.cookie.split('; ').find((item) => item.startsWith(`${csrfName}=`))
  return cookie ? decodeURIComponent(cookie.slice(csrfName.length + 1)) : null
}

export function acceptSession(session: LoginResponse) {
  accessToken = session.accessToken
  expiresAt = Date.now() + session.expiresIn * 1000
}

function clearSession() {
  accessToken = null
  expiresAt = 0
  window.dispatchEvent(new Event('session-ended'))
}

// Cookies rotate on refresh. Serialize both within a tab and across same-origin tabs.
function sessionLock<T>(operation: () => Promise<T>): Promise<T> {
  return navigator.locks ? navigator.locks.request('encore-auth-session', operation) : operation()
}

async function send<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  headers.set('X-Request-Id', crypto.randomUUID())
  if (init.body) headers.set('Content-Type', 'application/json')
  const deadline = AbortSignal.timeout(15_000)
  const signal = init.signal ? AbortSignal.any([init.signal, deadline]) : deadline
  let response: Response
  try {
    response = await fetch(`${baseUrl}${path}`, {
      ...init,
      headers,
      signal,
      credentials: 'include',
    })
  } catch (error) {
    if (init.signal?.aborted) throw error
    throw new ApiError(
      0,
      'NETWORK_ERROR',
      'Unable to reach the ticket service. Check your connection and try again.',
    )
  }
  if (!response.ok) {
    const problem = (await response.json().catch(() => ({}))) as Record<string, unknown>
    const code = typeof problem.code === 'string' ? problem.code : 'HTTP_ERROR'
    const wait = Number(response.headers.get('Retry-After')) || undefined
    const message =
      code === 'RATE_LIMITED'
        ? `Too many attempts. ${wait ? `Try again in ${wait} seconds.` : 'Please wait before trying again.'}`
        : messages[code] ||
          (typeof problem.detail === 'string'
            ? problem.detail
            : 'Something went wrong. Please try again.')
    throw new ApiError(
      response.status,
      code,
      message,
      typeof problem.traceId === 'string' ? problem.traceId : undefined,
      wait,
    )
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

export async function refreshSession(rejectedToken?: string | null): Promise<void> {
  if (accessToken && accessToken !== rejectedToken && expiresAt > Date.now() + 30_000) return
  if (!refreshPromise) {
    refreshPromise = sessionLock(async () => {
      const csrf = csrfCookie()
      if (!csrf) throw new ApiError(401, 'UNAUTHORIZED', messages.UNAUTHORIZED)
      const session = await send<LoginResponse>('/auth/refresh', {
        method: 'POST',
        headers: { 'X-CSRF-TOKEN': csrf },
      })
      acceptSession(session)
    })
      .catch((error: unknown) => {
        if (error instanceof ApiError && (error.status === 401 || error.status === 403))
          clearSession()
        throw error
      })
      .finally(() => {
        refreshPromise = null
      })
  }
  return refreshPromise
}

export async function api<T>(
  path: string,
  init: RequestInit = {},
  authenticated = false,
): Promise<T> {
  if (!authenticated) return send<T>(path, init)
  if (!accessToken || expiresAt <= Date.now() + 30_000) await refreshSession()
  const usedToken = accessToken
  const authorized = () => {
    const headers = new Headers(init.headers)
    headers.set('Authorization', `Bearer ${accessToken}`)
    return send<T>(path, { ...init, headers })
  }
  try {
    return await authorized()
  } catch (error) {
    if (!(error instanceof ApiError) || error.status !== 401) throw error
    await refreshSession(usedToken)
    try {
      return await authorized()
    } catch (retryError) {
      if (retryError instanceof ApiError && retryError.status === 401) clearSession()
      throw retryError
    }
  }
}

export async function restoreSession() {
  if (!csrfCookie()) return false
  await refreshSession()
  return true
}

export async function signIn(credentials: { username: string; password: string }) {
  await sessionLock(async () => {
    try {
      acceptSession(
        await send<LoginResponse>('/auth/login', {
          method: 'POST',
          body: JSON.stringify(credentials),
        }),
      )
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        throw new ApiError(
          401,
          error.code,
          'The username or password is incorrect, or this account is unavailable.',
          error.traceId,
        )
      }
      throw error
    }
  })
}

export async function signOut() {
  await sessionLock(async () => {
    const csrf = csrfCookie()
    await send<void>('/auth/logout', {
      method: 'POST',
      headers: csrf ? { 'X-CSRF-TOKEN': csrf } : {},
    })
    clearSession()
  })
}

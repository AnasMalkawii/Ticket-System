import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { eventFormSchema, quantitySchema, registerSchema } from '../../src/lib/validation'
import { money, saleIsOpen } from '../../src/lib/format'

describe('backend validation boundaries', () => {
  it('rejects a password that exceeds BCrypt’s UTF-8 byte limit', () => {
    const password = '界'.repeat(25)
    expect(
      registerSchema.safeParse({ username: 'fan.user', password, confirmPassword: password })
        .success,
    ).toBe(false)
  })
  it('rejects fractional quantities and quantities beyond the configured default', () => {
    expect(quantitySchema.safeParse({ quantity: 1.5 }).success).toBe(false)
    expect(quantitySchema.safeParse({ quantity: 5 }).success).toBe(false)
  })
  it('checks sale windows and currency precision', () => {
    const event = {
      name: 'Concert',
      venue: 'Arena',
      startsAt: '',
      saleStartsAt: '2026-10-07T20:00',
      saleEndsAt: '2026-10-07T19:00',
      price: '45.50',
      currency: 'EUR',
      totalTickets: '100',
      status: 'SCHEDULED',
    }
    expect(eventFormSchema.safeParse(event).success).toBe(false)
    expect(eventFormSchema.safeParse({ ...event, saleEndsAt: '', currency: 'JPY' }).success).toBe(
      false,
    )
    expect(
      eventFormSchema.safeParse({ ...event, saleEndsAt: '', currency: 'JOD', price: '12.345' })
        .success,
    ).toBe(true)
  })
  it('interprets minor currency units and opening/closing boundaries', () => {
    expect(money(12345, 'JOD')).toContain('12.345')
    expect(money(4500, 'JPY')).toContain('4,500')
    const event = {
      status: 'ON_SALE',
      saleStartsAt: '2026-10-07T10:00:00Z',
      saleEndsAt: '2026-10-07T11:00:00Z',
    }
    expect(saleIsOpen(event, Date.parse(event.saleStartsAt))).toBe(true)
    expect(saleIsOpen(event, Date.parse(event.saleEndsAt))).toBe(false)
    expect(saleIsOpen({ ...event, status: 'SCHEDULED' }, Date.parse(event.saleStartsAt))).toBe(
      false,
    )
  })
})

describe.each([
  { mode: 'development', development: true, csrfCookie: 'ticket_csrf' },
  { mode: 'production', development: false, csrfCookie: '__Host-ticket_csrf' },
])('authenticated fetch ($mode)', ({ development, csrfCookie }) => {
  beforeEach(() => {
    vi.resetModules()
    vi.stubEnv('DEV', development)
    vi.stubEnv('VITE_CSRF_COOKIE_NAME', '')
    vi.stubGlobal('document', { cookie: `${csrfCookie}=csrf-value` })
    vi.stubGlobal('window', new EventTarget())
    vi.stubGlobal('navigator', {})
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.unstubAllEnvs()
  })

  it('serializes simultaneous 401 responses into one refresh with CSRF', async () => {
    let refreshes = 0
    let requests = 0
    const fetchMock = vi.fn(async (url: string, init: RequestInit) => {
      const headers = new Headers(init.headers)
      expect(init.credentials).toBe('include')
      expect(headers.get('X-Request-Id')).toBeTruthy()
      if (url.endsWith('/auth/refresh')) {
        refreshes++
        expect(headers.get('X-CSRF-TOKEN')).toBe('csrf-value')
        expect(headers.has('Authorization')).toBe(false)
        await new Promise((resolve) => setTimeout(resolve, 10))
        return Response.json({
          accessToken: 'fresh',
          expiresIn: 900,
          tokenType: 'Bearer',
          role: 'USER',
        })
      }
      requests++
      return headers.get('Authorization') === 'Bearer fresh'
        ? Response.json({ id: 'reservation' })
        : Response.json({ code: 'UNAUTHORIZED' }, { status: 401 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const { api, acceptSession } = await import('../../src/lib/api')
    acceptSession({ accessToken: 'old', expiresIn: 900, tokenType: 'Bearer', role: 'USER' })
    await Promise.all([api('/reservations/a', {}, true), api('/reservations/b', {}, true)])
    expect(refreshes).toBe(1)
    expect(requests).toBe(4)
  })

  it('keeps bearer credentials out of public requests', async () => {
    const fetchMock = vi.fn(async (_url: string, init: RequestInit) => {
      expect(new Headers(init.headers).has('Authorization')).toBe(false)
      return Response.json({ content: [] })
    })
    vi.stubGlobal('fetch', fetchMock)
    const { api, acceptSession } = await import('../../src/lib/api')
    acceptSession({ accessToken: 'private', expiresIn: 900, tokenType: 'Bearer', role: 'USER' })
    await api('/events')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('does not automatically repeat an uncertain reservation write', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'))
    vi.stubGlobal('fetch', fetchMock)
    const { api, acceptSession } = await import('../../src/lib/api')
    acceptSession({ accessToken: 'private', expiresIn: 900, tokenType: 'Bearer', role: 'USER' })
    await expect(
      api('/events/a/reservations', { method: 'POST', body: '{"quantity":1}' }, true),
    ).rejects.toMatchObject({ code: 'NETWORK_ERROR' })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('ends the client session when the refresh credential is rejected', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => Response.json({ code: 'UNAUTHORIZED' }, { status: 401 })),
    )
    const ended = vi.fn()
    window.addEventListener('session-ended', ended)
    const { api } = await import('../../src/lib/api')
    await expect(api('/auth/me', {}, true)).rejects.toMatchObject({ status: 401 })
    expect(ended).toHaveBeenCalledTimes(1)
  })
})

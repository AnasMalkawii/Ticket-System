import { test, expect, type Page } from '@playwright/test'

const eventId = '11111111-1111-4111-8111-111111111111'
const reservationId = 'aaaa1111-1111-4111-8111-111111111111'
const userId = 'bbbb1111-1111-4111-8111-111111111111'
const mainEvent = {
  id: eventId,
  name: 'Aurora Live — Opening Night',
  venue: 'Riverside Arena',
  startsAt: '2026-11-07T19:00:00Z',
  saleStartsAt: '2020-01-01T00:00:00Z',
  saleEndsAt: '2099-01-01T00:00:00Z',
  status: 'ON_SALE',
  priceMinor: 4500,
  currency: 'EUR',
}
const lineup = [
  mainEvent,
  {
    ...mainEvent,
    id: '22222222-2222-4222-8222-222222222222',
    name: 'Aurora Live — Second Night',
    status: 'SCHEDULED',
  },
  {
    ...mainEvent,
    id: '33333333-3333-4333-8333-333333333333',
    name: 'Midnight Sessions — Finale',
    venue: 'The Vault',
    status: 'CLOSED',
    priceMinor: 3000,
  },
]

async function mockApi(
  page: Page,
  options: { role?: 'USER' | 'ADMIN'; failFirstReserve?: boolean } = {},
) {
  let authenticated = false
  let event = { ...mainEvent }
  let total = 100
  let reservation = {
    id: reservationId,
    eventId,
    userId,
    quantity: 1,
    status: 'PENDING',
    expiresAt: new Date(Date.now() + 180_000).toISOString(),
    terminatedAt: null as string | null,
    createdAt: new Date().toISOString(),
  }
  const reservationKeys: string[] = []
  const payloads: { path: string; body: Record<string, unknown> }[] = []
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname.replace('/api/v1', '')
    const method = request.method()
    const body = request.postDataJSON() as Record<string, unknown> | null
    if (body) payloads.push({ path, body })
    const reply = (data: unknown, status = 200, headers = {}) =>
      route.fulfill({
        status,
        contentType: 'application/json',
        body: JSON.stringify(data),
        headers,
      })
    if (path === '/auth/login' || path === '/auth/refresh') {
      if (path.endsWith('refresh')) expect(request.headers()['x-csrf-token']).toBe('csrf-fixture')
      authenticated = true
      return reply(
        {
          accessToken: 'test-access',
          tokenType: 'Bearer',
          expiresIn: 900,
          role: options.role || 'USER',
        },
        200,
        { 'Set-Cookie': '__Host-ticket_csrf=csrf-fixture; Secure; Path=/; SameSite=Strict' },
      )
    }
    if (path === '/auth/logout') {
      expect(request.headers()['x-csrf-token']).toBe('csrf-fixture')
      authenticated = false
      return route.fulfill({
        status: 204,
        headers: { 'Set-Cookie': '__Host-ticket_csrf=; Secure; Path=/; Max-Age=0' },
      })
    }
    if (path === '/auth/register')
      return reply({ id: userId, username: body?.username, role: 'USER' }, 201)
    if (path === '/auth/me')
      return authenticated
        ? reply({
            id: userId,
            username: options.role === 'ADMIN' ? 'organizer' : 'musicfan',
            role: options.role || 'USER',
          })
        : reply({ code: 'UNAUTHORIZED' }, 401)
    if (path === '/events') {
      const content = lineup
        .map((item) => (item.id === eventId ? event : item))
        .filter(
          (item) =>
            !url.searchParams.get('status') || item.status === url.searchParams.get('status'),
        )
      return reply({ content, page: 0, size: 12, totalElements: content.length, totalPages: 1 })
    }
    if (path.endsWith('/availability'))
      return reply({
        eventId,
        total,
        available: total - (reservation.status === 'CONFIRMED' ? reservation.quantity : 0),
        advisory: true,
        asOf: new Date().toISOString(),
      })
    if (path === `/events/${eventId}`) return reply(event)
    if (path === `/events/${eventId}/reservations`) {
      expect(request.headers()['authorization']).toBe('Bearer test-access')
      expect(request.headers()['x-request-id']).toBeTruthy()
      reservationKeys.push(request.headers()['idempotency-key'])
      if (options.failFirstReserve && reservationKeys.length === 1) return route.abort('failed')
      reservation = { ...reservation, quantity: Number(body?.quantity) }
      return reply(reservation, 201)
    }
    if (path === `/reservations/${reservationId}/confirm`) {
      expect(body).toEqual({ paymentToken: 'tok_ok' })
      reservation = { ...reservation, status: 'CONFIRMED', terminatedAt: new Date().toISOString() }
      return reply({
        reservation,
        order: {
          id: 'order-1',
          reservationId,
          status: 'PAID',
          amountMinor: reservation.quantity * 4500,
          currency: 'EUR',
          createdAt: new Date().toISOString(),
        },
      })
    }
    if (path === `/reservations/${reservationId}`) {
      if (method === 'DELETE') reservation = { ...reservation, status: 'CANCELLED' }
      return reply(reservation)
    }
    if (path === '/admin/events' || path === `/admin/events/${eventId}`) {
      if (body?.addTickets) total += Number(body.addTickets)
      else event = { ...event, ...body }
      return reply(event, method === 'POST' ? 201 : 200)
    }
    return reply({ detail: `Unexpected test API call: ${method} ${path}` }, 404)
  })
  return { reservationKeys, payloads }
}

async function login(page: Page, redirect = `/events/${eventId}`) {
  await page.goto(`/login?redirect=${encodeURIComponent(redirect)}`)
  await page.getByLabel('Username', { exact: true }).fill('musicfan')
  await page.getByLabel('Password', { exact: true }).fill('correct horse battery staple')
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(redirect.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '$'))
}

test('catalog, filtering, detail links, and responsive layout', async ({ page }, info) => {
  await mockApi(page)
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Find your kind of live.' })).toBeVisible()
  await expect(page.locator('.event-card')).toHaveCount(3)
  await page.screenshot({ path: info.outputPath('catalog.png'), fullPage: true })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  )
  await page.getByRole('button', { name: 'On sale', exact: true }).click()
  await expect(page.locator('.event-card')).toHaveCount(1)
  await page.locator('.event-card').click()
  await expect(page.getByRole('heading', { name: mainEvent.name })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Sign in to reserve' })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  )
})

test('sign in, reserve, confirm, reload session, and find saved bookings', async ({
  page,
}, info) => {
  await mockApi(page)
  await login(page)
  await page.screenshot({ path: info.outputPath('event-details.png'), fullPage: true })
  await page.getByRole('button', { name: 'Add one ticket' }).click()
  await page.getByRole('button', { name: 'Reserve 2 tickets' }).click()
  await expect(page.getByRole('heading', { name: 'Your tickets are on hold.' })).toBeVisible()
  await expect(page.getByRole('timer')).toBeVisible()
  await page.screenshot({ path: info.outputPath('reservation.png'), fullPage: true })
  await page.getByRole('button', { name: 'Confirm booking' }).click()
  await expect(page.getByRole('heading', { name: 'See you in the crowd.' })).toBeVisible()
  await expect(page.getByText('€90.00', { exact: true })).toBeVisible()
  await page.screenshot({ path: info.outputPath('confirmed.png'), fullPage: true })
  await page.reload()
  await expect(page.getByRole('heading', { name: 'See you in the crowd.' })).toBeVisible()
  await page.getByRole('link', { name: 'My tickets', exact: true }).last().click()
  await expect(page.locator('.booking-row')).toHaveCount(1)
  await expect(page.getByRole('heading', { name: mainEvent.name })).toBeVisible()
})

test('retains a reservation key after an uncertain result and page reload', async ({ page }) => {
  const api = await mockApi(page, { failFirstReserve: true })
  await login(page)
  await page.getByRole('button', { name: 'Reserve a ticket' }).click()
  await expect(page.getByRole('alert')).toContainText('Unable to reach')
  await page.reload()
  await page.getByRole('button', { name: 'Reserve a ticket' }).click()
  await expect(page.getByRole('heading', { name: 'Your tickets are on hold.' })).toBeVisible()
  expect(api.reservationKeys).toHaveLength(2)
  expect(api.reservationKeys[0]).toBe(api.reservationKeys[1])
})

test('releases a hold only after choosing to release tickets', async ({ page }) => {
  await mockApi(page)
  await login(page)
  await page.getByRole('button', { name: 'Reserve a ticket' }).click()
  await page.getByRole('button', { name: 'Release my hold' }).click()
  await page.getByRole('button', { name: 'Keep tickets' }).click()
  await expect(page.getByRole('button', { name: 'Confirm booking' })).toBeVisible()
  await page.getByRole('button', { name: 'Release my hold' }).click()
  await page.getByRole('button', { name: 'Release tickets', exact: true }).click()
  await expect(page.getByText('Your tickets have been released.')).toBeVisible()
})

test('registration validates input before sending a request', async ({ page }, info) => {
  const api = await mockApi(page)
  await page.goto('/register')
  await page.screenshot({ path: info.outputPath('register.png'), fullPage: true })
  await page.getByLabel('Username', { exact: true }).fill('ab')
  await page.getByLabel('Password', { exact: true }).fill('short')
  await page.getByLabel('Confirm password').fill('other')
  await page.getByRole('button', { name: 'Create account', exact: true }).click()
  await expect(page.getByText('Use at least 3 characters.')).toBeVisible()
  expect(api.payloads).toHaveLength(0)
  await page.getByLabel('Username', { exact: true }).fill('musicfan')
  await page.getByLabel('Password', { exact: true }).fill('correct horse battery staple')
  await page.getByLabel('Confirm password').fill('correct horse battery staple')
  await page.getByRole('button', { name: 'Create account', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('Your account is ready.')
})

test('admin creates an event, changes sales, and adds inventory', async ({ page }, info) => {
  const api = await mockApi(page, { role: 'ADMIN' })
  await login(page, '/admin')
  await page.getByRole('link', { name: 'Create event', exact: true }).click()
  await page.getByLabel('Event name').fill('An Evening Together')
  await page.getByLabel('Venue', { exact: true }).fill('The Grand Hall')
  await page.getByLabel('Sales open').fill('2026-10-01T12:00')
  await page.getByLabel('Sales close').fill('2026-11-01T12:00')
  await page.getByLabel('Price per ticket').fill('12.345')
  await page.getByLabel('Currency').selectOption('JOD')
  await page.getByLabel('Total tickets').fill('200')
  await page.getByRole('button', { name: 'Create event', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Set the stage.' })).toBeVisible()
  await page.screenshot({ path: info.outputPath('admin-edit.png'), fullPage: true })
  expect(api.payloads.find((item) => item.path === '/admin/events')?.body.priceMinor).toBe(12345)
  await page.getByLabel('Sale status').selectOption('ON_SALE')
  await page.getByRole('button', { name: 'Save changes' }).click()
  await expect(page.getByRole('status')).toContainText('saved')
  await page.getByLabel('Additional tickets').fill('50')
  await page.getByRole('button', { name: 'Add tickets', exact: true }).click()
  await expect(page.getByText('Tickets added successfully.')).toBeVisible()
  expect(api.payloads.some((item) => item.body.addTickets === 50)).toBe(true)
})

test('unavailable API presents a retry action, without fake event data', async ({ page }) => {
  await page.route('**/api/v1/**', (route) =>
    route.fulfill({
      status: 503,
      contentType: 'application/problem+json',
      body: JSON.stringify({ code: 'DEPENDENCY_UNAVAILABLE' }),
    }),
  )
  await page.goto('/')
  await expect(page.getByRole('alert')).toContainText('temporarily unavailable', {
    timeout: 15_000,
  })
  await expect(page.getByRole('button', { name: 'Try again' })).toBeVisible()
  await expect(page.locator('.event-card')).toHaveCount(0)
})

test('signing out clears access to bookings and normal users cannot manage events', async ({
  page,
}) => {
  await mockApi(page)
  await login(page, '/admin')
  await expect(page.getByRole('heading', { name: 'This page is for organizers.' })).toBeVisible()
  const menu = page.getByRole('button', { name: 'Open navigation' })
  if (await menu.isVisible()) await menu.click()
  await page.getByRole('button', { name: 'Sign out', exact: true }).click()
  await expect(page).toHaveURL(/\/login\?redirect=/)
  await page.goto('/tickets')
  await expect(page.getByRole('heading', { name: 'Good to see you again.' })).toBeVisible()
})

test('small phones do not overflow on event and booking pages', async ({ page }) => {
  await mockApi(page)
  await page.setViewportSize({ width: 320, height: 720 })
  await page.goto('/')
  await expect(page.locator('.event-card')).toHaveCount(3)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  )
  await login(page)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  )
  await page.getByRole('button', { name: 'Reserve a ticket' }).click()
  await expect(page.getByRole('heading', { name: 'Your tickets are on hold.' })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  )
})

test('a delayed session response cannot restore an account after sign-out', async ({ page }) => {
  await mockApi(page)
  await login(page)
  let release = () => {}
  let requested = false
  const gate = new Promise<void>((resolve) => {
    release = resolve
  })
  await page.route('**/api/v1/auth/me', async (route) => {
    requested = true
    await gate
    await route
      .fulfill({
        contentType: 'application/json',
        body: JSON.stringify({ id: userId, username: 'musicfan', role: 'USER' }),
      })
      .catch(() => {})
  })
  await page.clock.install()
  await page.clock.setSystemTime(new Date(Date.now() + 61_000))
  await page.evaluate(() => window.dispatchEvent(new Event('visibilitychange')))
  await expect.poll(() => requested).toBe(true)
  const menu = page.getByRole('button', { name: 'Open navigation' })
  if (await menu.isVisible()) await menu.click()
  await page.getByRole('button', { name: 'Sign out', exact: true }).click()
  release()
  await page.goto('/tickets')
  await expect(page.getByRole('heading', { name: 'Good to see you again.' })).toBeVisible()
})

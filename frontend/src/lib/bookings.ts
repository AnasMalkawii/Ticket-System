import { z } from 'zod'

const idsSchema = z.array(z.string().uuid()).max(100)
export function readBookings(userId: string): string[] {
  try {
    const value = idsSchema.safeParse(
      JSON.parse(localStorage.getItem(`encore:bookings:${userId}`) || '[]'),
    )
    return value.success ? value.data : []
  } catch {
    return []
  }
}
export function rememberBooking(userId: string, id: string) {
  try {
    localStorage.setItem(
      `encore:bookings:${userId}`,
      JSON.stringify(
        [id, ...readBookings(userId).filter((existing) => existing !== id)].slice(0, 100),
      ),
    )
  } catch {
    /* Storage may be disabled; the reservation URL still works. */
  }
}

// Reuse the same key after an uncertain network result, including a page reload.
const memoryIntents = new Map<string, string>()
export function reservationKey(userId: string, eventId: string, quantity: number) {
  const name = `encore:hold:${userId}:${eventId}:${quantity}`
  try {
    const existing = sessionStorage.getItem(name)
    if (existing && z.string().uuid().safeParse(existing).success) return existing
    const key = crypto.randomUUID()
    sessionStorage.setItem(name, key)
    return key
  } catch {
    const key = memoryIntents.get(name) || crypto.randomUUID()
    memoryIntents.set(name, key)
    return key
  }
}
export function clearReservationKey(userId: string, eventId: string, quantity: number) {
  const name = `encore:hold:${userId}:${eventId}:${quantity}`
  memoryIntents.delete(name)
  try {
    sessionStorage.removeItem(name)
  } catch {
    /* Optional storage. */
  }
}

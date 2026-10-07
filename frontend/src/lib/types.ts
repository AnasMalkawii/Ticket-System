export const eventStatuses = ['SCHEDULED', 'ON_SALE', 'SOLD_OUT', 'CLOSED', 'CANCELLED'] as const
export type EventStatus = (typeof eventStatuses)[number]
export type Role = 'USER' | 'ADMIN'

export interface User {
  id: string
  username: string
  role: Role
}
export interface LoginResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
  role: Role
}
export interface TicketEvent {
  id: string
  name: string
  venue: string
  startsAt: string | null
  saleStartsAt: string
  saleEndsAt: string | null
  status: EventStatus
  priceMinor: number
  currency: string
}
export interface EventPage {
  content: TicketEvent[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}
export interface Availability {
  eventId: string
  total: number
  available: number
  advisory: boolean
  asOf: string
}
export interface Reservation {
  id: string
  eventId: string
  userId: string
  quantity: number
  status: 'PENDING' | 'CONFIRMED' | 'CANCELLED' | 'EXPIRED'
  expiresAt: string
  terminatedAt: string | null
  createdAt: string
}
export interface Order {
  id: string
  reservationId: string
  status: string
  amountMinor: number
  currency: string
  createdAt: string
}
export interface Confirmation {
  reservation: Reservation
  order: Order
}
export interface EventInput {
  name: string
  venue: string
  startsAt?: string
  saleStartsAt: string
  saleEndsAt?: string
  priceMinor: number
  currency: string
}

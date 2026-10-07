import { queryOptions } from '@tanstack/react-query'
import { api, ApiError } from './api'
import type { Availability, EventPage, EventStatus, Reservation, TicketEvent } from './types'

export const queryRetry = (attempt: number, error: Error) =>
  attempt < 2 && (!(error instanceof ApiError) || error.status === 0 || error.status >= 500)
export const eventsQuery = (page = 0, status?: EventStatus) =>
  queryOptions({
    queryKey: ['events', page, status],
    queryFn: ({ signal }) =>
      api<EventPage>(`/events?page=${page}&size=12${status ? `&status=${status}` : ''}`, {
        signal,
      }),
    staleTime: 30_000,
  })
export const eventQuery = (id: string) =>
  queryOptions({
    queryKey: ['event', id],
    queryFn: ({ signal }) => api<TicketEvent>(`/events/${id}`, { signal }),
    staleTime: 30_000,
  })
export const availabilityQuery = (id: string) =>
  queryOptions({
    queryKey: ['availability', id],
    queryFn: ({ signal }) => api<Availability>(`/events/${id}/availability`, { signal }),
    staleTime: 5_000,
    refetchInterval: 15_000,
  })
export const reservationQuery = (id: string, userId: string) =>
  queryOptions({
    queryKey: ['reservation', userId, id],
    queryFn: ({ signal }) => api<Reservation>(`/reservations/${id}`, { signal }, true),
    staleTime: 0,
    refetchInterval: (query) => (query.state.data?.status === 'PENDING' ? 5_000 : false),
  })

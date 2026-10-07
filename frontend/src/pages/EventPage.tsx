import { useNow } from '../lib/useNow'
import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowUpRight, CalendarDays, Clock3, MapPin, Minus, Plus, Ticket } from 'lucide-react'
import { Link, useNavigate, useParams } from 'react-router'
import { useAuth } from '../auth/useAuth'
import { api, ApiError } from '../lib/api'
import { availabilityQuery, eventQuery } from '../lib/queries'
import { clearReservationKey, rememberBooking, reservationKey } from '../lib/bookings'
import { date, money, saleIsOpen } from '../lib/format'
import { quantitySchema } from '../lib/validation'
import type { Reservation } from '../lib/types'
import { BackLink, ErrorAlert, PageTitle, Spinner, StatusBadge } from '../components/ui'
import { Poster } from '../components/Poster'

export function EventPage() {
  const { eventId = '' } = useParams()
  const event = useQuery(eventQuery(eventId))
  const availability = useQuery(availabilityQuery(eventId))
  const { user, isLoading } = useAuth()
  const [quantity, setQuantity] = useState(1)
  const now = useNow()
  const navigate = useNavigate()
  const client = useQueryClient()
  const reserve = useMutation({
    mutationFn: async () => {
      if (!user) throw new Error('Sign in to reserve tickets.')
      const validated = quantitySchema.parse({ quantity })
      return api<Reservation>(
        `/events/${eventId}/reservations`,
        {
          method: 'POST',
          headers: { 'Idempotency-Key': reservationKey(user.id, eventId, quantity) },
          body: JSON.stringify(validated),
        },
        true,
      )
    },
    onSuccess: (reservation) => {
      if (!user) return
      rememberBooking(user.id, reservation.id)
      clearReservationKey(user.id, eventId, quantity)
      client.setQueryData(['reservation', user.id, reservation.id], reservation)
      void client.invalidateQueries({ queryKey: ['availability', eventId] })
      navigate(`/reservations/${reservation.id}`)
    },
    onError: (error) => {
      // Retain the key whenever the server may still have committed the hold.
      if (
        user &&
        error instanceof ApiError &&
        error.status >= 400 &&
        error.status < 500 &&
        ![401, 429].includes(error.status) &&
        error.code !== 'IDEMPOTENCY_IN_PROGRESS'
      ) {
        clearReservationKey(user.id, eventId, quantity)
      }
      void client.invalidateQueries({ queryKey: ['availability', eventId] })
      void client.invalidateQueries({ queryKey: ['event', eventId] })
    },
  })
  if (event.isPending)
    return (
      <div className="shell page-space">
        <Spinner label="Loading event…" />
      </div>
    )
  if (!event.data)
    return (
      <div className="shell page-space">
        <BackLink />
        <ErrorAlert
          error={event.error}
          retry={() => {
            void event.refetch()
          }}
        />
      </div>
    )
  const data = event.data
  const open = saleIsOpen(data, now)
  const remaining = availability.data?.available
  const max = Math.min(4, remaining ?? 4)
  return (
    <div className="shell page-space">
      <PageTitle title={data.name} />
      <BackLink />
      <div className="detail-grid">
        <div>
          <StatusBadge status={data.status} />
          <h1 className="detail-title">{data.name}</h1>
          <div className="event-meta">
            <span>
              <MapPin size={18} />
              {data.venue}
            </span>
            <span>
              <CalendarDays size={18} />
              {date(data.startsAt, true)}
            </span>
          </div>
          <Poster id={data.id} name={data.name} large />
          <div className="event-facts">
            <div>
              <span className="eyebrow">THE VENUE</span>
              <h3>{data.venue}</h3>
            </div>
            <div>
              <span className="eyebrow">TICKET SALES</span>
              <p>
                Open {date(data.saleStartsAt, true)}
                <br />
                {data.saleEndsAt
                  ? `Close ${date(data.saleEndsAt, true)}`
                  : 'No closing date announced'}
              </p>
            </div>
          </div>
        </div>
        <aside className="booking-panel">
          <span className="eyebrow">YOUR PLACE IN THE CROWD</span>
          <h2>Make it a date.</h2>
          <div className="booking-price">
            {money(data.priceMinor, data.currency)}
            <span>per ticket</span>
          </div>
          <div className="availability-line">
            <span className="live-dot" />
            <span>
              {availability.isPending
                ? 'Checking availability…'
                : remaining !== undefined
                  ? `${remaining.toLocaleString()} tickets currently available`
                  : 'Availability is unavailable'}
            </span>
          </div>
          <ErrorAlert
            error={availability.error}
            retry={() => {
              void availability.refetch()
            }}
          />
          <p className="field-hint">
            Availability can change. Your tickets are secured when your hold is confirmed.
          </p>
          {open ? (
            <>
              <div className="quantity-row">
                <label id="quantity-label">Tickets</label>
                <div className="quantity-control">
                  <button
                    aria-label="Remove one ticket"
                    disabled={quantity <= 1 || reserve.isPending}
                    onClick={() => {
                      setQuantity(quantity - 1)
                      reserve.reset()
                    }}
                  >
                    <Minus size={16} />
                  </button>
                  <output aria-labelledby="quantity-label" aria-live="polite">
                    {quantity}
                  </output>
                  <button
                    aria-label="Add one ticket"
                    disabled={quantity >= max || reserve.isPending}
                    onClick={() => {
                      setQuantity(quantity + 1)
                      reserve.reset()
                    }}
                  >
                    <Plus size={16} />
                  </button>
                </div>
              </div>
              <p className="field-hint">Up to 4 tickets per person, per event.</p>
              <div className="total-row">
                <span>Total</span>
                <strong>{money(data.priceMinor * quantity, data.currency)}</strong>
              </div>
              <ErrorAlert error={reserve.error} />
              {user?.role === 'ADMIN' ? (
                <p className="info-note">
                  You are signed in as an administrator.{' '}
                  <Link to={`/admin/events/${eventId}`} className="underline">
                    Manage this event
                  </Link>
                  .
                </p>
              ) : !user && !isLoading ? (
                <Link
                  className="button button-orange w-full"
                  to={`/login?redirect=${encodeURIComponent(`/events/${eventId}`)}`}
                >
                  Sign in to reserve <ArrowUpRight size={18} />
                </Link>
              ) : (
                <button
                  className="button button-orange w-full"
                  disabled={reserve.isPending || isLoading || max < quantity}
                  onClick={() => reserve.mutate()}
                >
                  {reserve.isPending ? (
                    <Spinner label="Reserving…" />
                  ) : (
                    <>
                      <Ticket size={18} />
                      Reserve {quantity === 1 ? 'a ticket' : `${quantity} tickets`}
                    </>
                  )}
                </button>
              )}
              <div className="booking-hint">
                <Clock3 size={16} />
                <span>Your hold has a short expiry. Confirm it on the next step.</span>
              </div>
            </>
          ) : (
            <div className="info-note">
              {data.status === 'SCHEDULED' || now < Date.parse(data.saleStartsAt)
                ? `Tickets go on sale ${date(data.saleStartsAt, true)}.`
                : 'Tickets are not currently on sale for this event.'}
            </div>
          )}
          <div className="booking-footnote">One good night. One simple booking.</div>
        </aside>
      </div>
    </div>
  )
}

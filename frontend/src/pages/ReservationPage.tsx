import { useNow } from '../lib/useNow'
import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ArrowUpRight,
  CalendarDays,
  Check,
  CheckCircle2,
  Clock3,
  Copy,
  MapPin,
  Ticket,
} from 'lucide-react'
import { Link, useParams } from 'react-router'
import { useAuth } from '../auth/useAuth'
import { api } from '../lib/api'
import { eventQuery, reservationQuery } from '../lib/queries'
import { rememberBooking } from '../lib/bookings'
import { date, money } from '../lib/format'
import type { Confirmation, Order, Reservation } from '../lib/types'
import { BackLink, ErrorAlert, PageTitle, Spinner, StatusBadge } from '../components/ui'

export function ReservationPage() {
  const { reservationId = '' } = useParams()
  const { user } = useAuth()
  const client = useQueryClient()
  const now = useNow()
  const [askCancel, setAskCancel] = useState(false)
  const [copied, setCopied] = useState(false)
  const [copyError, setCopyError] = useState<Error | null>(null)
  const reservation = useQuery({
    ...reservationQuery(reservationId, user?.id || ''),
    enabled: !!user,
  })
  const event = useQuery({
    ...eventQuery(reservation.data?.eventId || ''),
    enabled: !!reservation.data,
  })
  const order = useQuery<Order | null>({
    queryKey: ['order', user?.id, reservationId],
    queryFn: () => null,
    enabled: false,
    staleTime: Infinity,
  })
  useEffect(() => {
    if (reservation.data && user && reservation.data.userId === user.id)
      rememberBooking(user.id, reservationId)
  }, [reservation.data, user, reservationId])
  function update(data: Reservation) {
    client.setQueryData(['reservation', user?.id, reservationId], data)
    void client.invalidateQueries({ queryKey: ['availability', data.eventId] })
  }
  const confirm = useMutation({
    mutationFn: () =>
      api<Confirmation>(
        `/reservations/${reservationId}/confirm`,
        { method: 'POST', body: JSON.stringify({ paymentToken: 'tok_ok' }) },
        true,
      ),
    onSuccess: (data) => {
      update(data.reservation)
      client.setQueryData(['order', user?.id, reservationId], data.order)
    },
    onError: () => {
      void reservation.refetch()
    },
  })
  const cancel = useMutation({
    mutationFn: () =>
      api<Reservation>(`/reservations/${reservationId}`, { method: 'DELETE' }, true),
    onSuccess: (data) => {
      update(data)
      setAskCancel(false)
    },
    onError: () => {
      void reservation.refetch()
    },
  })
  if (reservation.isPending)
    return (
      <div className="shell page-space">
        <Spinner label="Finding your reservation…" />
      </div>
    )
  if (!reservation.data)
    return (
      <div className="shell page-space">
        <BackLink to="/tickets">My tickets</BackLink>
        <ErrorAlert
          error={reservation.error}
          retry={() => {
            void reservation.refetch()
          }}
        />
      </div>
    )
  const data = reservation.data
  const seconds = Math.max(0, Math.ceil((Date.parse(data.expiresAt) - now) / 1000))
  const pending = data.status === 'PENDING'
  const active = pending && seconds > 0
  const confirmed = data.status === 'CONFIRMED'
  const busy = confirm.isPending || cancel.isPending
  const owned = data.userId === user?.id && user?.role === 'USER'
  return (
    <div className="shell page-space reservation-page">
      <PageTitle title={confirmed ? 'Booking confirmed' : 'Your reservation'} />
      <BackLink to="/tickets">My tickets</BackLink>
      <div className="reservation-heading">
        <span className="eyebrow">
          {confirmed ? 'YOU’RE GOING' : pending ? 'A LITTLE CLOSER TO LIVE' : 'YOUR RESERVATION'}
        </span>
        <h1>
          {confirmed
            ? 'See you in the crowd.'
            : active
              ? 'Your tickets are on hold.'
              : 'Here’s where things stand.'}
        </h1>
        <p>
          {confirmed
            ? 'Your booking is confirmed. Keep your reservation reference handy.'
            : active
              ? 'Confirm your booking before your hold expires.'
              : pending
                ? 'Your hold time has ended. We are checking the final status.'
                : data.status === 'EXPIRED'
                  ? 'Your hold expired and the tickets were released.'
                  : 'Your tickets have been released.'}
        </p>
      </div>
      <ErrorAlert
        error={reservation.error}
        retry={() => {
          void reservation.refetch()
        }}
      />
      <div className="reservation-grid">
        <div className="ticket-stub">
          <div className="ticket-stub-main">
            <div className="flex items-center justify-between gap-3">
              <span className="eyebrow">ENCORE / ADMIT {data.quantity}</span>
              <StatusBadge status={data.status} />
            </div>
            <h2>{event.data?.name || 'Your event'}</h2>
            <ErrorAlert
              error={event.error}
              retry={() => {
                void event.refetch()
              }}
            />
            <div className="event-meta flex-col items-start">
              <span>
                <MapPin size={17} />
                {event.data?.venue || 'Loading venue…'}
              </span>
              <span>
                <CalendarDays size={17} />
                {date(event.data?.startsAt || null, true)}
              </span>
            </div>
            <div className="ticket-stub-details">
              <div>
                <span>Tickets</span>
                <strong>{data.quantity} × general admission</strong>
              </div>
              <div>
                <span>Booked</span>
                <strong>{date(data.createdAt)}</strong>
              </div>
            </div>
          </div>
          <div className="ticket-stub-bottom">
            <div>
              <span className="eyebrow">RESERVATION REFERENCE</span>
              <code>{data.id}</code>
            </div>
            <button
              className="icon-button"
              aria-label="Copy reservation reference"
              onClick={async () => {
                try {
                  await navigator.clipboard.writeText(data.id)
                  setCopied(true)
                  setCopyError(null)
                } catch {
                  setCopyError(
                    new Error(
                      'Could not copy. Select the reservation reference and copy it manually.',
                    ),
                  )
                }
              }}
            >
              {copied ? <Check size={19} /> : <Copy size={19} />}
            </button>
          </div>
          {copied && (
            <p className="px-7 pb-3 text-xs" role="status">
              Reference copied.
            </p>
          )}
        </div>
        <aside className="booking-panel reservation-panel">
          {confirmed ? (
            <>
              <CheckCircle2 size={38} strokeWidth={1.3} className="text-green-800" />
              <h2>You’re all set.</h2>
              <p>
                {data.quantity} ticket{data.quantity === 1 ? '' : 's'} confirmed.
              </p>
              {order.data && (
                <>
                  <div className="total-row">
                    <span>Total confirmed</span>
                    <strong>{money(order.data.amountMinor, order.data.currency)}</strong>
                  </div>
                  <p className="field-hint break-all">Order reference: {order.data.id}</p>
                </>
              )}
              <Link to="/" className="button button-dark w-full">
                Explore more events <ArrowUpRight size={17} />
              </Link>
            </>
          ) : pending ? (
            <>
              <div className={`countdown ${seconds < 60 ? 'countdown-urgent' : ''}`}>
                <Clock3 size={22} />
                <div>
                  <span>Hold expires in</span>
                  <strong role="timer" aria-label="Time remaining on your hold">
                    {Math.floor(seconds / 60)}:{String(seconds % 60).padStart(2, '0')}
                  </strong>
                </div>
              </div>
              <h2>Make it official.</h2>
              <div className="total-row">
                <span>
                  {data.quantity} ticket{data.quantity === 1 ? '' : 's'}
                </span>
                <strong>
                  {event.data
                    ? money(event.data.priceMinor * data.quantity, event.data.currency)
                    : '…'}
                </strong>
              </div>
              <p className="field-hint">
                This system uses a simulated payment. No card details are collected.
              </p>
              <ErrorAlert error={confirm.error || cancel.error} />
              {owned && (
                <>
                  <button
                    className="button button-orange w-full"
                    disabled={!active || busy || !event.data || reservation.isError}
                    onClick={() => confirm.mutate()}
                  >
                    {confirm.isPending ? (
                      <Spinner label="Confirming…" />
                    ) : (
                      <>
                        <Ticket size={17} />
                        Confirm booking
                      </>
                    )}
                  </button>
                  {askCancel ? (
                    <div className="cancel-prompt">
                      <p>Release these tickets? You’ll need a new reservation to book again.</p>
                      <div className="flex gap-3">
                        <button
                          className="text-link"
                          disabled={busy}
                          onClick={() => setAskCancel(false)}
                        >
                          Keep tickets
                        </button>
                        <button
                          className="text-link text-red-800"
                          disabled={busy || !active}
                          onClick={() => cancel.mutate()}
                        >
                          {cancel.isPending ? 'Releasing…' : 'Release tickets'}
                        </button>
                      </div>
                    </div>
                  ) : (
                    <button
                      className="cancel-link"
                      disabled={busy || !active}
                      onClick={() => setAskCancel(true)}
                    >
                      Release my hold
                    </button>
                  )}
                </>
              )}
              {!owned && (
                <p className="info-note">
                  This is a support view. Only the reservation owner can confirm or release tickets.
                </p>
              )}
              {!active && (
                <Link className="text-link" to={`/events/${data.eventId}`}>
                  Check tickets for this event <ArrowUpRight size={16} />
                </Link>
              )}
            </>
          ) : (
            <>
              <Ticket size={32} strokeWidth={1.3} />
              <h2>{data.status === 'EXPIRED' ? 'Another chance?' : 'Plans change.'}</h2>
              <p>You can check current availability and start a new booking.</p>
              <Link to={`/events/${data.eventId}`} className="button button-dark w-full">
                Back to the event <ArrowUpRight size={17} />
              </Link>
            </>
          )}
        </aside>
      </div>
      <ErrorAlert error={copyError} />
    </div>
  )
}

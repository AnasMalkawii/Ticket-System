import { useState, type FormEvent } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ArrowRight, ArrowUpRight, CalendarDays, Ticket } from 'lucide-react'
import { Link, useNavigate } from 'react-router'
import { useAuth } from '../auth/useAuth'
import { readBookings } from '../lib/bookings'
import { eventQuery, reservationQuery } from '../lib/queries'
import { date } from '../lib/format'
import { reservationIdSchema } from '../lib/validation'
import { EmptyState, Field, PageTitle, Spinner, StatusBadge } from '../components/ui'

export function TicketsPage() {
  const { user } = useAuth()
  const [lookupError, setLookupError] = useState<string>()
  const navigate = useNavigate()
  const ids = user ? readBookings(user.id) : []
  function lookup(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const result = reservationIdSchema.safeParse(
      new FormData(event.currentTarget).get('reservationId'),
    )
    if (!result.success) {
      setLookupError(result.error.issues[0].message)
      return
    }
    navigate(`/reservations/${result.data}`)
  }
  return (
    <div className="shell page-space">
      <PageTitle title="My tickets" />
      <div className="page-heading">
        <div>
          <span className="eyebrow">YOUR PLANS, ALL HERE</span>
          <h1>My tickets.</h1>
          <p>Good nights worth looking forward to.</p>
        </div>
        <Link to="/" className="button button-outline">
          Explore events <ArrowUpRight size={17} />
        </Link>
      </div>
      <p className="browser-note">
        Reservations made or looked up on this browser appear here. Have a booking from another
        device? Find it using its reservation ID below.
      </p>
      <div className="tickets-layout">
        <section aria-label="Your reservations" className="space-y-4">
          {ids.length === 0 ? (
            <EmptyState
              title="Your next great night is waiting."
              action={
                <Link to="/" className="button button-dark">
                  Find an event <ArrowRight size={16} />
                </Link>
              }
            >
              You haven’t saved any reservations on this browser yet.
            </EmptyState>
          ) : (
            ids.map((id) => <BookingRow key={id} id={id} userId={user!.id} />)
          )}
        </section>
        <aside className="lookup-panel">
          <Ticket size={25} strokeWidth={1.4} />
          <h2>Have a reference?</h2>
          <p>Find a reservation using the ID you received when you booked.</p>
          <form onSubmit={lookup} noValidate>
            <Field
              label="Reservation ID"
              name="reservationId"
              placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
              error={lookupError}
            />
            <button className="button button-dark w-full mt-4">
              Find reservation <ArrowRight size={16} />
            </button>
          </form>
        </aside>
      </div>
    </div>
  )
}

function BookingRow({ id, userId }: { id: string; userId: string }) {
  const reservation = useQuery(reservationQuery(id, userId))
  const event = useQuery({
    ...eventQuery(reservation.data?.eventId || ''),
    enabled: !!reservation.data,
  })
  if (reservation.isPending)
    return (
      <div className="booking-row">
        <Spinner label="Loading reservation…" />
      </div>
    )
  if (!reservation.data)
    return (
      <Link className="booking-row" to={`/reservations/${id}`}>
        <div>
          <h3>Reservation unavailable</h3>
          <p>{reservation.error?.message}</p>
          <code className="text-xs break-all">{id}</code>
        </div>
        <ArrowUpRight size={20} />
      </Link>
    )
  return (
    <Link className="booking-row" to={`/reservations/${id}`}>
      <div className="booking-row-icon">
        <Ticket size={27} strokeWidth={1.4} />
      </div>
      <div className="min-w-0 flex-1">
        <StatusBadge status={reservation.data.status} />
        <h3>{event.data?.name || 'Your event'}</h3>
        <p>
          <CalendarDays size={14} />
          {date(event.data?.startsAt || null)}
          <span>·</span>
          {reservation.data.quantity} ticket{reservation.data.quantity === 1 ? '' : 's'}
        </p>
      </div>
      <ArrowUpRight size={23} className="shrink-0" />
    </Link>
  )
}

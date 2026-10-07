import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowUpRight, Check, Plus } from 'lucide-react'
import { Link, useNavigate, useParams } from 'react-router'
import { z } from 'zod'
import { api } from '../lib/api'
import { availabilityQuery, eventQuery } from '../lib/queries'
import { currencyDigits, localDatetime, money, statusLabels } from '../lib/format'
import { eventFormSchema, fieldErrors } from '../lib/validation'
import { eventStatuses, type EventInput, type TicketEvent } from '../lib/types'
import { BackLink, ErrorAlert, Field, PageTitle, Spinner } from '../components/ui'

export function EventFormPage() {
  const { eventId } = useParams()
  const event = useQuery({ ...eventQuery(eventId || ''), enabled: !!eventId })
  if (eventId && event.isPending)
    return (
      <div className="shell page-space">
        <Spinner label="Loading event details…" />
      </div>
    )
  if (eventId && !event.data)
    return (
      <div className="shell page-space">
        <BackLink to="/admin">Manage events</BackLink>
        <ErrorAlert
          error={event.error}
          retry={() => {
            void event.refetch()
          }}
        />
      </div>
    )
  return <EventForm key={eventId || 'new'} event={event.data} />
}

function EventForm({ event }: { event?: TicketEvent }) {
  const [errors, setErrors] = useState<Record<string, string>>({})
  const client = useQueryClient()
  const navigate = useNavigate()
  const save = useMutation({
    mutationFn: (input: EventInput & { totalTickets?: number; status?: string }) =>
      api<TicketEvent>(
        event ? `/admin/events/${event.id}` : '/admin/events',
        { method: event ? 'PATCH' : 'POST', body: JSON.stringify(input) },
        true,
      ),
    onSuccess: (saved) => {
      client.setQueryData(['event', saved.id], saved)
      void client.invalidateQueries({ queryKey: ['events'] })
      if (!event) navigate(`/admin/events/${saved.id}`, { replace: true })
    },
  })
  function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    save.reset()
    const validated = eventFormSchema.safeParse(Object.fromEntries(new FormData(e.currentTarget)))
    if (!validated.success) {
      const next = fieldErrors(validated.error)
      setErrors(next)
      e.currentTarget.querySelector<HTMLInputElement>(`[name="${Object.keys(next)[0]}"]`)?.focus()
      return
    }
    setErrors({})
    const value = validated.data
    save.mutate({
      name: value.name,
      venue: value.venue,
      ...(value.startsAt ? { startsAt: new Date(value.startsAt).toISOString() } : {}),
      saleStartsAt: new Date(value.saleStartsAt).toISOString(),
      ...(value.saleEndsAt ? { saleEndsAt: new Date(value.saleEndsAt).toISOString() } : {}),
      priceMinor: Math.round(Number(value.price) * 10 ** currencyDigits(value.currency)),
      currency: value.currency,
      ...(event ? { status: value.status } : { totalTickets: value.totalTickets }),
    })
  }
  const currencies = [
    ...new Set(['EUR', 'USD', 'GBP', 'JOD', 'JPY', ...(event ? [event.currency] : [])]),
  ]
  return (
    <div className="shell page-space">
      <PageTitle title={event ? 'Edit event' : 'Create event'} />
      <BackLink to="/admin">Manage events</BackLink>
      <div className="page-heading">
        <div>
          <span className="eyebrow">THE ORGANIZER’S DESK</span>
          <h1>{event ? 'Set the stage.' : 'Something to look forward to.'}</h1>
          <p>
            {event
              ? 'Update the details and keep your audience in the know.'
              : 'Add an event to the lineup. You can open ticket sales after creating it.'}
          </p>
        </div>
        {event && (
          <Link to={`/events/${event.id}`} className="text-link">
            View event <ArrowUpRight size={17} />
          </Link>
        )}
      </div>
      <div className="admin-form-layout">
        <form onSubmit={submit} noValidate className="form-card">
          <fieldset disabled={save.isPending}>
            <legend>Event details</legend>
            <div className="form-stack">
              <Field
                label="Event name"
                name="name"
                placeholder="A name worth remembering"
                defaultValue={event?.name}
                error={errors.name}
                maxLength={200}
              />
              <Field
                label="Venue"
                name="venue"
                placeholder="Where it all happens"
                defaultValue={event?.venue}
                error={errors.venue}
                maxLength={200}
              />
              <Field
                label="Event date and time"
                name="startsAt"
                type="datetime-local"
                defaultValue={localDatetime(event?.startsAt || null)}
                error={errors.startsAt}
                hint="Optional. Times are in your device’s local time zone."
              />
            </div>
          </fieldset>
          <fieldset disabled={save.isPending}>
            <legend>Tickets & sales</legend>
            <div className="form-grid">
              <Field
                label="Sales open"
                name="saleStartsAt"
                type="datetime-local"
                defaultValue={localDatetime(event?.saleStartsAt || null)}
                error={errors.saleStartsAt}
              />
              <Field
                label="Sales close"
                name="saleEndsAt"
                type="datetime-local"
                defaultValue={localDatetime(event?.saleEndsAt || null)}
                error={errors.saleEndsAt}
                hint="Optional."
              />
              <Field
                label="Price per ticket"
                name="price"
                inputMode="decimal"
                placeholder="45.00"
                defaultValue={
                  event ? String(event.priceMinor / 10 ** currencyDigits(event.currency)) : ''
                }
                error={errors.price}
              />
              <div className="field">
                <label htmlFor="currency">Currency</label>
                <select id="currency" name="currency" defaultValue={event?.currency || 'EUR'}>
                  {currencies.map((currency) => (
                    <option key={currency}>{currency}</option>
                  ))}
                </select>
              </div>
              {event ? (
                <>
                  <input type="hidden" name="totalTickets" value="1" />
                  <div className="field">
                    <label htmlFor="status">Sale status</label>
                    <select id="status" name="status" defaultValue={event.status}>
                      {eventStatuses.map((status) => (
                        <option value={status} key={status}>
                          {statusLabels[status]}
                        </option>
                      ))}
                    </select>
                    <p className="field-hint">
                      Sales must also be within the opening and closing dates.
                    </p>
                  </div>
                </>
              ) : (
                <>
                  <input type="hidden" name="status" value="SCHEDULED" />
                  <Field
                    label="Total tickets"
                    name="totalTickets"
                    type="number"
                    min="1"
                    step="1"
                    placeholder="100"
                    error={errors.totalTickets}
                  />
                </>
              )}
            </div>
          </fieldset>
          <ErrorAlert error={save.error} />
          {save.isSuccess && (
            <p className="success-note flex items-center gap-2" role="status">
              <Check size={17} />
              Your event details have been saved.
            </p>
          )}
          <div className="form-actions">
            <Link to="/admin" className="text-link">
              Back to events
            </Link>
            <button className="button button-orange" disabled={save.isPending}>
              {save.isPending ? (
                <Spinner label="Saving…" />
              ) : event ? (
                'Save changes'
              ) : (
                'Create event'
              )}
              <ArrowUpRight size={16} />
            </button>
          </div>
        </form>
        <aside>
          {event ? (
            <InventoryPanel event={event} />
          ) : (
            <div className="organizer-note">
              <span className="eyebrow">A LITTLE HEADS-UP</span>
              <h2>Ready when you are.</h2>
              <p>
                New events start as “Coming soon.” After you save, switch the sale status to “On
                sale” when you’re ready to welcome your audience.
              </p>
              <p>You can add more tickets later.</p>
            </div>
          )}
        </aside>
      </div>
    </div>
  )
}

function InventoryPanel({ event }: { event: TicketEvent }) {
  const inventory = useQuery(availabilityQuery(event.id))
  const client = useQueryClient()
  const [error, setError] = useState<string>()
  const add = useMutation({
    mutationFn: (addTickets: number) =>
      api<TicketEvent>(
        `/admin/events/${event.id}`,
        { method: 'PATCH', body: JSON.stringify({ addTickets }) },
        true,
      ),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['availability', event.id] })
      void client.invalidateQueries({ queryKey: ['events'] })
      void client.invalidateQueries({ queryKey: ['event', event.id] })
    },
  })
  function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    add.reset()
    const result = z.coerce
      .number()
      .int()
      .min(1, 'Add at least one ticket.')
      .max(2_147_483_647)
      .safeParse(new FormData(e.currentTarget).get('addTickets'))
    if (!result.success) {
      setError(result.error.issues[0].message)
      return
    }
    setError(undefined)
    add.mutate(result.data)
  }
  return (
    <div className="inventory-panel">
      <span className="eyebrow">TICKET INVENTORY</span>
      <h2>Room for more.</h2>
      <dl>
        <div>
          <dt>Total tickets</dt>
          <dd>{inventory.data?.total.toLocaleString() ?? '—'}</dd>
        </div>
        <div>
          <dt>Available now</dt>
          <dd>{inventory.data?.available.toLocaleString() ?? '—'}</dd>
        </div>
        <div>
          <dt>Ticket price</dt>
          <dd>{money(event.priceMinor, event.currency)}</dd>
        </div>
      </dl>
      <ErrorAlert
        error={inventory.error}
        retry={() => {
          void inventory.refetch()
        }}
      />
      <form onSubmit={submit} noValidate>
        <Field
          label="Additional tickets"
          name="addTickets"
          type="number"
          min="1"
          step="1"
          placeholder="e.g. 50"
          error={error}
          disabled={add.isPending}
        />
        <ErrorAlert error={add.error} />
        {add.isSuccess && (
          <p role="status" className="success-note">
            Tickets added successfully.
          </p>
        )}
        <button className="button button-dark w-full mt-4" disabled={add.isPending}>
          {add.isPending ? (
            <Spinner label="Adding…" />
          ) : (
            <>
              <Plus size={17} />
              Add tickets
            </>
          )}
        </button>
      </form>
      <p className="field-hint mt-4">
        This adds to the existing inventory. Availability is an estimate and can change with
        bookings.
      </p>
    </div>
  )
}

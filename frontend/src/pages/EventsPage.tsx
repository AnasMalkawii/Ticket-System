import { useQuery } from '@tanstack/react-query'
import { ArrowDown, ArrowRight, ArrowUpRight, CalendarDays, MapPin } from 'lucide-react'
import { Link, useSearchParams } from 'react-router'
import { eventsQuery } from '../lib/queries'
import { date, money } from '../lib/format'
import { eventStatuses, type EventStatus } from '../lib/types'
import { EmptyState, ErrorAlert, PageTitle, StatusBadge } from '../components/ui'
import { Poster } from '../components/Poster'

const filters: { label: string; status?: EventStatus }[] = [
  { label: 'All events' },
  { label: 'On sale', status: 'ON_SALE' },
  { label: 'Coming soon', status: 'SCHEDULED' },
  { label: 'Sold out', status: 'SOLD_OUT' },
  { label: 'Sales closed', status: 'CLOSED' },
]

export function EventsPage() {
  const [params, setParams] = useSearchParams()
  const rawStatus = params.get('status')
  const status = eventStatuses.find((item) => item === rawStatus)
  const rawPage = Number(params.get('page') || 0)
  const page = Number.isInteger(rawPage) && rawPage >= 0 ? rawPage : 0
  const events = useQuery(eventsQuery(page, status))
  function update(nextPage: number, nextStatus?: EventStatus) {
    setParams({
      ...(nextStatus ? { status: nextStatus } : {}),
      ...(nextPage ? { page: String(nextPage) } : {}),
    })
  }
  return (
    <>
      <PageTitle title="Explore events" />
      <section className="shell hero">
        <div className="hero-copy">
          <span className="eyebrow">
            <span className="live-dot" />
            YOUR NEXT GREAT NIGHT
          </span>
          <h1>
            Some things
            <br />
            you have to
            <br />
            <em>be there for.</em>
          </h1>
          <p>
            The lights go down. The crowd comes alive.
            <br className="hidden sm:block" /> Find your next event and make it a night to remember.
          </p>
          <a href="#events" className="button button-orange">
            Find your next event <ArrowUpRight size={19} />
          </a>
          <div className="hero-note">
            <span className="tiny-line" />
            Real moments. A ticket away.
          </div>
        </div>
        <div className="hero-art" aria-hidden="true">
          <div className="hero-art-header">
            <span>
              GOOD NIGHTS
              <br />
              START HERE.
            </span>
            <ArrowUpRight size={30} strokeWidth={1.2} />
          </div>
          <div className="record">
            <div className="record-label">
              <span>encore.</span>
              <span>LIVE / SIDE A</span>
              <i />
            </div>
          </div>
          <div className="hero-art-type">
            GO
            <br />
            <span>LIVE.</span>
          </div>
          <div className="hero-art-footer">
            <span>TURN UP. TUNE IN.</span>
            <span>↗</span>
          </div>
        </div>
      </section>
      <div className="manifesto-strip">
        <div className="shell flex items-center justify-between gap-4">
          <span>FOR THE MOMENTS THAT STAY WITH YOU.</span>
          <span className="hidden sm:inline">
            YOUR PLACE IN THE CROWD <ArrowDown size={14} className="ml-3 inline" />
          </span>
        </div>
      </div>
      <section id="events" className="shell events-section">
        <div className="section-heading">
          <div>
            <span className="eyebrow">THE LINEUP</span>
            <h2>Find your kind of live.</h2>
          </div>
          <span className="result-count">
            {events.data
              ? `${events.data.totalElements} event${events.data.totalElements === 1 ? '' : 's'}`
              : 'Explore the calendar'}
          </span>
        </div>
        <div className="filter-tabs" aria-label="Filter events">
          {filters.map((filter) => (
            <button
              key={filter.label}
              onClick={() => update(0, filter.status)}
              aria-pressed={status === filter.status}
              className={status === filter.status ? 'selected' : ''}
            >
              {filter.label}
            </button>
          ))}
        </div>
        {events.isPending && (
          <div className="event-grid" aria-label="Loading events" role="status">
            {[0, 1, 2].map((item) => (
              <div className="event-skeleton" key={item}>
                <div />
                <div />
                <div />
              </div>
            ))}
          </div>
        )}
        <ErrorAlert
          error={events.error}
          retry={() => {
            void events.refetch()
          }}
        />
        {events.data && events.data.content.length === 0 && (
          <EmptyState
            title="The next good night is on its way."
            action={
              <button className="button button-dark" onClick={() => update(0)}>
                See all events <ArrowRight size={16} />
              </button>
            }
          >
            No events match this selection. Check the full lineup or come back soon.
          </EmptyState>
        )}
        {events.data && (
          <div className="event-grid">
            {events.data.content.map((event) => (
              <Link to={`/events/${event.id}`} key={event.id} className="event-card">
                <Poster id={event.id} name={event.name} />
                <div className="event-card-body">
                  <StatusBadge status={event.status} />
                  <h3>{event.name}</h3>
                  <p>
                    <MapPin size={14} />
                    {event.venue}
                  </p>
                  <p>
                    <CalendarDays size={14} />
                    {date(event.startsAt)}
                  </p>
                  <div className="event-card-footer">
                    <span>
                      {money(event.priceMinor, event.currency)} <small>/ ticket</small>
                    </span>
                    <span className="card-arrow">
                      <ArrowUpRight size={21} />
                    </span>
                  </div>
                </div>
              </Link>
            ))}
          </div>
        )}
        {events.data && events.data.totalPages > 1 && (
          <nav className="pagination" aria-label="Event pages">
            <button
              className="button button-outline button-small"
              disabled={page === 0 || events.isFetching}
              onClick={() => update(page - 1, status)}
            >
              Previous
            </button>
            <span>
              Page {page + 1} of {events.data.totalPages}
            </span>
            <button
              className="button button-outline button-small"
              disabled={page + 1 >= events.data.totalPages || events.isFetching}
              onClick={() => update(page + 1, status)}
            >
              Next <ArrowRight size={16} />
            </button>
          </nav>
        )}
      </section>
      <section className="shell closing-note">
        <TicketIllustration />
        <div>
          <span className="eyebrow">MAKE A PLAN. MAKE A MEMORY.</span>
          <h2>
            Your spot in the crowd
            <br />
            is waiting.
          </h2>
        </div>
        <Link to="/tickets" className="text-link">
          Already booked? Find your tickets <ArrowUpRight size={18} />
        </Link>
      </section>
    </>
  )
}

function TicketIllustration() {
  return (
    <div className="ticket-illustration" aria-hidden="true">
      <span>
        ADMIT
        <br />
        <b>YOU.</b>
      </span>
      <i />
      <span>
        ENCORE
        <br />
        |||||||||||
      </span>
    </div>
  )
}

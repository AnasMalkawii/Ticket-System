import { useQuery } from '@tanstack/react-query'
import { ArrowLeft, ArrowRight, ArrowUpRight, Plus } from 'lucide-react'
import { Link, useSearchParams } from 'react-router'
import { eventsQuery } from '../lib/queries'
import { date, money } from '../lib/format'
import { EmptyState, ErrorAlert, PageTitle, Spinner, StatusBadge } from '../components/ui'

export function AdminPage() {
  const [params, setParams] = useSearchParams()
  const rawPage = Number(params.get('page') || 0)
  const page = Number.isInteger(rawPage) && rawPage >= 0 ? rawPage : 0
  const events = useQuery(eventsQuery(page))
  return (
    <div className="shell page-space">
      <PageTitle title="Manage events" />
      <div className="page-heading">
        <div>
          <span className="eyebrow">THE ORGANIZER’S DESK</span>
          <h1>Manage events.</h1>
          <p>A good experience starts behind the scenes.</p>
        </div>
        <Link to="/admin/events/new" className="button button-orange">
          <Plus size={18} />
          Create event
        </Link>
      </div>
      <div className="admin-summary">
        <span>Event catalog</span>
        <strong>
          {events.data?.totalElements ?? '—'} <small>events</small>
        </strong>
        <p>Edit event details, manage sales, and add tickets.</p>
      </div>
      {events.isPending && <Spinner label="Loading your events…" />}
      <ErrorAlert
        error={events.error}
        retry={() => {
          void events.refetch()
        }}
      />
      {events.data?.content.length === 0 && (
        <EmptyState
          title="Start with a great event."
          action={
            <Link to="/admin/events/new" className="button button-dark">
              Create your first event <Plus size={16} />
            </Link>
          }
        >
          Your event catalog is empty.
        </EmptyState>
      )}
      {events.data && events.data.content.length > 0 && (
        <div className="table-scroll">
          <table className="events-table">
            <thead>
              <tr>
                <th scope="col">Event / venue</th>
                <th scope="col">Event date</th>
                <th scope="col">Ticket price</th>
                <th scope="col">Status</th>
                <th scope="col">
                  <span className="sr-only">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {events.data.content.map((event) => (
                <tr key={event.id}>
                  <td>
                    <Link to={`/admin/events/${event.id}`} className="table-event-name">
                      {event.name}
                    </Link>
                    <span className="table-venue">{event.venue}</span>
                  </td>
                  <td>{date(event.startsAt)}</td>
                  <td>{money(event.priceMinor, event.currency)}</td>
                  <td>
                    <StatusBadge status={event.status} />
                  </td>
                  <td>
                    <Link to={`/admin/events/${event.id}`} className="text-link">
                      Edit <ArrowUpRight size={15} />
                      <span className="sr-only"> {event.name}</span>
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {events.data && events.data.totalPages > 1 && (
        <nav className="pagination" aria-label="Event pages">
          <button
            className="button button-outline button-small"
            disabled={page === 0 || events.isFetching}
            onClick={() => setParams({ page: String(page - 1) })}
          >
            <ArrowLeft size={15} />
            Previous
          </button>
          <span>
            Page {page + 1} of {events.data.totalPages}
          </span>
          <button
            className="button button-outline button-small"
            disabled={page + 1 >= events.data.totalPages || events.isFetching}
            onClick={() => setParams({ page: String(page + 1) })}
          >
            Next <ArrowRight size={15} />
          </button>
        </nav>
      )}
    </div>
  )
}

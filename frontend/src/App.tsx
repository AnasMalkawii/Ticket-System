import { Component, type ReactNode } from 'react'
import { BrowserRouter, Link, Navigate, Outlet, Route, Routes, useLocation } from 'react-router'
import { useAuth } from './auth/useAuth'
import { Layout } from './components/Layout'
import { EmptyState, ErrorAlert, PageTitle, Spinner } from './components/ui'
import { EventsPage } from './pages/EventsPage'
import { EventPage } from './pages/EventPage'
import { AuthPage } from './pages/AuthPage'
import { TicketsPage } from './pages/TicketsPage'
import { ReservationPage } from './pages/ReservationPage'
import { AdminPage } from './pages/AdminPage'
import { EventFormPage } from './pages/EventFormPage'

function ProtectedRoute({ admin = false }: { admin?: boolean }) {
  const { user, isLoading, error, retry } = useAuth()
  const location = useLocation()
  if (isLoading)
    return (
      <div className="shell page-space">
        <Spinner label="Checking your session…" />
      </div>
    )
  if (!user && error)
    return (
      <div className="shell page-space">
        <ErrorAlert error={error} retry={retry} />
        <Link to="/login" className="button button-dark mt-5">
          Sign in
        </Link>
      </div>
    )
  if (!user)
    return (
      <Navigate
        to={`/login?redirect=${encodeURIComponent(location.pathname + location.search)}`}
        replace
      />
    )
  if (admin && user.role !== 'ADMIN')
    return (
      <div className="shell page-space">
        <EmptyState
          title="This page is for organizers."
          action={
            <Link to="/" className="button button-dark">
              Explore events
            </Link>
          }
        >
          Your account does not have access to event management.
        </EmptyState>
      </div>
    )
  return <Outlet />
}

class ErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false }
  static getDerivedStateFromError() {
    return { failed: true }
  }
  render() {
    if (this.state.failed)
      return (
        <div className="shell page-space">
          <EmptyState
            title="Something went wrong."
            action={
              <button className="button button-dark" onClick={() => window.location.reload()}>
                Reload page
              </button>
            }
          >
            Please reload the page to continue.
          </EmptyState>
        </div>
      )
    return this.props.children
  }
}

export default function App() {
  return (
    <ErrorBoundary>
      <BrowserRouter>
        <Routes>
          <Route element={<Layout />}>
            <Route index element={<EventsPage />} />
            <Route path="events/:eventId" element={<EventPage />} />
            <Route path="login" element={<AuthPage key="login" mode="login" />} />
            <Route path="register" element={<AuthPage key="register" mode="register" />} />
            <Route element={<ProtectedRoute />}>
              <Route path="tickets" element={<TicketsPage />} />
              <Route path="reservations/:reservationId" element={<ReservationPage />} />
            </Route>
            <Route element={<ProtectedRoute admin />}>
              <Route path="admin" element={<AdminPage />} />
              <Route path="admin/events/new" element={<EventFormPage />} />
              <Route path="admin/events/:eventId" element={<EventFormPage />} />
            </Route>
            <Route
              path="*"
              element={
                <div className="shell page-space">
                  <PageTitle title="Page not found" />
                  <EmptyState
                    title="You’ve wandered off the setlist."
                    action={
                      <Link to="/" className="button button-dark">
                        Back to events
                      </Link>
                    }
                  >
                    This page does not exist. The next good night is still out there.
                  </EmptyState>
                </div>
              }
            />
          </Route>
        </Routes>
      </BrowserRouter>
    </ErrorBoundary>
  )
}

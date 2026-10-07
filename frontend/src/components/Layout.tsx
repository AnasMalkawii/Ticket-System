import { useEffect, useState } from 'react'
import { ArrowUpRight, LogOut, Menu, Ticket, X } from 'lucide-react'
import { Link, NavLink, Outlet, useLocation } from 'react-router'
import { useMutation } from '@tanstack/react-query'
import { useAuth } from '../auth/useAuth'
import { ErrorAlert } from './ui'

export function Layout() {
  const { user, logout } = useAuth()
  const [menuOpen, setMenuOpen] = useState(false)
  const location = useLocation()
  const signout = useMutation({ mutationFn: logout })
  useEffect(() => {
    window.scrollTo(0, 0)
    document.getElementById('main-content')?.focus({ preventScroll: true })
  }, [location.pathname])
  return (
    <>
      <a href="#main-content" className="skip-link">
        Skip to content
      </a>
      <header className="site-header">
        <div className="shell header-inner">
          <Link to="/" className="brand" aria-label="Encore home">
            <span className="brand-mark">
              <Ticket size={23} strokeWidth={1.8} />
            </span>
            encore<span className="brand-period">.</span>
          </Link>
          <button
            className="mobile-menu"
            aria-label={menuOpen ? 'Close navigation' : 'Open navigation'}
            aria-expanded={menuOpen}
            onClick={() => setMenuOpen(!menuOpen)}
          >
            {menuOpen ? <X /> : <Menu />}
          </button>
          <nav
            aria-label="Main navigation"
            className={`header-nav ${menuOpen ? 'is-open' : ''}`}
            onClick={() => setMenuOpen(false)}
          >
            <NavLink to="/" end>
              Explore events
            </NavLink>
            {user && <NavLink to="/tickets">My tickets</NavLink>}
            {user?.role === 'ADMIN' && <NavLink to="/admin">Manage events</NavLink>}
            <div className="header-account">
              {user ? (
                <>
                  <span className="account-name">{user.username}</span>
                  <button
                    onClick={() => signout.mutate()}
                    disabled={signout.isPending}
                    className="icon-button"
                    aria-label="Sign out"
                  >
                    <LogOut size={18} />
                  </button>
                </>
              ) : (
                <>
                  <NavLink to="/login">Sign in</NavLink>
                  <Link className="button button-dark button-small" to="/register">
                    Create account <ArrowUpRight size={15} />
                  </Link>
                </>
              )}
            </div>
          </nav>
        </div>
      </header>
      {signout.error && (
        <div className="shell pt-5">
          <ErrorAlert error={signout.error} retry={() => signout.mutate()} />
        </div>
      )}
      <main id="main-content" tabIndex={-1}>
        <Outlet />
      </main>
      <footer className="site-footer">
        <div className="shell footer-inner">
          <Link to="/" className="brand">
            encore<span className="brand-period">.</span>
          </Link>
          <p>Less scrolling. More being there.</p>
          <span>THE TICKET SYSTEM</span>
        </div>
      </footer>
    </>
  )
}

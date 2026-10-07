import { useState, type FormEvent } from 'react'
import { useMutation } from '@tanstack/react-query'
import { ArrowUpRight, Eye, EyeOff } from 'lucide-react'
import { Link, Navigate, useLocation, useNavigate, useSearchParams } from 'react-router'
import { useAuth } from '../auth/useAuth'
import { api } from '../lib/api'
import { fieldErrors, loginSchema, registerSchema } from '../lib/validation'
import { ErrorAlert, Field, PageTitle, Spinner } from '../components/ui'

export function AuthPage({ mode }: { mode: 'login' | 'register' }) {
  const registering = mode === 'register'
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [showPassword, setShowPassword] = useState(false)
  const { login, user } = useAuth()
  const [params] = useSearchParams()
  const location = useLocation()
  const navigate = useNavigate()
  const rawRedirect = params.get('redirect') || '/tickets'
  const redirect =
    rawRedirect.startsWith('/') && !rawRedirect.startsWith('//') && !rawRedirect.includes('\\')
      ? rawRedirect
      : '/tickets'
  const notice = (location.state as { notice?: string } | null)?.notice
  const submit = useMutation({
    mutationFn: async (credentials: { username: string; password: string }) => {
      if (registering) {
        await api('/auth/register', { method: 'POST', body: JSON.stringify(credentials) })
        navigate(`/login?redirect=${encodeURIComponent(redirect)}`, {
          replace: true,
          state: { notice: 'Your account is ready. Sign in to find your next great night.' },
        })
      } else {
        const account = await login(credentials)
        navigate(account.role === 'ADMIN' && redirect === '/tickets' ? '/admin' : redirect, {
          replace: true,
        })
      }
    },
  })
  function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    submit.reset()
    const result = (registering ? registerSchema : loginSchema).safeParse(
      Object.fromEntries(new FormData(event.currentTarget)),
    )
    if (!result.success) {
      const next = fieldErrors(result.error)
      setErrors(next)
      event.currentTarget
        .querySelector<HTMLInputElement>(`[name="${Object.keys(next)[0]}"]`)
        ?.focus()
      return
    }
    setErrors({})
    submit.mutate(result.data)
  }
  if (user)
    return (
      <Navigate
        to={user.role === 'ADMIN' && redirect === '/tickets' ? '/admin' : redirect}
        replace
      />
    )
  return (
    <div className="shell auth-layout">
      <PageTitle title={registering ? 'Create account' : 'Sign in'} />
      <div className="auth-editorial">
        <span className="eyebrow">GET OUT. GET INTO IT.</span>
        <h1>
          Life sounds
          <br />
          better <em>live.</em>
        </h1>
        <p>
          A place in the crowd.
          <br />A night you’ll talk about for years.
        </p>
        <div className="auth-disc" aria-hidden="true">
          <span>
            see
            <br />
            you
            <br />
            <em>there.</em>
          </span>
        </div>
        <span className="auth-editorial-foot">ENCORE / YOUR NEXT GREAT NIGHT</span>
      </div>
      <div className="auth-form-panel">
        <span className="eyebrow">{registering ? 'JOIN THE CROWD' : 'WELCOME BACK'}</span>
        <h2>{registering ? 'Your next night starts here.' : 'Good to see you again.'}</h2>
        <p>
          {registering
            ? 'Create an account to reserve tickets and keep track of your bookings.'
            : 'Sign in to pick up where you left off.'}
        </p>
        {notice && (
          <div className="success-note" role="status">
            {notice}
          </div>
        )}
        <form onSubmit={onSubmit} noValidate className="form-stack">
          <Field
            label="Username"
            name="username"
            autoComplete="username"
            autoCapitalize="none"
            spellCheck={false}
            error={errors.username}
            placeholder="Your username"
            disabled={submit.isPending}
          />
          <div className="password-field">
            <Field
              label="Password"
              name="password"
              type={showPassword ? 'text' : 'password'}
              autoComplete={registering ? 'new-password' : 'current-password'}
              error={errors.password}
              hint={
                registering
                  ? 'At least 15 characters. A memorable passphrase works well.'
                  : undefined
              }
              placeholder={registering ? 'Choose a passphrase' : 'Your password'}
              disabled={submit.isPending}
            />
            <button
              type="button"
              aria-label={showPassword ? 'Hide password' : 'Show password'}
              aria-pressed={showPassword}
              onClick={() => setShowPassword(!showPassword)}
            >
              {showPassword ? <EyeOff size={18} /> : <Eye size={18} />}
            </button>
          </div>
          {registering && (
            <Field
              label="Confirm password"
              name="confirmPassword"
              type={showPassword ? 'text' : 'password'}
              autoComplete="new-password"
              error={errors.confirmPassword}
              placeholder="One more time"
              disabled={submit.isPending}
            />
          )}
          <ErrorAlert error={submit.error} />
          <button className="button button-orange w-full" disabled={submit.isPending}>
            {submit.isPending ? (
              <Spinner label={registering ? 'Creating account…' : 'Signing in…'} />
            ) : (
              <>
                {registering ? 'Create account' : 'Sign in'}
                <ArrowUpRight size={18} />
              </>
            )}
          </button>
        </form>
        <p className="auth-switch">
          {registering ? 'Already part of the crowd?' : 'New around here?'}{' '}
          <Link
            to={`/${registering ? 'login' : 'register'}?redirect=${encodeURIComponent(redirect)}`}
          >
            {registering ? 'Sign in' : 'Create an account'} <ArrowUpRight size={14} />
          </Link>
        </p>
      </div>
    </div>
  )
}

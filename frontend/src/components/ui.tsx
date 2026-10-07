import { useEffect, type InputHTMLAttributes, type ReactNode } from 'react'
import { AlertCircle, ArrowRight, LoaderCircle, RefreshCw, Ticket } from 'lucide-react'
import { Link } from 'react-router'
import { ApiError } from '../lib/api'
import { statusLabels } from '../lib/format'

export function StatusBadge({ status }: { status: string }) {
  return (
    <span className={`status status-${status.toLowerCase()}`}>
      <span aria-hidden="true" />
      {statusLabels[status] || status}
    </span>
  )
}
export function Spinner({ label = 'Loading…' }: { label?: string }) {
  return (
    <span className="inline-flex items-center gap-2" role="status">
      <LoaderCircle size={17} className="animate-spin" />
      {label}
    </span>
  )
}
export function ErrorAlert({ error, retry }: { error: Error | null; retry?: () => void }) {
  if (!error) return null
  return (
    <div className="error-alert" role="alert">
      <AlertCircle size={19} className="shrink-0" />
      <div className="min-w-0">
        <p>{error.message}</p>
        {error instanceof ApiError && error.traceId && (
          <details className="mt-2 text-xs">
            <summary>Support reference</summary>
            <p className="mt-1 break-all">{error.traceId}</p>
          </details>
        )}
        {retry && (
          <button
            className="mt-3 inline-flex items-center gap-2 font-semibold underline underline-offset-4"
            onClick={retry}
          >
            <RefreshCw size={14} />
            Try again
          </button>
        )}
      </div>
    </div>
  )
}
export function EmptyState({
  title,
  children,
  action,
}: {
  title: string
  children: ReactNode
  action?: ReactNode
}) {
  return (
    <div className="empty-state">
      <Ticket size={34} strokeWidth={1.3} />
      <h2>{title}</h2>
      <p>{children}</p>
      {action}
    </div>
  )
}
export function Field({
  label,
  error,
  hint,
  ...input
}: InputHTMLAttributes<HTMLInputElement> & { label: string; error?: string; hint?: string }) {
  const id = input.id || input.name
  const described = error || hint ? `${id}-help` : undefined
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <input {...input} id={id} aria-invalid={!!error} aria-describedby={described} />
      {(error || hint) && (
        <p id={described} className={error ? 'field-error' : 'field-hint'}>
          {error || hint}
        </p>
      )}
    </div>
  )
}
export function BackLink({
  to = '/',
  children = 'All events',
}: {
  to?: string
  children?: ReactNode
}) {
  return (
    <Link to={to} className="back-link">
      <ArrowRight size={16} className="rotate-180" />
      {children}
    </Link>
  )
}
export function PageTitle({ title }: { title: string }) {
  useEffect(() => {
    document.title = `${title} · Encore`
  }, [title])
  return null
}

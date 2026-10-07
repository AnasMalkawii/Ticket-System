export const statusLabels: Record<string, string> = {
  SCHEDULED: 'Coming soon',
  ON_SALE: 'On sale',
  SOLD_OUT: 'Sold out',
  CLOSED: 'Sales closed',
  CANCELLED: 'Cancelled',
  PENDING: 'On hold',
  CONFIRMED: 'Confirmed',
  EXPIRED: 'Expired',
}
export function currencyDigits(currency: string) {
  try {
    return (
      new Intl.NumberFormat('en', { style: 'currency', currency }).resolvedOptions()
        .maximumFractionDigits ?? 2
    )
  } catch {
    return 2
  }
}
export function money(amountMinor: number, currency: string) {
  try {
    return new Intl.NumberFormat('en', { style: 'currency', currency }).format(
      amountMinor / 10 ** currencyDigits(currency),
    )
  } catch {
    return `${(amountMinor / 100).toFixed(2)} ${currency}`
  }
}
export function date(value: string | null, withTime = false) {
  if (!value) return 'Date to be announced'
  return new Intl.DateTimeFormat('en', {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
    ...(withTime ? { hour: 'numeric', minute: '2-digit' } : {}),
  }).format(new Date(value))
}
export function localDatetime(value: string | null) {
  if (!value) return ''
  const d = new Date(value)
  return new Date(d.getTime() - d.getTimezoneOffset() * 60_000).toISOString().slice(0, 16)
}
export function saleIsOpen(
  event: { status: string; saleStartsAt: string; saleEndsAt: string | null },
  now = Date.now(),
) {
  return (
    event.status === 'ON_SALE' &&
    new Date(event.saleStartsAt).getTime() <= now &&
    (!event.saleEndsAt || new Date(event.saleEndsAt).getTime() > now)
  )
}

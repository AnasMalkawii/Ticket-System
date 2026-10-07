import { z } from 'zod'
import { currencyDigits } from './format'
import { eventStatuses } from './types'

const username = z
  .string()
  .trim()
  .min(3, 'Use at least 3 characters.')
  .max(64, 'Use at most 64 characters.')
export const loginSchema = z.object({
  username,
  password: z.string().min(1, 'Enter your password.').max(128),
})
export const registerSchema = z
  .object({
    username: username.regex(
      /^[A-Za-z0-9._-]+$/,
      'Use letters, numbers, dots, underscores, or hyphens.',
    ),
    password: z
      .string()
      .min(15, 'Use at least 15 characters.')
      .max(72, 'Use at most 72 characters.')
      .refine(
        (value) => new TextEncoder().encode(value).length <= 72,
        'Password must fit within 72 UTF-8 bytes.',
      ),
    confirmPassword: z.string(),
  })
  .refine((value) => value.password === value.confirmPassword, {
    path: ['confirmPassword'],
    message: 'Passwords do not match.',
  })
export const quantitySchema = z.object({
  quantity: z.coerce
    .number()
    .int()
    .min(1, 'Choose at least one ticket.')
    .max(4, 'Choose up to 4 tickets.'),
})
export const reservationIdSchema = z.string().trim().uuid('Enter a valid reservation ID.')
const optionalDate = z
  .string()
  .refine((value) => !value || !Number.isNaN(Date.parse(value)), 'Choose a valid date and time.')
export const eventFormSchema = z
  .object({
    name: z.string().trim().min(1, 'Enter an event name.').max(200),
    venue: z.string().trim().min(1, 'Enter a venue.').max(200),
    startsAt: optionalDate,
    saleStartsAt: z
      .string()
      .min(1, 'Choose when sales open.')
      .refine((value) => !Number.isNaN(Date.parse(value)), 'Choose a valid date and time.'),
    saleEndsAt: optionalDate,
    price: z
      .string()
      .regex(/^\d+(\.\d+)?$/, 'Enter a positive price or 0.')
      .max(12),
    currency: z.string().regex(/^[A-Z]{3}$/, 'Choose a three-letter currency code.'),
    totalTickets: z.coerce.number().int().min(1, 'Enter at least one ticket.').max(2_147_483_647),
    status: z.enum(eventStatuses),
  })
  .superRefine((value, ctx) => {
    if (value.saleEndsAt && Date.parse(value.saleEndsAt) <= Date.parse(value.saleStartsAt)) {
      ctx.addIssue({
        code: 'custom',
        path: ['saleEndsAt'],
        message: 'Sales must close after they open.',
      })
    }
    const decimals = value.price.split('.')[1]?.length ?? 0
    if (decimals > currencyDigits(value.currency)) {
      ctx.addIssue({
        code: 'custom',
        path: ['price'],
        message: `Use up to ${currencyDigits(value.currency)} decimal places for ${value.currency}.`,
      })
    }
  })
export function fieldErrors(error: z.ZodError): Record<string, string> {
  const fields: Record<string, string> = {}
  for (const issue of error.issues) fields[String(issue.path[0])] ??= issue.message
  return fields
}

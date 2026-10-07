import { createContext, useContext } from 'react'
import type { User } from '../lib/types'

export interface AuthContextValue {
  user: User | null
  isLoading: boolean
  error: Error | null
  retry: () => void
  login: (credentials: { username: string; password: string }) => Promise<User>
  logout: () => Promise<void>
}
export const AuthContext = createContext<AuthContextValue | null>(null)
export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) throw new Error('AuthProvider is missing')
  return context
}

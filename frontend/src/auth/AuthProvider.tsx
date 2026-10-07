import { useEffect, type ReactNode } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError, restoreSession, signIn, signOut } from '../lib/api'
import type { User } from '../lib/types'
import { AuthContext, type AuthContextValue } from './useAuth'

export function AuthProvider({ children }: { children: ReactNode }) {
  const client = useQueryClient()
  const session = useQuery({
    queryKey: ['session'],
    queryFn: async ({ signal }): Promise<User | null> => {
      try {
        return (await restoreSession()) ? await api<User>('/auth/me', { signal }, true) : null
      } catch (error) {
        if (error instanceof ApiError && (error.status === 401 || error.status === 403)) return null
        throw error
      }
    },
    retry: false,
    staleTime: 60_000,
  })
  useEffect(() => {
    const ended = () => {
      // A late /me response must not restore a user after sign-out.
      void client.cancelQueries({ queryKey: ['session'] })
      client.setQueryData(['session'], null)
      void client.cancelQueries({
        predicate: (query) => ['reservation', 'order'].includes(String(query.queryKey[0])),
      })
      client.removeQueries({
        predicate: (query) => ['reservation', 'order'].includes(String(query.queryKey[0])),
      })
    }
    window.addEventListener('session-ended', ended)
    return () => window.removeEventListener('session-ended', ended)
  }, [client])
  const login: AuthContextValue['login'] = async (credentials) => {
    await signIn(credentials)
    const user = await api<User>('/auth/me', {}, true)
    await client.cancelQueries({ queryKey: ['session'] })
    client.setQueryData(['session'], user)
    return user
  }
  return (
    <AuthContext.Provider
      value={{
        user: session.data ?? null,
        isLoading: session.isPending,
        error: session.error,
        retry: () => {
          void session.refetch()
        },
        login,
        logout: async () => {
          await signOut()
        },
      }}
    >
      {children}
    </AuthContext.Provider>
  )
}

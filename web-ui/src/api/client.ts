import type { User } from 'firebase/auth';

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

interface RequestOptions extends RequestInit {
  accountId?: string;
}

export async function apiRequest<T>(
  path: string,
  user: User,
  options: RequestOptions = {},
): Promise<T> {
  const token = await user.getIdToken();
  const { accountId, ...fetchOptions } = options;

  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${token}`,
    ...(accountId ? { 'X-ACCOUNT-ID': accountId } : {}),
    ...(fetchOptions.headers as Record<string, string> | undefined),
  };

  const response = await fetch(`${BASE_URL}${path}`, {
    ...fetchOptions,
    headers,
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `HTTP ${response.status}`);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return response.json() as Promise<T>;
}

export async function apiRequestText(
  path: string,
  user: User,
  options: RequestOptions = {},
): Promise<string> {
  const token = await user.getIdToken();
  const { accountId, ...fetchOptions } = options;

  const headers: Record<string, string> = {
    Authorization: `Bearer ${token}`,
    ...(accountId ? { 'X-ACCOUNT-ID': accountId } : {}),
    ...(fetchOptions.headers as Record<string, string> | undefined),
  };

  const response = await fetch(`${BASE_URL}${path}`, {
    ...fetchOptions,
    headers,
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `HTTP ${response.status}`);
  }

  return response.text();
}

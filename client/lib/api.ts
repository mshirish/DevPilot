export type IndexStatus = "PENDING" | "INDEXING" | "READY" | "FAILED";

export type User = {
  id: string;
  githubId: number;
  githubUsername: string;
  displayName: string;
  avatarUrl: string | null;
  /** Demo-mode visitor without a GitHub account */
  guest: boolean;
  /** False in demo mode for visitors: hide sync/index controls */
  canManageRepos: boolean;
};

export type AuthConfig = {
  demoMode: boolean;
  demoRepos: string[];
  /** Who generates the answers, for the login page notice. Empty: don't show it. */
  aiProviderName?: string;
};

export type Repository = {
  id: string;
  githubRepoId: number;
  owner: string;
  name: string;
  fullName: string;
  isPrivate: boolean;
  defaultBranch: string;
  language: string | null;
  htmlUrl: string | null;
  description: string | null;
  indexStatus: IndexStatus;
  indexedAt: string | null;
  chunkCount: number;
  filesTotal: number;
  filesProcessed: number;
  errorMessage: string | null;
};

export type IndexStatusResponse = {
  repositoryId: string;
  indexStatus: IndexStatus;
  filesTotal: number;
  filesProcessed: number;
  chunkCount: number;
  indexedAt: string | null;
  errorMessage: string | null;
};

export type ChatSession = {
  id: string;
  repositoryId: string;
  title: string;
  createdAt: string;
};

export type Citation = {
  filePath: string;
  startLine: number | null;
  endLine: number | null;
  language: string | null;
};

export type ChatMessage = {
  id: string;
  role: "USER" | "ASSISTANT";
  content: string;
  citations: Citation[];
  createdAt: string;
};


export class ApiError extends Error {
  status: number;
  /** Machine-readable reason from the backend, e.g. "DEMO_QUESTION_LIMIT" */
  code?: string;

  constructor(status: number, message: string, code?: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

/** The visitor used all their demo questions. Waiting won't help, so the chat stops offering to send. */
export const DEMO_QUESTION_LIMIT = "DEMO_QUESTION_LIMIT";

/** Builds an ApiError from a failed response's JSON body ({ message, code }). */
export async function toApiError(res: Response): Promise<ApiError> {
  try {
    const data = await res.json();
    return new ApiError(res.status, data.message ?? data.error ?? res.statusText, data.code);
  } catch {
    return new ApiError(res.status, res.statusText || "Request failed");
  }
}

export function getApiBaseUrl() {
  // "" means same origin (the Vercel proxy in next.config.ts); unset means local dev
  const url = process.env.NEXT_PUBLIC_API_URL;
  if (url === undefined) return "http://localhost:8080";
  return url.replace(/\/$/, "");
}

export function getGithubLoginUrl() {
  return `${getApiBaseUrl()}/oauth2/authorization/github`;
}


export async function apiFetch<T>(
  path: string,
  init?: RequestInit,
): Promise<T> {
  const res = await fetch(`${getApiBaseUrl()}${path}`, {
    ...init,
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      ...(init?.headers ?? {}),
    },
  });

  if (!res.ok) {
    throw await toApiError(res);
  }

  if (res.status === 204) {
    return undefined as T;
  }

  return res.json() as Promise<T>;
}

export const api = {
  me: () => apiFetch<User>("/api/auth/me"),
  authConfig: () => apiFetch<AuthConfig>("/api/auth/config"),
  demoLogin: () => apiFetch<User>("/api/auth/demo", { method: "POST" }),
  logout: () =>
    apiFetch<void>("/api/auth/logout", {
      method: "POST",
    }),

  listRepos: (refresh = true) =>
    apiFetch<Repository[]>(`/api/repos?refresh=${refresh}`),
  getRepo: (id: string) => apiFetch<Repository>(`/api/repos/${id}`),
  startIndex: (id: string) =>
    apiFetch<Repository>(`/api/repos/${id}/index`, { method: "POST" }),
  indexStatus: (id: string) =>
    apiFetch<IndexStatusResponse>(`/api/repos/${id}/status`),
   createSession: (repositoryId: string, title?: string) =>
    apiFetch<ChatSession>("/api/chat/sessions", {
      method: "POST",
      body: JSON.stringify({ repositoryId, title }),
    }),
  listSessions: (repositoryId: string) =>
    apiFetch<ChatSession[]>(
      `/api/chat/sessions?repositoryId=${encodeURIComponent(repositoryId)}`
    ),
  getMessages: (sessionId: string) =>
    apiFetch<ChatMessage[]>(`/api/chat/sessions/${sessionId}`),
};

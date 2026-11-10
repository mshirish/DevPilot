export const queryKeys = {
  auth: {
    all: ["auth"] as const,
    me: () => [...queryKeys.auth.all, "me"] as const,
    config: () => [...queryKeys.auth.all, "config"] as const,
  },
  repos: {
    all: ["repos"] as const,
    list: () => [...queryKeys.repos.all, "list"] as const,
    detail: (id: string) => [...queryKeys.repos.all, "detail", id] as const,
    status: (id: string) => [...queryKeys.repos.all, "status", id] as const,
  },
  chat: {
    all: ["chat"] as const,
    sessions: (repositoryId: string) =>
      [...queryKeys.chat.all, "sessions", repositoryId] as const,
    messages: (sessionId: string) =>
      [...queryKeys.chat.all, "messages", sessionId] as const,
    /** Client-only: the server's message once the visitor hit the demo question cap */
    demoLimit: () => [...queryKeys.chat.all, "demo-limit"] as const,
  },
};

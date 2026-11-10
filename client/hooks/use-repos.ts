"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api, type Repository } from "@/lib/api";
import { queryKeys } from "@/lib/query-keys";
import { toast } from "@/components/ui/toast";



const INDEXING_POLL_MS = 2000;

function hasIndexingRepos(repos: Repository[] | undefined) {
  return repos?.some((repo) => repo.indexStatus === "INDEXING") ?? false;
}

function updateRepoInListCache(
  queryClient: ReturnType<typeof useQueryClient>,
  repo: Repository
) {
  queryClient.setQueryData<Repository[]>(queryKeys.repos.list(), (current) => {
    if (!current) return current;
    return current.map((item) => (item.id === repo.id ? repo : item));
  });
}

export function useRepos() {
  return useQuery({
    queryKey: queryKeys.repos.list(),
    queryFn: async () => {
      const repos = await api.listRepos(false);
      if (repos.length === 0) {
        return api.listRepos(true);
      }
      return repos;
    },
    staleTime: 30_000,
    refetchInterval: (query) =>
      hasIndexingRepos(query.state.data) ? INDEXING_POLL_MS : false,
  });
}

export function useRepository(repoId: string) {
  return useQuery({
    queryKey: queryKeys.repos.detail(repoId),
    queryFn: () => api.getRepo(repoId),
    enabled: Boolean(repoId),
    refetchInterval: (query) =>
      query.state.data?.indexStatus === "INDEXING" ? INDEXING_POLL_MS : false,
  });
}

export function useIndexStatus(repoId: string, enabled = false) {
  return useQuery({
    queryKey: queryKeys.repos.status(repoId),
    queryFn: () => api.indexStatus(repoId),
    enabled: Boolean(repoId) && enabled,
    refetchInterval: (query) =>
      query.state.data?.indexStatus === "INDEXING" ? 1500 : false,
  });
}

export function useStartIndexing() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (repoId: string) => api.startIndex(repoId),
    onSuccess: (repo) => {
      queryClient.setQueryData(queryKeys.repos.detail(repo.id), repo);
      updateRepoInListCache(queryClient, repo);
      void queryClient.invalidateQueries({
        queryKey: queryKeys.repos.status(repo.id),
      });
      // Not "loading": the toast times out long before indexing ends; progress shows on the repo
      toast.add({
        title: "Indexing started",
        description: `${repo.fullName}: progress is shown on the repository.`,
        type: "info",
      });
    },
    onError: (error: Error) => {
      toast.add({
        title: "Could not start indexing",
        description: error.message,
        type: "error",
      });
    },
  });
}

export function useRefreshRepos() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: () =>
      toast.promise(api.listRepos(true), {
        loading: {
          title: "Syncing repositories",
          description: "Fetching the latest repos from GitHub…",
          type: "loading",
        },
        success: (repos) => ({
          title: "Repositories synced",
          description: `${repos.length} ${repos.length === 1 ? "repository" : "repositories"} loaded from GitHub`,
          type: "success",
        }),
        error: (error: Error) => ({
          title: "Sync failed",
          description:
            error instanceof Error ? error.message : "Could not sync repositories",
          type: "error",
        }),
      }),
    onSuccess: (repos) => {
      queryClient.setQueryData(queryKeys.repos.list(), repos);
    },
  });
}

export function getRepoProgress(repo: Pick<
  Repository,
  "filesProcessed" | "filesTotal"
>) {
  if (!repo.filesTotal) return 0;
  return Math.min(100, Math.round((repo.filesProcessed / repo.filesTotal) * 100));
}

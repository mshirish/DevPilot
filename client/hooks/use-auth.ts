"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";


import { api, ApiError } from "@/lib/api";
import { queryKeys } from "@/lib/query-keys";

export const AUTH_COOKIE = "devpilot_auth";

export function setAuthCookie(authed: boolean) {
  if (typeof document === "undefined") return;
  if (authed) {
    document.cookie = `${AUTH_COOKIE}=1; path=/; max-age=${60 * 60 * 24 * 7}; SameSite=Lax`;
  } else {
    document.cookie = `${AUTH_COOKIE}=; path=/; max-age=0; SameSite=Lax`;
  }
}


export function useCurrentUser(){
    return useQuery({
        queryKey: queryKeys.auth.me(),
        queryFn: async()=>{
            try {
                const user = await api.me();
                setAuthCookie(true);
                return user;
            } catch (error) {
                setAuthCookie(false);
                throw error;
            }

        },
         staleTime: 5 * 60 * 1000,
    retry: false,
    })
}

/** Whether the signed-in user may sync/index repos. False for demo visitors (and while loading). */
export function useCanManageRepos() {
  const { data: user } = useCurrentUser();
  return user?.canManageRepos ?? false;
}

/** Public backend settings (is demo mode on?). Also wakes a sleeping free-tier backend early. */
export function useAuthConfig() {
  return useQuery({
    queryKey: queryKeys.auth.config(),
    queryFn: () => api.authConfig(),
    staleTime: Infinity,
    retry: 2,
  });
}

/** Signs in as a guest for the read-only demo, then caches the user like a normal login. */
export function useDemoLogin() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: () => api.demoLogin(),
    onSuccess: (user) => {
      setAuthCookie(true);
      queryClient.setQueryData(queryKeys.auth.me(), user);
      // Drop any repo list cached from a previous user
      queryClient.removeQueries({ queryKey: queryKeys.repos.all });
    },
  });
}

/**
 * True once `active` has stayed true for `delayMs`. Used to explain slow first loads:
 * the free backend sleeps when idle and takes 1–2 minutes to wake up (Spring starts in ~90s on Render free).
 */
export function useIsSlow(active: boolean, delayMs = 4000) {
  const [slow, setSlow] = useState(false);
  useEffect(() => {
    if (!active) return;
    const timer = setTimeout(() => setSlow(true), delayMs);
    return () => {
      clearTimeout(timer);
      setSlow(false);
    };
  }, [active, delayMs]);
  return active && slow;
}

export function useLogout() {
  const queryClient = useQueryClient();
  const router = useRouter();

  return useMutation({
    mutationFn: () => api.logout(),
    onSettled: async () => {
      setAuthCookie(false);
      queryClient.setQueryData(queryKeys.auth.me(), null);
      queryClient.removeQueries({ queryKey: queryKeys.repos.all });
      queryClient.removeQueries({ queryKey: queryKeys.chat.all });
      await queryClient.invalidateQueries({ queryKey: queryKeys.auth.all });
      router.replace("/login");
    },
  });
}

"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";

import { useCurrentUser, useIsSlow } from "@/hooks/use-auth";
import { Spinner } from "@/components/ui/spinner";

export function RequireAuth({ children }: { children: React.ReactNode }) {
  const { data: user, isLoading, isError } = useCurrentUser();
  const router = useRouter();
  const isWaking = useIsSlow(isLoading);

  useEffect(() => {
    if (!isLoading && !user) {
      router.replace("/login");
    }
  }, [isLoading, user, router]);

  if (isLoading) {
    return (
      <div className="flex min-h-svh items-center justify-center">
        <div className="flex flex-col items-center gap-3 text-muted-foreground">
          <Spinner className="size-6" />
          <p className="text-sm">Loading your workspace…</p>
          {isWaking && (
            <p className="max-w-xs text-center text-xs">
              The free server is waking up from sleep. This can take 1–2
              minutes.
            </p>
          )}
        </div>
      </div>
    );
  }

  if (isError || !user) {
    return null;
  }

  return <>{children}</>;
}

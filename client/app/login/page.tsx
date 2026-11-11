"use client";
import React from 'react';
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect } from "react";
import { AlertCircle } from "lucide-react";

import { GitHubIcon } from "@/components/icons/github-icon";
import { BrandMark } from "@/components/layout/app-shell";
import { ModeToggle } from "@/components/ui/mode-toggle";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { buttonVariants } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Spinner } from "@/components/ui/spinner";
import { cn } from "@/lib/utils";
import { getGithubLoginUrl } from '@/lib/api';
import {
  useAuthConfig,
  useCurrentUser,
  useDemoLogin,
  useIsSlow,
} from '@/hooks/use-auth';
import { Sparkles } from "lucide-react";

function LoginLoading(){
    return (
        <div className="flex min-h-svh items-center justify-center">
                <Spinner className="size-8"/>
        </div>
    )
}

const LoginContent = () => {
     const params = useSearchParams();
  const router = useRouter();
  const error = params.get("error");
  const next = params.get("next") || "/dashboard";
  const { data: user, isLoading } = useCurrentUser();
  const config = useAuthConfig();
  const demoLogin = useDemoLogin();
  const demoMode = config.data?.demoMode ?? false;
  const aiProviderName = config.data?.aiProviderName?.trim();
  // The free backend sleeps when idle; explain the wait instead of looking broken
  const isWaking = useIsSlow(config.isLoading || demoLogin.isPending);
  // Only same-site paths (not "//other.site") to avoid an open redirect
  const safeNext = next.startsWith("/") && !next.startsWith("//") ? next : "/dashboard";

  useEffect(() => {
    if (!isLoading && user) {
      router.replace(safeNext);
    }
  }, [user, isLoading, safeNext, router]);

  function startDemo() {
    demoLogin.mutate(undefined, {
      onSuccess: () => router.replace("/dashboard"),
    });
  }


  return (
   <div className="relative flex min-h-svh flex-col overflow-hidden bg-background">
      <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(ellipse_at_top,oklch(from_var(--primary)_l_c_h/0.1),transparent_55%)]" />

      <header className="relative z-10 flex h-14 items-center justify-between px-4">
        <Link href="/">
          <BrandMark />
        </Link>
        <ModeToggle />
      </header>

      <main className="relative z-10 flex flex-1 items-center justify-center px-4 py-10">
        <Card className="w-full max-w-sm border-border/70 bg-card/90 shadow-lg shadow-foreground/5 backdrop-blur-xl">
          <CardHeader className="space-y-4 text-center">
            <div className="mx-auto flex size-12 items-center justify-center rounded-2xl bg-foreground text-background">
              <GitHubIcon className="size-6" />
            </div>
            <div className="space-y-1">
              <CardTitle className="text-xl">
                {demoMode ? "Try DevPilot" : "Sign in"}
              </CardTitle>
              <CardDescription>
                {demoMode
                  ? "Ask questions about a real codebase in plain English. No sign-up needed."
                  : "Connect GitHub to chat with your repositories."}
              </CardDescription>
            </div>
          </CardHeader>

          <CardContent className="space-y-4">
            {error && (
              <Alert variant="destructive">
                <AlertCircle />
                <AlertTitle>Sign-in failed</AlertTitle>
                <AlertDescription>Please try again.</AlertDescription>
              </Alert>
            )}

            {demoLogin.isError && (
              <Alert variant="destructive">
                <AlertCircle />
                <AlertTitle>Couldn&apos;t start the demo</AlertTitle>
                <AlertDescription>{demoLogin.error.message}</AlertDescription>
              </Alert>
            )}

            {isWaking && (
              <Alert>
                <Spinner />
                <AlertTitle>Waking up the server…</AlertTitle>
                <AlertDescription>
                  This demo runs on free hosting that sleeps when idle. The first
                  load can take about a minute.
                </AlertDescription>
              </Alert>
            )}

            {demoMode && (
              <>
                <button
                  type="button"
                  onClick={startDemo}
                  disabled={demoLogin.isPending}
                  className={cn(
                    buttonVariants({ size: "lg" }),
                    "inline-flex w-full items-center justify-center gap-2"
                  )}
                >
                  {demoLogin.isPending ? (
                    <Spinner className="size-5" />
                  ) : (
                    <Sparkles className="size-5" />
                  )}
                  Try the live demo
                </button>
                {aiProviderName && (
                  <p className="text-center text-xs text-muted-foreground">
                    Answers are generated by {aiProviderName}, and your questions are sent to{" "}
                    {aiProviderName}.
                  </p>
                )}
                {config.data && config.data.demoRepos.length > 0 && (
                  <p className="text-center text-xs text-muted-foreground">
                    Preloaded: {config.data.demoRepos.join(", ")}
                  </p>
                )}
              </>
            )}

            <a
              href={getGithubLoginUrl()}
              className={cn(
                buttonVariants({ size: "lg", variant: demoMode ? "outline" : "default" }),
                "inline-flex w-full items-center justify-center gap-2",
                !demoMode && "bg-foreground text-background hover:bg-foreground/90"
              )}
            >
              <GitHubIcon className="size-5" />
              {demoMode ? "Owner sign-in with GitHub" : "Continue with GitHub"}
            </a>
          </CardContent>
        </Card>
      </main>
    </div>
  )
}

export default function LoginPage() {
  return (
    <Suspense fallback={<LoginLoading />}>
      <LoginContent />
    </Suspense>
  );
}
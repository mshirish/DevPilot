"use client";

import {
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { useCallback, useRef, useState } from "react";

import { api, ApiError, DEMO_QUESTION_LIMIT, type ChatMessage } from "@/lib/api";
import { queryKeys } from "@/lib/query-keys";
import { streamChatMessage } from "@/lib/stream-chat";
import { toast } from "@/components/ui/toast";

export function useChatSessions(repositoryId: string, enabled = true) {
  return useQuery({
    queryKey: queryKeys.chat.sessions(repositoryId),
    queryFn: () => api.listSessions(repositoryId),
    enabled: Boolean(repositoryId) && enabled,
  });
}

export function useChatMessages(sessionId: string | null) {
  return useQuery({
    queryKey: queryKeys.chat.messages(sessionId ?? ""),
    queryFn: () => api.getMessages(sessionId!),
    enabled: Boolean(sessionId),
  });
}

export function useCreateChatSession(repositoryId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (title?: string) => api.createSession(repositoryId, title),
    onSuccess: (session) => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.chat.sessions(repositoryId),
      });
      queryClient.setQueryData(queryKeys.chat.messages(session.id), []);
    },
    onError: (error: Error) => {
      toast.add({
        title: "Could not create chat",
        description: error.message,
        type: "error",
      });
    },
  });
}

/**
 * The server's message once this visitor has used all their demo questions, else null.
 * Kept in the query cache so it applies to every chat in this tab, not just the one that hit it.
 */
export function useDemoQuestionLimit() {
  return useQuery<string | null>({
    queryKey: queryKeys.chat.demoLimit(),
    queryFn: () => null,
    initialData: null,
    staleTime: Infinity,
    gcTime: Infinity,
  }).data;
}

export function useStreamChat(sessionId: string | null) {
  const queryClient = useQueryClient();
  const [streaming, setStreaming] = useState(false);
  const [streamText, setStreamText] = useState("");
  const abortRef = useRef<AbortController | null>(null);

  /** Sends a question. Resolves false if it failed and was removed, so the caller can offer a retry. */
  const send = useCallback(
    async (content: string): Promise<boolean> => {
      if (!sessionId || !content.trim() || streaming) return false;

      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;

      const optimisticId = `temp-${Date.now()}`;
      // Set once the server has saved the question (user_message event)
      let savedId: string | null = null;
      const optimistic: ChatMessage = {
        id: optimisticId,
        role: "USER",
        content: content.trim(),
        citations: [],
        createdAt: new Date().toISOString(),
      };

      queryClient.setQueryData<ChatMessage[]>(
        queryKeys.chat.messages(sessionId),
        (prev) => [...(prev ?? []), optimistic]
      );

      setStreaming(true);
      setStreamText("");

      try {
        await streamChatMessage(sessionId, content.trim(), {
          signal: controller.signal,
          onUserMessage: (message) => {
            savedId = message.id;
            queryClient.setQueryData<ChatMessage[]>(
              queryKeys.chat.messages(sessionId),
              (prev) => [
                ...(prev ?? []).filter((m) => m.id !== optimisticId),
                message,
              ]
            );
          },
          onToken: (token) => {
            setStreamText((prev) => prev + token);
          },
          onAssistantMessage: (message) => {
            queryClient.setQueryData<ChatMessage[]>(
              queryKeys.chat.messages(sessionId),
              (prev) => [...(prev ?? []), message]
            );
            setStreamText("");
          },
        });
        return true;
      } catch (err) {
        // The user pressed Stop: the question was sent, keep it
        if ((err as Error).name === "AbortError") return true;
        if (err instanceof ApiError && err.code === DEMO_QUESTION_LIMIT) {
          // Permanent for this visitor: the chat shows a lasting notice instead of a toast
          queryClient.setQueryData(queryKeys.chat.demoLimit(), err.message);
        } else {
          // Temporary (rate limit, daily cap, provider error): the text goes back in the input
          toast.add({
            title: "Message not sent",
            description: err instanceof Error ? err.message : "Unknown error",
            type: "error",
          });
        }
        // The server removes a question it couldn't answer; drop it from the thread too
        // (both the optimistic copy and the saved one, if it already arrived)
        queryClient.setQueryData<ChatMessage[]>(
          queryKeys.chat.messages(sessionId),
          (prev) =>
            (prev ?? []).filter((m) => m.id !== optimisticId && m.id !== savedId)
        );
        setStreamText("");
        return false;
      } finally {
        setStreaming(false);
      }
    },
    [sessionId, streaming, queryClient]
  );

  const stop = useCallback(() => {
    abortRef.current?.abort();
    setStreaming(false);
  }, []);

  return { send, stop, streaming, streamText };
}

"use client";

import { useCallback, useEffect, useState } from "react";

import { env } from "@/lib/env";
import type {
  SessionHistoryListResponse,
  SessionHistorySummary,
} from "@/lib/session-history";

async function fetchSessionList(): Promise<SessionHistorySummary[]> {
  const response = await fetch(`${env.backendHttpUrl}/api/sessions`, { cache: "no-store" });
  const payload = (await response.json()) as SessionHistoryListResponse | { detail?: string };
  if (!response.ok) {
    throw new Error(
      "detail" in payload && typeof payload.detail === "string"
        ? payload.detail
        : "Failed to load session history.",
    );
  }
  if (!("items" in payload)) {
    throw new Error("Failed to load session history.");
  }
  return payload.items;
}

export function useSessionHistory() {
  const [sessions, setSessions] = useState<SessionHistorySummary[]>([]);
  const [status, setStatus] = useState<"idle" | "loading" | "error">("loading");
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setStatus("loading");
    setErrorMessage(null);
    try {
      const nextSessions = await fetchSessionList();
      setSessions(nextSessions);
      setStatus("idle");
    } catch (error) {
      setStatus("error");
      setErrorMessage(
        error instanceof Error ? error.message : "Failed to load session history.",
      );
    }
  }, []);

  const deleteSession = useCallback(async (sessionId: string) => {
    const response = await fetch(`${env.backendHttpUrl}/api/sessions/${sessionId}`, {
      method: "DELETE",
    });
    const payload = (await response.json().catch(() => ({ detail: "Failed to delete session." }))) as {
      detail?: string;
    };
    if (!response.ok) {
      throw new Error(payload.detail ?? "Failed to delete session.");
    }

    setSessions((current) => current.filter((session) => session.sessionId !== sessionId));
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  return {
    deleteSession,
    errorMessage,
    refresh,
    sessions,
    status,
  };
}
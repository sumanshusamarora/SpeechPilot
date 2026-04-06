"use client";

import { useCallback, useEffect, useState } from "react";

import { env } from "@/lib/env";
import type { SessionHistoryDetailResponse } from "@/lib/session-history";

async function fetchSessionDetail(sessionId: string): Promise<SessionHistoryDetailResponse> {
  const response = await fetch(`${env.backendHttpUrl}/api/sessions/${sessionId}`, { cache: "no-store" });
  const payload = (await response.json()) as SessionHistoryDetailResponse | { detail?: string };
  if (!response.ok) {
    throw new Error(
      "detail" in payload && typeof payload.detail === "string"
        ? payload.detail
        : "Failed to load session detail.",
    );
  }
  if (!("summary" in payload)) {
    throw new Error("Failed to load session detail.");
  }
  return payload;
}

export function useSessionDetail(sessionId: string) {
  const [detail, setDetail] = useState<SessionHistoryDetailResponse | null>(null);
  const [status, setStatus] = useState<"idle" | "loading" | "error">("loading");
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setStatus("loading");
    setErrorMessage(null);
    try {
      const payload = await fetchSessionDetail(sessionId);
      setDetail(payload);
      setStatus("idle");
    } catch (error) {
      setStatus("error");
      setErrorMessage(
        error instanceof Error ? error.message : "Failed to load session detail.",
      );
    }
  }, [sessionId]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  return {
    detail,
    errorMessage,
    refresh,
    status,
  };
}
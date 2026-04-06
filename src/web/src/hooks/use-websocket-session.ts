"use client";

import { useEffect, useRef, useState } from "react";

import {
  createSessionStartEvent,
  createSessionStopEvent,
  type ClientEvent,
  type ServerEvent,
} from "@/lib/contracts";

import { env } from "@/lib/env";

type ConnectionStatus = "disconnected" | "connecting" | "connected" | "error";

type LogDirection = "in" | "out" | "system";

interface LogEntry {
  id: string;
  direction: LogDirection;
  timestamp: string;
  payload: ClientEvent | ServerEvent | { message: string } | string;
}

const MAX_LOG_ENTRIES = 40;

function buildDefaultSessionId() {
  return `debug-${Date.now()}`;
}

export function useWebsocketSession() {
  const websocketRef = useRef<WebSocket | null>(null);
  const [entries, setEntries] = useState<LogEntry[]>([]);
  const [sessionId, setSessionId] = useState(buildDefaultSessionId);
  const [connectionStatus, setConnectionStatus] =
    useState<ConnectionStatus>("disconnected");

  const appendLog = (direction: LogDirection, payload: LogEntry["payload"]) => {
    setEntries((currentEntries) => [
      {
        id: `${Date.now()}-${Math.random()}`,
        direction,
        timestamp: new Date().toISOString(),
        payload,
      },
      ...currentEntries,
    ].slice(0, MAX_LOG_ENTRIES));
  };

  const handleIncomingMessage = (rawPayload: string) => {
    try {
      appendLog("in", JSON.parse(rawPayload) as ServerEvent);
    } catch {
      appendLog("in", rawPayload);
    }
  };

  const connect = () => {
    if (websocketRef.current?.readyState === WebSocket.OPEN) {
      appendLog("system", { message: "Websocket is already connected." });
      return;
    }

    setConnectionStatus("connecting");
    const websocket = new WebSocket(env.backendWsUrl);
    websocketRef.current = websocket;

    websocket.onopen = () => {
      setConnectionStatus("connected");
      appendLog("system", { message: "Websocket connected." });
    };

    websocket.onmessage = (event) => {
      handleIncomingMessage(String(event.data));
    };

    websocket.onerror = () => {
      setConnectionStatus("error");
      appendLog("system", { message: "Websocket reported an error." });
    };

    websocket.onclose = () => {
      websocketRef.current = null;
      setConnectionStatus("disconnected");
      appendLog("system", { message: "Websocket disconnected." });
    };
  };

  const disconnect = () => {
    websocketRef.current?.close();
  };

  const send = (event: ClientEvent) => {
    if (websocketRef.current?.readyState !== WebSocket.OPEN) {
      appendLog("system", { message: "Cannot send event while websocket is disconnected." });
      return;
    }

    websocketRef.current.send(JSON.stringify(event));
    appendLog("out", event);
  };

  const startSession = () => {
    const nextSessionId = sessionId.trim() || buildDefaultSessionId();
    setSessionId(nextSessionId);
    send(createSessionStartEvent(nextSessionId));
  };

  const stopSession = () => {
    const nextSessionId = sessionId.trim();
    if (!nextSessionId) {
      appendLog("system", { message: "Enter a session ID before sending session.stop." });
      return;
    }
    send(createSessionStopEvent(nextSessionId));
  };

  useEffect(() => () => websocketRef.current?.close(), []);

  return {
    backendHttpUrl: env.backendHttpUrl,
    backendWsUrl: env.backendWsUrl,
    connect,
    connectionStatus,
    disconnect,
    entries,
    sessionId,
    setSessionId,
    startSession,
    stopSession,
  };
}
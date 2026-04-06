"use client";

import { useEffect, useRef, useState } from "react";

import {
  createAudioChunkEvent,
  createSessionStartEvent,
  createSessionStopEvent,
  type AudioChunkPayload,
  type ClientEvent,
  type SessionSummaryPayload,
  type ServerEvent,
  type TranscriptFinalEvent,
  type TranscriptPartialEvent,
} from "@/lib/contracts";
import { BrowserAudioCapture, type BrowserAudioChunk } from "@/lib/audio-capture";

import { env } from "@/lib/env";

type ConnectionStatus = "disconnected" | "connecting" | "connected" | "error";
type SessionState = "idle" | "starting" | "capturing" | "stopping" | "error";

type LogDirection = "in" | "out" | "system";

interface LogEntry {
  id: string;
  direction: LogDirection;
  timestamp: string;
  payload: ClientEvent | ServerEvent | { message: string } | string;
}

interface FinalTranscriptSegment {
  utteranceId: string;
  text: string;
  timestamp: string;
}

const MAX_LOG_ENTRIES = 40;

function buildDefaultSessionId() {
  return `debug-${Date.now()}`;
}

export function useWebsocketSession() {
  const websocketRef = useRef<WebSocket | null>(null);
  const audioCaptureRef = useRef<BrowserAudioCapture | null>(null);
  const sequenceRef = useRef(0);
  const connectionPromiseRef = useRef<Promise<void> | null>(null);
  const sessionStateRef = useRef<SessionState>("idle");
  const [entries, setEntries] = useState<LogEntry[]>([]);
  const [sessionId, setSessionId] = useState(buildDefaultSessionId);
  const [connectionStatus, setConnectionStatus] =
    useState<ConnectionStatus>("disconnected");
  const [sessionState, setSessionState] = useState<SessionState>("idle");
  const [partialTranscript, setPartialTranscript] = useState("");
  const [finalSegments, setFinalSegments] = useState<FinalTranscriptSegment[]>([]);
  const [summary, setSummary] = useState<SessionSummaryPayload | null>(null);
  const [micErrorMessage, setMicErrorMessage] = useState<string | null>(null);

  const updateSessionState = (nextState: SessionState) => {
    sessionStateRef.current = nextState;
    setSessionState(nextState);
  };

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

  const handleServerEvent = (event: ServerEvent) => {
    if (event.type === "transcript.partial") {
      const payload = event as TranscriptPartialEvent;
      setPartialTranscript(payload.payload.text);
      return;
    }
    if (event.type === "transcript.final") {
      const payload = event as TranscriptFinalEvent;
      setPartialTranscript("");
      setFinalSegments((currentSegments) => [
        ...currentSegments,
        {
          utteranceId: payload.payload.utteranceId,
          text: payload.payload.text,
          timestamp: payload.timestamp,
        },
      ]);
      return;
    }
    if (event.type === "session.summary") {
      setSummary(event.payload);
      updateSessionState("idle");
      return;
    }
    if (event.type === "error") {
      setMicErrorMessage(event.payload.message);
      if (sessionStateRef.current !== "idle") {
        updateSessionState("error");
      }
    }
  };

  const connect = async () => {
    if (websocketRef.current?.readyState === WebSocket.OPEN) {
      appendLog("system", { message: "Websocket is already connected." });
      return;
    }
    if (connectionPromiseRef.current !== null) {
      await connectionPromiseRef.current;
      return;
    }

    setConnectionStatus("connecting");
    connectionPromiseRef.current = new Promise<void>((resolve, reject) => {
      const websocket = new WebSocket(env.backendWsUrl);
      websocketRef.current = websocket;

      websocket.onopen = () => {
        setConnectionStatus("connected");
        appendLog("system", { message: "Websocket connected." });
        connectionPromiseRef.current = null;
        resolve();
      };

      websocket.onmessage = (event) => {
        const rawPayload = String(event.data);
        try {
          const parsedEvent = JSON.parse(rawPayload) as ServerEvent;
          appendLog("in", parsedEvent);
          handleServerEvent(parsedEvent);
        } catch {
          handleIncomingMessage(rawPayload);
        }
      };

      websocket.onerror = () => {
        setConnectionStatus("error");
        appendLog("system", { message: "Websocket reported an error." });
        connectionPromiseRef.current = null;
        reject(new Error("Websocket connection failed."));
      };

      websocket.onclose = () => {
        websocketRef.current = null;
        setConnectionStatus("disconnected");
        updateSessionState("idle");
        void audioCaptureRef.current?.stop();
        audioCaptureRef.current = null;
        appendLog("system", { message: "Websocket disconnected." });
        connectionPromiseRef.current = null;
      };
    });

    await connectionPromiseRef.current;
  };

  const disconnect = () => {
    void audioCaptureRef.current?.stop();
    audioCaptureRef.current = null;
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

  const startLiveSession = async () => {
    const nextSessionId = sessionId.trim() || buildDefaultSessionId();
    sequenceRef.current = 0;
    setSessionId(nextSessionId);
    setPartialTranscript("");
    setFinalSegments([]);
    setSummary(null);
    setMicErrorMessage(null);
    updateSessionState("starting");

    try {
      await connect();
      send(createSessionStartEvent(nextSessionId));

      const audioCapture = new BrowserAudioCapture();
      audioCaptureRef.current = audioCapture;
      await audioCapture.start((chunk: BrowserAudioChunk) => {
        const payload: AudioChunkPayload = {
          sessionId: nextSessionId,
          sequence: sequenceRef.current,
          encoding: chunk.encoding,
          sampleRateHz: chunk.sampleRateHz,
          channelCount: chunk.channelCount,
          durationMs: chunk.durationMs,
          dataBase64: chunk.dataBase64,
        };
        sequenceRef.current += 1;
        send(createAudioChunkEvent(payload));
      });
      appendLog("system", { message: "Microphone capture started." });
      updateSessionState("capturing");
    } catch (error) {
      const message = error instanceof Error ? error.message : "Microphone capture failed.";
      setMicErrorMessage(message);
      appendLog("system", { message });
      updateSessionState("error");
      send(createSessionStopEvent(nextSessionId, "capture_failed"));
    }
  };

  const stopLiveSession = async () => {
    const nextSessionId = sessionId.trim();
    if (!nextSessionId) {
      appendLog("system", { message: "Enter a session ID before sending session.stop." });
      return;
    }

    updateSessionState("stopping");
    await audioCaptureRef.current?.stop();
    audioCaptureRef.current = null;
    send(createSessionStopEvent(nextSessionId));
  };

  useEffect(
    () => () => {
      void audioCaptureRef.current?.stop();
      websocketRef.current?.close();
    },
    [],
  );

  return {
    backendHttpUrl: env.backendHttpUrl,
    backendWsUrl: env.backendWsUrl,
    connect,
    connectionStatus,
    disconnect,
    entries,
    finalSegments,
    micErrorMessage,
    partialTranscript,
    setSessionId,
    sessionId,
    sessionState,
    startLiveSession,
    stopLiveSession,
    summary,
  };
}
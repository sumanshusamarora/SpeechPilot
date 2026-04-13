export const PROTOCOL_VERSION = "1.0";

export function createSessionStartEvent(sessionId) {
  return {
    version: PROTOCOL_VERSION,
    timestamp: new Date().toISOString(),
    type: "session.start",
    payload: {
      sessionId,
      client: "web",
      replayMode: false,
    },
  };
}

export function createSessionStopEvent(sessionId) {
  return {
    version: PROTOCOL_VERSION,
    timestamp: new Date().toISOString(),
    type: "session.stop",
    payload: {
      sessionId,
      reason: "manual_stop",
    },
  };
}
export const env = {
  backendHttpUrl:
    process.env.NEXT_PUBLIC_BACKEND_HTTP_URL ?? "http://localhost:8000",
  backendWsUrl:
    process.env.NEXT_PUBLIC_BACKEND_WS_URL ?? "ws://localhost:8000/ws",
  siteUrl:
    process.env.NEXT_PUBLIC_SITE_URL ?? "https://speechpilot.app",
};
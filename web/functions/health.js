// Expose a small health check through the same private service binding used by WebSockets.
export async function onRequest(context) {
  const relay = context.env.RELAY;
  if (!relay) {
    return Response.json({ ok: false, error: "NEXA relay binding is not configured" }, { status: 503 });
  }
  const healthUrl = new URL(context.request.url);
  healthUrl.pathname = "/health";
  healthUrl.search = "";
  return relay.fetch(new Request(healthUrl, context.request));
}

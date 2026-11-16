// Forward browser WebSocket upgrades to the existing relay Worker over a private service binding.
export async function onRequest(context) {
  const relay = context.env.RELAY;
  if (!relay) {
    return new Response("NEXA relay binding is not configured", { status: 503 });
  }
  return relay.fetch(context.request);
}

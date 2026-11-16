const MAX_FRAME_BYTES = 1_500_000;
// Keep an approved pairing session available across an overnight network outage.
const ROOM_TTL_MS = 48 * 60 * 60 * 1000;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/health") {
      return Response.json({ ok: true, service: "nexa-relay-cloudflare" });
    }
    if (url.pathname === "/" || url.pathname === "/viewer") {
      return env.ASSETS.fetch(new Request(new URL("/viewer.html", request.url), request));
    }
    if (url.pathname !== "/signal") return new Response("Not found", { status: 404 });
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("WebSocket upgrade required", { status: 426 });
    }
    const id = env.ROOM_HUB.idFromName("nexa-global-room-hub");
    return env.ROOM_HUB.get(id).fetch(request);
  }
};

export class RoomHub {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.attempts = new Map();
  }

  async fetch(request) {
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    this.state.acceptWebSocket(server);
    server.serializeAttachment({
      role: null,
      code: null,
      createdAt: null,
      ip: request.headers.get("CF-Connecting-IP") || "unknown",
      lastTapAt: 0,
      locked: false
    });
    return new Response(null, { status: 101, webSocket: client });
  }

  sockets() {
    return this.state.getWebSockets();
  }

  attachment(ws) {
    try { return ws.deserializeAttachment() || {}; } catch { return {}; }
  }

  save(ws, data) {
    ws.serializeAttachment(data);
  }

  send(ws, data) {
    if (!ws) return;
    try { ws.send(JSON.stringify(data)); } catch {}
  }

  findRoom(code) {
    let host = null;
    let viewer = null;
    let createdAt = null;
    for (const ws of this.sockets()) {
      const a = this.attachment(ws);
      if (a.code !== code) continue;
      if (a.role === "host") { host = ws; createdAt = a.createdAt; }
      if (a.role === "viewer") viewer = ws;
    }
    return { host, viewer, createdAt };
  }

  async webSocketMessage(ws, message) {
    const a = this.attachment(ws);
    if (typeof message !== "string") {
      const room = a.code ? this.findRoom(a.code) : null;
      if (!room || room.host !== ws || !room.viewer) return;
      if (message.byteLength > MAX_FRAME_BYTES) {
        try { ws.close(1009, "Frame too large"); } catch {}
        return;
      }
      // Drop stale frames instead of allowing a slow viewer to accumulate latency.
      if ((room.viewer.bufferedAmount || 0) > 750_000) return;
      try { room.viewer.send(message); } catch {}
      return;
    }

    let msg;
    try { msg = JSON.parse(message); } catch {
      try { ws.close(1003, "Invalid JSON"); } catch {}
      return;
    }

    if (msg.type === "host" && typeof msg.code === "string" && /^[0-9]{8}$/.test(msg.code)) {
      const now = Date.now();
      const record = this.attempts.get(a.ip) || { count: 0, until: now + 60_000 };
      if (now > record.until) { record.count = 0; record.until = now + 60_000; }
      record.count++;
      this.attempts.set(a.ip, record);
      if (record.count > 30) {
        try { ws.close(1013, "Too many attempts"); } catch {}
        return;
      }
      if (this.findRoom(msg.code).host) {
        this.send(ws, { type: "error", message: "کد تکراری است؛ کد تازه بسازید." });
        return;
      }
      this.save(ws, { ...a, role: "host", code: msg.code, createdAt: now, locked: false });
      this.send(ws, { type: "host_ready" });
      // A viewer may still be connected after the previous host socket dropped.
      // Complete the pairing handshake again when the host reconnects.
      const rejoinedRoom = this.findRoom(msg.code);
      if (rejoinedRoom.viewer) {
        this.send(ws, { type: "viewer_connected" });
        this.send(rejoinedRoom.viewer, { type: "viewer_ready" });
      }
      await this.state.storage.setAlarm(now + ROOM_TTL_MS);
      return;
    }

    if (msg.type === "viewer" && typeof msg.code === "string" && /^[0-9]{8}$/.test(msg.code)) {
      const room = this.findRoom(msg.code);
      if (!room.host || room.viewer) {
        this.send(ws, { type: "error", message: "کد نامعتبر است یا بیننده دیگری متصل است." });
        return;
      }
      this.save(ws, { ...a, role: "viewer", code: msg.code, createdAt: room.createdAt });
      this.send(ws, { type: "viewer_ready" });
      this.send(room.host, { type: "viewer_connected" });
      const hostState = this.attachment(room.host);
      this.send(ws, { type: hostState.locked ? "device_locked" : "device_unlocked" });
      return;
    }

    if (msg.type === "device_locked" || msg.type === "device_unlocked") {
      if (a.role !== "host") return;
      const locked = msg.type === "device_locked";
      this.save(ws, { ...a, locked });
      const room = a.code ? this.findRoom(a.code) : null;
      if (room?.viewer) this.send(room.viewer, { type: locked ? "device_locked" : "device_unlocked" });
      return;
    }

    if (msg.type === "tap") {
      const room = a.code ? this.findRoom(a.code) : null;
      if (!room || room.viewer !== ws) return;
      const now = Date.now();
      if (a.lastTapAt && now - a.lastTapAt < 100) return;
      const x = Number(msg.x), y = Number(msg.y);
      if (!Number.isFinite(x) || !Number.isFinite(y) || x < 0 || x > 1 || y < 0 || y > 1) return;
      this.save(ws, { ...a, lastTapAt: now });
      this.send(room.host, { type: "tap", x, y });
      return;
    }

    if (msg.type === "stop") {
      this.detach(ws, 1000, "Session stopped");
      try { ws.close(1000, "Session stopped"); } catch {}
    }
  }

  detach(ws, code = 1000, reason = "Disconnected") {
    const a = this.attachment(ws);
    if (!a.code) return;
    const room = this.findRoom(a.code);
    if (a.role === "host" && room.host === ws) {
      // Keep the viewer socket alive during a temporary host/network outage.
      // When the host reconnects with the same pairing code, the handshake
      // below notifies both sides and frame delivery resumes without re-entry.
      this.send(room.viewer, { type: "host_disconnected" });
    } else if (a.role === "viewer" && room.viewer === ws) {
      this.send(room.host, { type: "viewer_disconnected" });
    }
    this.save(ws, { ...a, role: null, code: null, createdAt: null });
  }

  async webSocketClose(ws, code, reason) {
    this.detach(ws, code, reason);
  }

  async webSocketError(ws) {
    this.detach(ws, 1011, "WebSocket error");
  }

  async alarm() {
    const now = Date.now();
    for (const ws of this.sockets()) {
      const a = this.attachment(ws);
      if (a.role === "host" && a.createdAt && now - a.createdAt >= ROOM_TTL_MS) {
        const room = this.findRoom(a.code);
        this.send(room.host, { type: "session_expired" });
        this.send(room.viewer, { type: "session_expired" });
        if (room.viewer) { try { room.viewer.close(1000, "Session expired"); } catch {} }
        try { ws.close(1000, "Session expired"); } catch {}
      }
    }
    const hosts = this.sockets().map(ws => this.attachment(ws)).filter(a => a.role === "host" && a.createdAt);
    if (hosts.length) {
      const next = Math.min(...hosts.map(a => a.createdAt + ROOM_TTL_MS));
      await this.state.storage.setAlarm(Math.max(now + 1000, next));
    }
  }
}

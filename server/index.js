const http = require("node:http");
const fs = require("node:fs");
const path = require("node:path");
const { WebSocketServer, WebSocket } = require("ws");

const PORT = Number(process.env.PORT || 8080);
const rooms = new Map();
const attempts = new Map();
const MAX_FRAME_BYTES = 1_500_000;

const server = http.createServer((req, res) => {
  if (req.url === "/health") {
    res.writeHead(200, { "content-type": "application/json" });
    return res.end(JSON.stringify({ ok: true, service: "nexa-relay" }));
  }
  if (req.url === "/" || req.url === "/viewer") {
    res.writeHead(200, { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" });
    return res.end(fs.readFileSync(path.join(__dirname, "public", "viewer.html")));
  }
  res.writeHead(404, { "content-type": "text/plain; charset=utf-8" });
  res.end("Not found\\n");
});

const wss = new WebSocketServer({
  noServer: true,
  maxPayload: MAX_FRAME_BYTES,
  perMessageDeflate: false
});

server.on("upgrade", (req, socket, head) => {
  if (req.url !== "/signal") {
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, ws => wss.emit("connection", ws, req));
});

function send(ws, data) {
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(data));
}

function detach(ws) {
  const room = ws.roomCode && rooms.get(ws.roomCode);
  if (!room) return;
  if (room.host === ws) {
    room.viewer && send(room.viewer, { type: "host_disconnected" });
    room.viewer && room.viewer.close(1000, "Host ended session");
    rooms.delete(ws.roomCode);
  } else if (room.viewer === ws) {
    room.viewer = null;
    send(room.host, { type: "viewer_disconnected" });
  }
  ws.roomCode = null;
}

wss.on("connection", ws => {
  ws.isAlive = true;
  ws.on("pong", () => { ws.isAlive = true; });

  ws.on("message", (raw, isBinary) => {
    if (isBinary) {
      const room = ws.roomCode && rooms.get(ws.roomCode);
      if (!room || room.host !== ws || !room.viewer) return;
      if (raw.length > MAX_FRAME_BYTES) return ws.close(1009, "Frame too large");
      if (room.viewer.readyState === WebSocket.OPEN) room.viewer.send(raw, { binary: true });
      return;
    }

    let msg;
    try { msg = JSON.parse(raw.toString()); } catch { return ws.close(1003, "Invalid JSON"); }

    if (msg.type === "host" && typeof msg.code === "string" && /^[0-9]{8}$/.test(msg.code)) {
      const now = Date.now();
      const key = ws._socket.remoteAddress || "unknown";
      const record = attempts.get(key) || { count: 0, until: now + 60_000 };
      if (now > record.until) { record.count = 0; record.until = now + 60_000; }
      record.count++;
      attempts.set(key, record);
      if (record.count > 30) return ws.close(1013, "Too many attempts");

      if (rooms.has(msg.code)) return send(ws, { type: "error", message: "کد تکراری است؛ کد تازه بسازید." });
      const room = { host: ws, viewer: null, createdAt: now };
      rooms.set(msg.code, room);
      ws.roomCode = msg.code;
      return send(ws, { type: "host_ready" });
    }

    if (msg.type === "viewer" && typeof msg.code === "string" && /^[0-9]{8}$/.test(msg.code)) {
      const room = rooms.get(msg.code);
      if (!room || room.viewer) return send(ws, { type: "error", message: "کد نامعتبر است یا بیننده دیگری متصل است." });
      room.viewer = ws;
      ws.roomCode = msg.code;
      send(ws, { type: "viewer_ready" });
      send(room.host, { type: "viewer_connected" });
      return;
    }

    if (msg.type === "tap") {
      const room = ws.roomCode && rooms.get(ws.roomCode);
      if (!room || room.viewer !== ws) return;
      const now = Date.now();
      if (ws.lastTapAt && now - ws.lastTapAt < 100) return;
      ws.lastTapAt = now;
      const x = Number(msg.x);
      const y = Number(msg.y);
      if (!Number.isFinite(x) || !Number.isFinite(y) || x < 0 || x > 1 || y < 0 || y > 1) return;
      send(room.host, { type: "tap", x, y });
      return;
    }

    if (msg.type === "stop") {
      detach(ws);
      ws.close(1000, "Session stopped");
    }
  });

  ws.on("close", () => detach(ws));
  ws.on("error", () => detach(ws));
});

setInterval(() => {
  const now = Date.now();
  for (const ws of wss.clients) {
    if (!ws.isAlive) { ws.terminate(); continue; }
    ws.isAlive = false;
    ws.ping();
  }
}, 30_000).unref();

server.listen(PORT, "0.0.0.0", () => {
  console.log(`NEXA relay listening on ${PORT}`);
});

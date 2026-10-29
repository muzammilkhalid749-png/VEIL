import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const PORT = Number(process.env.PORT_OVERRIDE || 3000);
const DIST_DIR = path.join(__dirname, 'web', 'dist');
const PUBLIC_DIR = path.join(__dirname, 'web', 'public');

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.mjs': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.apk': 'application/vnd.android.package-archive',
};

const FALLBACK_HTML = `<!doctype html>
<html lang="en" class="dark">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>Veil — Sovereign P2P Encrypted Messenger</title>
  <style>
    :root { color-scheme: dark; }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Inter, sans-serif;
      background: radial-gradient(circle at 20% 0%, #17153b 0%, #0b0d13 55%, #07080c 100%);
      color: #f3f4f6;
      min-height: 100vh;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 24px;
    }
    .card {
      max-width: 760px;
      width: 100%;
      background: rgba(20, 23, 34, 0.88);
      border: 1px solid rgba(124, 77, 255, 0.28);
      border-radius: 24px;
      padding: 32px;
      box-shadow: 0 24px 64px rgba(0, 0, 0, 0.55);
    }
    .badge {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      padding: 6px 14px;
      border-radius: 999px;
      background: rgba(16, 185, 129, 0.14);
      border: 1px solid rgba(16, 185, 129, 0.35);
      color: #34d399;
      font-size: 12px;
      font-weight: 600;
      letter-spacing: 0.04em;
      text-transform: uppercase;
      margin-bottom: 16px;
    }
    h1 {
      font-size: 28px;
      font-weight: 800;
      letter-spacing: -0.02em;
      margin-bottom: 8px;
    }
    p.sub {
      color: #9ca3af;
      font-size: 14.5px;
      line-height: 1.6;
      margin-bottom: 24px;
    }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
      gap: 14px;
    }
    .item {
      background: rgba(255, 255, 255, 0.03);
      border: 1px solid rgba(255, 255, 255, 0.08);
      border-radius: 16px;
      padding: 16px;
    }
    .item h3 {
      font-size: 14px;
      font-weight: 700;
      color: #c4b5fd;
      margin-bottom: 6px;
    }
    .item p {
      font-size: 12.5px;
      color: #9ca3af;
      line-height: 1.5;
    }
  </style>
</head>
<body>
  <div class="card">
    <div class="badge">● Veil Mesh Active • Android & Web Companion</div>
    <h1>Veil Sovereign Messenger</h1>
    <p class="sub">Zero-Knowledge End-to-End Encrypted P2P Messaging, Voice Streaming, Document Transfer, and Multi-Relay Hybrid Sync.</p>
    <div class="grid">
      <div class="item">
        <h3>Smart Media Auto-Download</h3>
        <p>Photos, videos & documents stay blurred until tapped unless Wi-Fi/Cellular auto-download is enabled in Storage Settings. Voice notes auto-download & auto-play sequentially.</p>
      </div>
      <div class="item">
        <h3>Location Picker Flow</h3>
        <p>First-time location permission opens the Location Picker sheet so you can choose Live Location, Current Location, or Nearby Places.</p>
      </div>
      <div class="item">
        <h3>Full Document Engine</h3>
        <p>End-to-end encrypted document sending, receiving, progress tracking, and system viewer opening across P2P and chunked cloud fallback.</p>
      </div>
    </div>
  </div>
</body>
</html>`;

function serveFile(filePath, res) {
  try {
    const stat = fs.statSync(filePath);
    if (!stat.isFile()) return false;
    const ext = path.extname(filePath).toLowerCase();
    const contentType = MIME_TYPES[ext] || 'application/octet-stream';
    res.writeHead(200, {
      'Content-Type': contentType,
      'Cache-Control': 'no-cache',
      'Access-Control-Allow-Origin': '*',
    });
    fs.createReadStream(filePath).pipe(res);
    return true;
  } catch {
    return false;
  }
}

const server = http.createServer((req, res) => {
  const rawUrl = (req.url || '/').split('?')[0];
  const safePath = path.normalize(decodeURIComponent(rawUrl)).replace(/^(\.\.(\/|\\|$))+/, '');

  if (safePath === '/health' || safePath === '/api/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'ok', port: PORT }));
    return;
  }

  // 1. Check built web/dist assets
  if (safePath !== '/' && safePath !== '') {
    const distCandidate = path.join(DIST_DIR, safePath);
    if (serveFile(distCandidate, res)) return;

    const pubCandidate = path.join(PUBLIC_DIR, safePath);
    if (serveFile(pubCandidate, res)) return;
  }

  // 2. Serve web/dist/index.html if available
  const distIndex = path.join(DIST_DIR, 'index.html');
  if (serveFile(distIndex, res)) return;

  // 3. Fallback instant HTML response
  res.writeHead(200, {
    'Content-Type': 'text/html; charset=utf-8',
    'Cache-Control': 'no-cache',
  });
  res.end(FALLBACK_HTML);
});

server.on('error', (err) => {
  if (err && err.code === 'EADDRINUSE') {
    console.log(`Port ${PORT} is already in use; exiting cleanly.`);
    process.exit(0);
  }
  console.error('Server error:', err);
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`Veil server listening on http://0.0.0.0:${PORT}`);
});

// Keep process alive even if stdin closes
process.stdin.resume();
setInterval(() => {}, 60_000);

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
  <title>Veil — Sound Set Audition Studio & Sovereign Messenger</title>
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
      padding: 20px;
    }
    .card {
      max-width: 820px;
      width: 100%;
      background: rgba(20, 23, 34, 0.92);
      border: 1px solid rgba(124, 77, 255, 0.32);
      border-radius: 24px;
      padding: 28px;
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
      margin-bottom: 14px;
    }
    h1 {
      font-size: 24px;
      font-weight: 800;
      letter-spacing: -0.02em;
      margin-bottom: 6px;
    }
    p.sub {
      color: #9ca3af;
      font-size: 13.5px;
      line-height: 1.55;
      margin-bottom: 20px;
    }
    .sets {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    .set-row {
      background: rgba(255, 255, 255, 0.035);
      border: 1px solid rgba(255, 255, 255, 0.09);
      border-radius: 16px;
      padding: 15px 18px;
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      transition: border-color 0.2s, background 0.2s;
    }
    .set-row.active {
      border-color: #8b5cf6;
      background: rgba(139, 92, 246, 0.09);
    }
    .set-info {
      flex: 1 1 280px;
    }
    .set-title {
      font-size: 15px;
      font-weight: 700;
      color: #e5e7eb;
      margin-bottom: 4px;
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .tag {
      font-size: 11px;
      font-weight: 600;
      padding: 2px 8px;
      border-radius: 6px;
      background: rgba(139, 92, 246, 0.2);
      color: #c4b5fd;
    }
    .set-desc {
      font-size: 12.5px;
      color: #9ca3af;
      line-height: 1.45;
    }
    .btn-group {
      display: flex;
      gap: 8px;
      flex-wrap: wrap;
    }
    button {
      cursor: pointer;
      border: 1px solid rgba(255, 255, 255, 0.14);
      background: rgba(255, 255, 255, 0.06);
      color: #f9fafb;
      font-size: 12.5px;
      font-weight: 600;
      padding: 8px 13px;
      border-radius: 10px;
      transition: all 0.15s ease;
    }
    button:hover {
      background: rgba(139, 92, 246, 0.25);
      border-color: #8b5cf6;
      transform: translateY(-1px);
    }
    button:active {
      transform: translateY(0);
    }
    button.combo {
      background: linear-gradient(135deg, #7c3aed, #4f46e5);
      border-color: transparent;
    }
    button.combo:hover {
      filter: brightness(1.12);
    }
  </style>
</head>
<body>
  <div class="card">
    <div class="badge">🔊 Interactive 44.1 kHz PCM Sound Audition Studio</div>
    <h1>Test & Compare All 5 Send / Receive Sound Sets</h1>
    <p class="sub">Click the buttons below to listen to each psychoacoustic sound set right here, or open any chat in the Android app → tap <strong>⋮ Menu → 🔊 Sound Sets (Play & Choose)</strong>.</p>
    <div class="sets">
      <div class="set-row active" id="row-1">
        <div class="set-info">
          <div class="set-title">Set 1: Organic Bubble & Kalimba <span class="tag">WhatsApp / Nature</span></div>
          <div class="set-desc">Send: 58ms hollow water-droplet pop (265→685 Hz) • Receive: 96ms warm wooden C5→G5 Perfect Fifth</div>
        </div>
        <div class="btn-group">
          <button onclick="playSound(1, true)">▶ Send</button>
          <button onclick="playSound(1, false)">▶ Receive</button>
          <button class="combo" onclick="playCombo(1)">▶ Send + Receive</button>
        </div>
      </div>

      <div class="set-row" id="row-2">
        <div class="set-info">
          <div class="set-title">Set 2: Silk Air-Whoosh & Dewdrop <span class="tag">iMessage / Sleek</span></div>
          <div class="set-desc">Send: 72ms velvet air-whoosh glide (340→880 Hz) • Receive: 85ms crystalline A5 dewdrop ping</div>
        </div>
        <div class="btn-group">
          <button onclick="playSound(2, true)">▶ Send</button>
          <button onclick="playSound(2, false)">▶ Receive</button>
          <button class="combo" onclick="playCombo(2)">▶ Send + Receive</button>
        </div>
      </div>

      <div class="set-row" id="row-3">
        <div class="set-info">
          <div class="set-title">Set 3: Micro-Snap & Twin Crystal <span class="tag">Telegram / Ultra-Fast</span></div>
          <div class="set-desc">Send: 38ms punchy tactile micro-pop (185→555 Hz) • Receive: 78ms crisp ascending F5→A5 Major Third</div>
        </div>
        <div class="btn-group">
          <button onclick="playSound(3, true)">▶ Send</button>
          <button onclick="playSound(3, false)">▶ Receive</button>
          <button class="combo" onclick="playCombo(3)">▶ Send + Receive</button>
        </div>
      </div>

      <div class="set-row" id="row-4">
        <div class="set-info">
          <div class="set-title">Set 4: Rosewood Tap & Velvet Triad <span class="tag">Cozy Studio</span></div>
          <div class="set-desc">Send: 48ms deep muted rosewood knock (210→420 Hz) • Receive: 110ms rolled D-Major harp triad (D5→F#5→A5)</div>
        </div>
        <div class="btn-group">
          <button onclick="playSound(4, true)">▶ Send</button>
          <button onclick="playSound(4, false)">▶ Receive</button>
          <button class="combo" onclick="playCombo(4)">▶ Send + Receive</button>
        </div>
      </div>

      <div class="set-row" id="row-5">
        <div class="set-info">
          <div class="set-title">Set 5: Studio Pulse & Octave Ping <span class="tag">Minimalist / Late-Night</span></div>
          <div class="set-desc">Send: 42ms whisper-clean sine pulse (300→600 Hz) • Receive: 68ms crisp octave double-ping (E5→E6)</div>
        </div>
        <div class="btn-group">
          <button onclick="playSound(5, true)">▶ Send</button>
          <button onclick="playSound(5, false)">▶ Receive</button>
          <button class="combo" onclick="playCombo(5)">▶ Send + Receive</button>
        </div>
      </div>
    </div>
  </div>

  <script>
    let audioCtx = null;
    function getCtx() {
      if (!audioCtx) {
        audioCtx = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 44100 });
      }
      if (audioCtx.state === 'suspended') audioCtx.resume();
      return audioCtx;
    }

    function highlightRow(setIdx) {
      for (let i = 1; i <= 5; i++) {
        const el = document.getElementById('row-' + i);
        if (el) el.classList.toggle('active', i === setIdx);
      }
    }

    function synthSent(setIdx, sr) {
      if (setIdx === 2) {
        const N = Math.floor(sr * 0.072), out = new Float32Array(N);
        let phase = 0, lpNoise = 0;
        for (let i = 0; i < N; i++) {
          const t = i / sr, p = i / N;
          const freq = 340 + 540 * Math.pow(p, 1.35);
          phase += (2 * Math.PI * freq) / sr;
          const attack = t < 0.005 ? 0.5 * (1 - Math.cos(Math.PI * (t / 0.005))) : 1;
          const env = attack * Math.sin(Math.PI * p) * Math.exp(-t / 0.042);
          lpNoise = lpNoise * 0.82 + (Math.random() * 2 - 1) * 0.18;
          const air = lpNoise * Math.sin(Math.PI * p) * 0.16;
          out[i] = ((Math.sin(phase) * 0.92 + Math.sin(phase * 2) * 0.08) * env + air) * 0.9;
        }
        return out;
      } else if (setIdx === 3) {
        const N = Math.floor(sr * 0.038), out = new Float32Array(N);
        let phase = 0, snap = 0;
        for (let i = 0; i < N; i++) {
          const t = i / sr, p = i / N;
          const freq = 185 + 370 * (1 - Math.exp(-t / 0.0075));
          phase += (2 * Math.PI * freq) / sr;
          snap += (2 * Math.PI * 1320) / sr;
          const attack = t < 0.0015 ? (t / 0.0015) : 1;
          const decay = Math.exp(-t / 0.011) * (1 - p * p);
          out[i] = (Math.sin(phase) * attack * decay + Math.sin(snap) * Math.exp(-t / 0.0012) * 0.25) * 0.92;
        }
        return out;
      } else if (setIdx === 4) {
        const N = Math.floor(sr * 0.048), out = new Float32Array(N);
        let phase = 0, wood = 0;
        for (let i = 0; i < N; i++) {
          const t = i / sr, p = i / N;
          const freq = 210 + 210 * (1 - Math.exp(-t / 0.009));
          phase += (2 * Math.PI * freq) / sr;
          wood += (2 * Math.PI * 630) / sr;
          const attack = t < 0.002 ? 0.5 * (1 - Math.cos(Math.PI * (t / 0.002))) : 1;
          const decay = Math.exp(-t / 0.014) * (1 - p * p);
          out[i] = (Math.sin(phase) * 0.92 * attack * decay + Math.sin(wood) * Math.exp(-t / 0.0035) * 0.22) * 0.92;
        }
        return out;
      } else if (setIdx === 5) {
        const N = Math.floor(sr * 0.042), out = new Float32Array(N);
        let phase = 0;
        for (let i = 0; i < N; i++) {
          const t = i / sr, p = i / N;
          const freq = 300 + 300 * (1 - Math.exp(-t / 0.0095));
          phase += (2 * Math.PI * freq) / sr;
          const attack = t < 0.0022 ? 0.5 * (1 - Math.cos(Math.PI * (t / 0.0022))) : 1;
          const decay = Math.exp(-t / 0.0135) * (1 - Math.pow(p, 2.5));
          out[i] = Math.sin(phase) * attack * decay * 0.9;
        }
        return out;
      } else {
        const N = Math.floor(sr * 0.058), out = new Float32Array(N);
        let phase = 0, clickPhase = 0;
        for (let i = 0; i < N; i++) {
          const t = i / sr, p = i / N;
          const freq = 265 + 420 * (1 - Math.exp(-t / 0.011));
          phase += (2 * Math.PI * freq) / sr;
          clickPhase += (2 * Math.PI * 1080) / sr;
          const attack = t < 0.0025 ? 0.5 * (1 - Math.cos(Math.PI * (t / 0.0025))) : 1;
          const decay = Math.exp(-t / 0.0165) * (1 - Math.pow(p, 3));
          const body = Math.sin(phase) * 0.94 + Math.sin(phase * 2) * 0.06;
          const click = Math.sin(clickPhase) * Math.exp(-t / 0.0016) * 0.18;
          out[i] = (body * attack * decay + click) * 0.92;
        }
        return out;
      }
    }

    function synthRecv(setIdx, sr) {
      if (setIdx === 2) {
        const N = Math.floor(sr * 0.085), out = new Float32Array(N);
        let phase = 0;
        for (let i = 0; i < N; i++) {
          const t = i / sr, p = i / N;
          phase += (2 * Math.PI * 880) / sr;
          const attack = t < 0.002 ? 0.5 * (1 - Math.cos(Math.PI * (t / 0.002))) : 1;
          const decay = Math.exp(-t / 0.022) * (1 - Math.pow(p, 3));
          const sub = Math.sin(phase * 0.5) * 0.22 * Math.exp(-t / 0.015);
          const shimmer = Math.sin(phase * 2) * 0.14 * Math.exp(-t / 0.005);
          out[i] = (Math.sin(phase) * 0.84 + sub + shimmer) * attack * decay * 0.92;
        }
        return out;
      } else if (setIdx === 3) {
        const N = Math.floor(sr * 0.078), out = new Float32Array(N);
        let p1 = 0, p2 = 0;
        for (let i = 0; i < N; i++) {
          const t = i / sr;
          let v = 0;
          p1 += (2 * Math.PI * 698.46) / sr;
          v += Math.sin(p1) * (t < 0.0018 ? t / 0.0018 : 1) * Math.exp(-t / 0.011) * 0.78;
          if (t >= 0.022) {
            const t2 = t - 0.022;
            p2 += (2 * Math.PI * 880) / sr;
            v += (Math.sin(p2) * 0.9 + Math.sin(p2 * 2) * 0.1 * Math.exp(-t2 / 0.004)) * (t2 < 0.0018 ? t2 / 0.0018 : 1) * Math.exp(-t2 / 0.016) * 0.96;
          }
          out[i] = v * 0.92;
        }
        return out;
      } else if (setIdx === 4) {
        const N = Math.floor(sr * 0.110), out = new Float32Array(N);
        let p1 = 0, p2 = 0, p3 = 0;
        for (let i = 0; i < N; i++) {
          const t = i / sr;
          let v = 0;
          p1 += (2 * Math.PI * 587.33) / sr;
          v += Math.sin(p1) * (t < 0.002 ? t / 0.002 : 1) * Math.exp(-t / 0.016) * 0.62;
          if (t >= 0.018) {
            const t2 = t - 0.018;
            p2 += (2 * Math.PI * 739.99) / sr;
            v += Math.sin(p2) * (t2 < 0.002 ? t2 / 0.002 : 1) * Math.exp(-t2 / 0.018) * 0.68;
          }
          if (t >= 0.036) {
            const t3 = t - 0.036;
            p3 += (2 * Math.PI * 880) / sr;
            v += (Math.sin(p3) * 0.92 + Math.sin(p3 * 2) * 0.08 * Math.exp(-t3 / 0.005)) * (t3 < 0.002 ? t3 / 0.002 : 1) * Math.exp(-t3 / 0.022) * 0.85;
          }
          out[i] = v * 0.92;
        }
        return out;
      } else if (setIdx === 5) {
        const N = Math.floor(sr * 0.068), out = new Float32Array(N);
        let p1 = 0, p2 = 0;
        for (let i = 0; i < N; i++) {
          const t = i / sr;
          let v = 0;
          p1 += (2 * Math.PI * 659.25) / sr;
          v += Math.sin(p1) * (t < 0.002 ? t / 0.002 : 1) * Math.exp(-t / 0.011) * 0.8;
          if (t >= 0.020) {
            const t2 = t - 0.020;
            p2 += (2 * Math.PI * 1318.51) / sr;
            v += Math.sin(p2) * (t2 < 0.002 ? t2 / 0.002 : 1) * Math.exp(-t2 / 0.014) * 0.85;
          }
          out[i] = v * 0.9;
        }
        return out;
      } else {
        const N = Math.floor(sr * 0.096), out = new Float32Array(N);
        let p1 = 0, p2 = 0;
        for (let i = 0; i < N; i++) {
          const t = i / sr;
          let v = 0;
          p1 += (2 * Math.PI * 523.25) / sr;
          const a1 = t < 0.002 ? 0.5 * (1 - Math.cos(Math.PI * (t / 0.002))) : 1;
          v += (Math.sin(p1) * 0.9 + Math.sin(p1 * 2) * 0.1 * Math.exp(-t / 0.005)) * a1 * Math.exp(-t / 0.013) * 0.78;
          if (t >= 0.032) {
            const t2 = t - 0.032;
            const tp = t2 / (0.096 - 0.032);
            p2 += (2 * Math.PI * 783.99) / sr;
            const a2 = t2 < 0.0022 ? 0.5 * (1 - Math.cos(Math.PI * (t2 / 0.0022))) : 1;
            const d2 = Math.exp(-t2 / 0.019) * (1 - Math.pow(Math.min(1, tp), 3));
            v += (Math.sin(p2) * 0.92 + Math.sin(p2 * 2) * 0.12 * Math.exp(-t2 / 0.006)) * a2 * d2 * 0.96;
          }
          out[i] = v * 0.92;
        }
        return out;
      }
    }

    function playSound(setIdx, isSend) {
      highlightRow(setIdx);
      const ctx = getCtx();
      const samples = isSend ? synthSent(setIdx, ctx.sampleRate) : synthRecv(setIdx, ctx.sampleRate);
      const buf = ctx.createBuffer(1, samples.length, ctx.sampleRate);
      buf.getChannelData(0).set(samples);
      const src = ctx.createBufferSource();
      src.buffer = buf;
      src.connect(ctx.destination);
      src.start();
    }

    function playCombo(setIdx) {
      playSound(setIdx, true);
      setTimeout(() => playSound(setIdx, false), 380);
    }
  </script>
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

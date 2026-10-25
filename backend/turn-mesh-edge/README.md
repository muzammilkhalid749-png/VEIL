# VEIL Zero-Log TURN/STUN & Blind Dead-Drop Edge Server

Production RFC 5389 / RFC 5766 TURN relay service configured for **100% Zero-Disk-Logging**, **RAM-only session quotas**, **TLS 1.3 on port 443**, and **Strict Peer IP Isolation** to shield against SSRF.

---

## 1. Security Architecture

1. **RFC 5389 Time-Limited Ephemeral Credentials:**
   - Every client generates a cryptographically signed pair:
     - `username = <timestamp>:<peer_salt>`
     - `password = Base64(HmacSHA1(username, static_auth_secret))`
   - Credentials auto-expire after 12 hours. Unauthorized third parties cannot reuse or probe the server.
2. **100% Zero-Disk-Logging:**
   - `no-stdout-log` and `syslog` disabled.
   - `log-file=/dev/null` ensures zero IP addresses, timestamps, or relay metadata ever touch persistent storage.
   - Container logging driver set to `none`.
3. **CGNAT & Stateful Firewall Traversal:**
   - TLS 1.3 listener on Port 443 (`tls-listening-port=443`) disguises TURN relay packets as standard HTTPS traffic, penetrating restrictive cellular CGNAT and corporate proxies.
4. **Strict Peer IP Isolation (Anti-SSRF):**
   - Explicitly rejects relay packets addressed to RFC 1918 private subnets (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`), loopback (`127.0.0.0/8`), link-local (`169.254.0.0/16`), and multicast networks.

---

## 2. Quick Start Deployment

### Prerequisites
- Docker & Docker Compose
- Open ports on host: `3478/tcp+udp`, `443/tcp+udp`, `5349/tcp+udp`, `49152-49200/udp`

### Step 1: Generate or Install TLS Certificates
For testing / private edge:
```bash
chmod +x generate-certs.sh
./generate-certs.sh
```

For production Let's Encrypt:
```bash
certbot certonly --standalone -d turn.veil-mesh.net
mkdir -p certs
cp /etc/letsencrypt/live/turn.veil-mesh.net/fullchain.pem certs/veil_turn.crt
cp /etc/letsencrypt/live/turn.veil-mesh.net/privkey.pem certs/veil_turn.key
```

### Step 2: Configure Shared Secret
Ensure `static-auth-secret` in `turnserver.conf` matches `TurnCredentialManager.java`:
```ini
static-auth-secret=veil_sec_mesh_turn_2026_zero_log_prod_secret
```

### Step 3: Start Edge Node
```bash
docker-compose up -d
```

### Step 4: Verify STUN/TURN Health
```bash
# Test STUN binding
docker exec -it veil-turn-edge turnutils_stunclient 127.0.0.1 3478
```

#!/bin/bash
# ═══════════════════════════════════════════════════════════════════════════════
# VEIL Zero-Log TURN Server Self-Signed TLS 1.3 Certificate Generator
# For production, replace with Let's Encrypt certificates.
# ═══════════════════════════════════════════════════════════════════════════════

set -e

mkdir -p certs
cd certs

if [ ! -f "veil_turn.key" ] || [ ! -f "veil_turn.crt" ]; then
    echo "Generating 4096-bit RSA TLS 1.3 Certificate for veil-mesh.net..."
    openssl req -x509 -newkey rsa:4096 -sha256 -days 3650 -nodes \
        -keyout veil_turn.key -out veil_turn.crt \
        -subj "/CN=turn.veil-mesh.net/O=VEIL Mesh Project/C=US" \
        -addext "subjectAltName=DNS:turn.veil-mesh.net,DNS:eu-turn.veil-mesh.net,DNS:us-turn.veil-mesh.net,DNS:ap-turn.veil-mesh.net,IP:127.0.0.1"

    chmod 600 veil_turn.key
    chmod 644 veil_turn.crt
    echo "Certificate and private key generated in ./certs/"
else
    echo "Certificates already exist in ./certs/."
fi

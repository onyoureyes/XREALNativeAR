#!/bin/bash
# XREAL Backup Server - Steam Deck Setup Script
# Installs: Tailscale + FastAPI backup server + systemd service
# Run this on the Steam Deck in Desktop Mode

set -e

INSTALL_DIR="$HOME/xreal-backup"
SERVICE_NAME="xreal-backup"

echo "========================================"
echo "  XREAL Backup Server Setup"
echo "  (Tailscale + FastAPI + systemd)"
echo "========================================"
echo "Install dir: $INSTALL_DIR"
echo ""

# ── Step 1: Tailscale ──

echo "=== [1/5] Tailscale Setup ==="
if command -v tailscale &> /dev/null; then
    echo "[OK] Tailscale already installed"
else
    echo "[...] Installing Tailscale..."
    curl -fsSL https://tailscale.com/install.sh | sh
    echo "[OK] Tailscale installed"
fi

# Check if Tailscale is running
if tailscale status &> /dev/null 2>&1; then
    TAILSCALE_IP=$(tailscale ip -4 2>/dev/null || echo "")
    echo "[OK] Tailscale connected: $TAILSCALE_IP"
else
    echo "[...] Starting Tailscale login..."
    echo "    A browser will open. Log in with your account."
    echo "    (Use the SAME account on your Android phone)"
    echo ""
    sudo systemctl enable --now tailscaled
    sudo tailscale up
    TAILSCALE_IP=$(tailscale ip -4 2>/dev/null || echo "unknown")
    echo "[OK] Tailscale connected: $TAILSCALE_IP"
fi
echo ""

# ── Step 2: Server files ──

echo "=== [2/5] Server Files ==="
mkdir -p "$INSTALL_DIR"
cp backup_server.py "$INSTALL_DIR/"
cp requirements.txt "$INSTALL_DIR/"
echo "[OK] Server files copied"

# ── Step 3: Configuration ──

echo "=== [3/5] Configuration ==="
if [ ! -f "$INSTALL_DIR/.env" ]; then
    API_KEY=$(python3 -c "import secrets; print(secrets.token_urlsafe(32))")
    cat > "$INSTALL_DIR/.env" << EOF
XREAL_API_KEY=$API_KEY
XREAL_DB_PATH=$INSTALL_DIR/backup.db
XREAL_HOST=0.0.0.0
XREAL_PORT=8090
EOF
    echo "[OK] Generated new API key"
else
    echo "[OK] .env exists, keeping existing config"
    API_KEY=$(grep XREAL_API_KEY "$INSTALL_DIR/.env" | cut -d= -f2)
fi

# ── Step 4: Python dependencies ──

echo "=== [4/5] Python Dependencies ==="
pip3 install --user -r requirements.txt 2>/dev/null || pip install --user -r requirements.txt
echo "[OK] Dependencies installed"

# ── Step 5: systemd service ──

echo "=== [5/5] systemd Service ==="
mkdir -p "$HOME/.config/systemd/user"
cat > "$HOME/.config/systemd/user/${SERVICE_NAME}.service" << EOF
[Unit]
Description=XREAL Backup Server
After=network-online.target tailscaled.service
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=$INSTALL_DIR
ExecStart=$(which python3) $INSTALL_DIR/backup_server.py
Restart=on-failure
RestartSec=10
Environment=PATH=$HOME/.local/bin:/usr/bin:/bin

[Install]
WantedBy=default.target
EOF

systemctl --user daemon-reload
systemctl --user enable "$SERVICE_NAME"
systemctl --user start "$SERVICE_NAME"

# Enable lingering so service runs even without login
loginctl enable-linger $(whoami) 2>/dev/null || true

echo "[OK] Service started and enabled on boot"
echo ""

# ── Summary ──

TAILSCALE_IP=$(tailscale ip -4 2>/dev/null || echo "<tailscale-ip>")
LOCAL_IP=$(hostname -I | awk '{print $1}')

echo "========================================"
echo "  Setup Complete!"
echo "========================================"
echo ""
echo "Server management:"
echo "  Status:   systemctl --user status $SERVICE_NAME"
echo "  Logs:     journalctl --user -u $SERVICE_NAME -f"
echo "  Restart:  systemctl --user restart $SERVICE_NAME"
echo ""
echo "Health check:"
echo "  curl http://localhost:8090/api/status"
echo ""
echo "========================================"
echo "  Android App Configuration"
echo "========================================"
echo ""
echo "  Server URL:  http://$TAILSCALE_IP:8090"
echo "  API Key:     $API_KEY"
echo ""
echo "  (Tailscale IP is fixed - works from anywhere)"
echo "  (Local IP: $LOCAL_IP - only works on same network)"
echo ""
echo "========================================"
echo "  Android Phone: Install Tailscale"
echo "========================================"
echo "  1. Play Store → 'Tailscale' 설치"
echo "  2. 같은 계정으로 로그인"
echo "  3. 연결 확인: ping $TAILSCALE_IP"
echo "========================================"

#!/bin/bash
# Installs a desktop launcher for the Nill OS - Wardriver desktop app, so it
# shows up in your applications menu (and on the Desktop) with its icon.
# Linux/XDG. Re-run any time; it just rewrites the entry.
set -e
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN="$DIR/run.sh"
ICON="$DIR/nillos-wardriver.png"
chmod +x "$RUN"

APPS="$HOME/.local/share/applications"
mkdir -p "$APPS"
ENTRY="$APPS/nillos-wardriver.desktop"

cat > "$ENTRY" <<EOF
[Desktop Entry]
Type=Application
Version=1.0
Name=Nill OS - Wardriver
GenericName=Wardrive log manager
Comment=Pull rig logs, build field reports, flash and manage the ESP boards
Exec=$RUN
Icon=$ICON
Terminal=false
Categories=Utility;Network;
Keywords=wardrive;wifi;bluetooth;esp32;flock;
EOF
chmod +x "$ENTRY"

# Put a copy on the Desktop too, if there is one.
DESK="$(xdg-user-dir DESKTOP 2>/dev/null || echo "$HOME/Desktop")"
if [ -d "$DESK" ]; then
    cp "$ENTRY" "$DESK/nillos-wardriver.desktop"
    chmod +x "$DESK/nillos-wardriver.desktop"
    # Mark trusted so GNOME/Nautilus launches it without the "untrusted" prompt.
    gio set "$DESK/nillos-wardriver.desktop" metadata::trusted true 2>/dev/null || true
fi

update-desktop-database "$APPS" 2>/dev/null || true
echo "Installed: $ENTRY"
[ -d "$DESK" ] && echo "Desktop icon: $DESK/nillos-wardriver.desktop"
echo "Look for \"Nill OS - Wardriver\" in your app menu."

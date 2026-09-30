#!/bin/bash
# One-click launcher for the Nill OS - Wardriver desktop app. Creates the
# Python venv and installs dependencies on first run, then launches the GUI.
set -e
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PY="$DIR/venv/bin/python3"

if [ ! -x "$PY" ]; then
    echo "First run: setting up the Python environment..."
    python3 -m venv "$DIR/venv"
    "$DIR/venv/bin/pip" install --quiet --upgrade pip
    "$DIR/venv/bin/pip" install --quiet -r "$DIR/requirements.txt"
fi

exec "$PY" "$DIR/organize_wardrive.py"

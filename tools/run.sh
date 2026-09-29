#!/bin/bash
# One-click launcher for the Wardrive Organizer GUI.
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$DIR/venv/bin/python3" "$DIR/organize_wardrive.py"

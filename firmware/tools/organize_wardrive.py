#!/usr/bin/env python3
"""
Wardrive CSV Organizer - one-button tool.

Finds every wifi_*.csv / ble_*.csv session file from the wardrive rig's SD
card, combines them into a single spreadsheet, and removes duplicates:
  - exact duplicate rows (the same observation appearing twice)
  - the same device (MAC) seen across multiple separate drives/sessions -
    collapsed to one row (its first sighting), with a "TimesSeen" column
    showing how many total observations that device actually had

Run it: tools/venv/bin/python3 tools/organize_wardrive.py
(or double-click run.sh in the same folder)
"""

import glob
import os
import sys
import tkinter as tk
from datetime import datetime
from tkinter import filedialog, messagebox

import pandas as pd

WIGLE_COLUMNS = [
    "MAC", "SSID", "AuthMode", "FirstSeen", "Channel", "Frequency",
    "RSSI", "CurrentLatitude", "CurrentLongitude", "AltitudeMeters",
    "AccuracyMeters", "Type",
]

OUTPUT_DIR = os.path.expanduser("~/Wardrive_Reports")


def find_session_folder():
    """Look for a mounted SD card's wardrive/ folder automatically. Returns
    the path if exactly one candidate is found, otherwise None (caller
    falls back to letting the user pick)."""
    candidates = glob.glob("/media/*/*/wardrive") + glob.glob("/run/media/*/*/wardrive")
    candidates = [c for c in candidates if os.path.isdir(c)]
    if len(candidates) == 1:
        return candidates[0]
    return None


def load_one_csv(path):
    """Reads a single WigleWifi-1.6 session file: line 1 is metadata (not a
    header), line 2 is the real column header, the rest is data."""
    df = pd.read_csv(path, skiprows=1, dtype=str, keep_default_na=False)
    # Guard against a header-only file (no data rows) or a file that
    # doesn't actually match the expected columns - skip it rather than
    # letting one odd file break the whole run.
    if list(df.columns) != WIGLE_COLUMNS:
        return None
    if df.empty:
        return df
    df["SourceFile"] = os.path.basename(path)
    return df


def process_folder(folder):
    files = sorted(glob.glob(os.path.join(folder, "*.csv")))
    if not files:
        raise ValueError(f"No .csv files found in:\n{folder}")

    frames = []
    skipped = []
    for path in files:
        try:
            df = load_one_csv(path)
            if df is None:
                skipped.append(os.path.basename(path))
                continue
            frames.append(df)
        except Exception as e:
            skipped.append(f"{os.path.basename(path)} ({e})")

    if not frames:
        raise ValueError("No usable data rows found in any file - they may all be header-only.")

    combined = pd.concat(frames, ignore_index=True)
    raw_row_count = len(combined)

    # Step 1: drop exact duplicate observations (every column identical).
    combined = combined.drop_duplicates(
        subset=[c for c in combined.columns if c != "SourceFile"], keep="first"
    )
    after_exact_dedup = len(combined)

    # Sort chronologically so "first sighting" (used below) is meaningful,
    # and so the final sheet reads in the order things were actually found.
    combined = combined.sort_values("FirstSeen", kind="stable")

    # Step 2: collapse to one row per device (MAC), keeping its earliest
    # sighting, but recording how many total observations it had.
    times_seen = combined.groupby("MAC").size().rename("TimesSeen")
    unique = combined.drop_duplicates(subset=["MAC"], keep="first").merge(
        times_seen, on="MAC", how="left"
    )
    unique = unique.sort_values("FirstSeen", kind="stable").reset_index(drop=True)

    stats = {
        "files_processed": len(frames),
        "files_skipped": skipped,
        "raw_rows": raw_row_count,
        "after_exact_dedup": after_exact_dedup,
        "unique_devices": len(unique),
    }
    return unique, stats


def run_organizer():
    folder = find_session_folder()
    if not folder:
        folder = filedialog.askdirectory(
            title="Select the 'wardrive' folder from the SD card"
        )
        if not folder:
            return  # user cancelled

    try:
        unique, stats = process_folder(folder)
    except Exception as e:
        messagebox.showerror("Wardrive Organizer", f"Couldn't process that folder:\n\n{e}")
        return

    os.makedirs(OUTPUT_DIR, exist_ok=True)
    out_name = f"wardrive_master_{datetime.now().strftime('%Y-%m-%d_%H%M')}.xlsx"
    out_path = os.path.join(OUTPUT_DIR, out_name)
    unique.to_excel(out_path, index=False, sheet_name="Wardrive Devices")

    if stats["raw_rows"] == 0:
        messagebox.showwarning(
            "Wardrive Organizer",
            f"Found {stats['files_processed']} session file(s) in:\n{folder}\n\n"
            "But every one of them is header-only - no APs or BLE devices were "
            "actually logged in any of them (this is normal for bench-test files "
            "captured before the rig had a GPS fix).\n\n"
            "Go for an actual drive with the rig capturing, then run this again.",
        )
        return

    summary = (
        f"Source folder:\n{folder}\n\n"
        f"Files processed: {stats['files_processed']}\n"
        f"Files skipped (empty/bad format): {len(stats['files_skipped'])}\n\n"
        f"Raw observations: {stats['raw_rows']}\n"
        f"After removing exact duplicates: {stats['after_exact_dedup']}\n"
        f"Unique devices in final sheet: {stats['unique_devices']}\n\n"
        f"Saved to:\n{out_path}"
    )
    if messagebox.askyesno("Wardrive Organizer - Done", summary + "\n\nOpen the file now?"):
        if sys.platform.startswith("linux"):
            os.system(f'xdg-open "{out_path}" &')
        elif sys.platform == "darwin":
            os.system(f'open "{out_path}"')
        else:
            os.startfile(out_path)  # Windows


def main():
    root = tk.Tk()
    root.title("Wardrive Organizer")
    root.geometry("420x220")
    root.resizable(False, False)

    tk.Label(
        root, text="Wardrive CSV Organizer", font=("Sans", 16, "bold")
    ).pack(pady=(24, 4))
    tk.Label(
        root,
        text="Combines every session file into one spreadsheet,\nremoves duplicates, saves to ~/Wardrive_Reports.",
        justify="center",
    ).pack(pady=(0, 20))

    tk.Button(
        root,
        text="Organize Wardrive Data",
        font=("Sans", 12, "bold"),
        bg="#2e7d32",
        fg="white",
        padx=20,
        pady=12,
        command=run_organizer,
    ).pack()

    root.mainloop()


if __name__ == "__main__":
    main()

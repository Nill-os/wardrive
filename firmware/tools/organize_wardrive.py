#!/usr/bin/env python3
"""
Nill OS Wardriver - Upload & Organize (one-button PC tool).

Plug the rig's microSD card into the PC, open this, click **Upload**. It pulls
every session file off the card and sorts everything into a tidy, dated folder
tree under ~/Wardrive_Reports, so nothing stays as a loose pile of CSVs:

  ~/Wardrive_Reports/upload_<date>_<time>/
    sessions/                 raw session files copied off the card,
      2026-09-29/               grouped into one folder per drive date
        wifi_20260929_143416.csv
        ble_20260929_143416.csv
    combined/
      all_networks.wigle.csv  every unique device, WigleWifi-1.6 format,
                                ready to hand to WiGLE's web uploader
      wifi_only.csv           just the WiFi APs (same format)
      ble_only.csv            just the BLE/other devices
      all_devices.xlsx        the master spreadsheet, with a TimesSeen column
    summary.txt

Nothing is deleted from the card - it only ever copies off it. The rig still
does its own automatic WiGLE / wdgwars uploads; this is for organizing and
keeping your own copy.

Run it: tools/venv/bin/python3 tools/organize_wardrive.py
(or double-click run.sh in the same folder)
"""

import glob
import os
import re
import shutil
import string
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

# First line of a WigleWifi-1.6 file - WiGLE's web uploader reads this to know
# the format. The rig and app write their own; we write a generic one here for
# the combined outputs so they upload cleanly as one merged file.
WIGLE_HEADER = ("WigleWifi-1.6,appRelease=NillOSWardriver,model=Rig,release=1.0,"
                "device=ESP32,display=none,board=ESP32,brand=NillOS")

OUTPUT_ROOT = os.path.expanduser("~/Wardrive_Reports")

# Pulls the YYYYMMDD date out of a session filename like wifi_20260929_143416.csv
# or phone_20260929_100129.csv, so files get grouped by the day they were driven.
DATE_IN_NAME = re.compile(r"(\d{4})(\d{2})(\d{2})_\d{6}")


def find_session_folder():
    """Look for a mounted SD card's wardrive/ folder automatically across
    Linux, macOS and Windows. Returns the path if exactly one candidate is
    found, otherwise None (the caller falls back to letting the user pick)."""
    candidates = []
    # Linux (udisks) and macOS.
    candidates += glob.glob("/media/*/*/wardrive") + glob.glob("/run/media/*/*/wardrive")
    candidates += glob.glob("/Volumes/*/wardrive")
    # Windows: a wardrive folder at the root of any drive letter.
    if os.name == "nt":
        for letter in string.ascii_uppercase:
            p = f"{letter}:\\wardrive"
            if os.path.isdir(p):
                candidates.append(p)
    candidates = [c for c in candidates if os.path.isdir(c)]
    # De-dup while preserving order.
    seen, unique = set(), []
    for c in candidates:
        if c not in seen:
            seen.add(c)
            unique.append(c)
    return unique[0] if len(unique) == 1 else None


def date_for_file(path):
    """The drive date (YYYY-MM-DD) for a session file, from its name if it has
    a timestamp, else from the file's own modified time as a fallback."""
    m = DATE_IN_NAME.search(os.path.basename(path))
    if m:
        return f"{m.group(1)}-{m.group(2)}-{m.group(3)}"
    return datetime.fromtimestamp(os.path.getmtime(path)).strftime("%Y-%m-%d")


def load_one_csv(path):
    """Reads a single WigleWifi-1.6 session file: line 1 is metadata (not a
    header), line 2 is the real column header, the rest is data."""
    df = pd.read_csv(path, skiprows=1, dtype=str, keep_default_na=False)
    if list(df.columns) != WIGLE_COLUMNS:
        return None
    if df.empty:
        return df
    df["SourceFile"] = os.path.basename(path)
    return df


def dedupe(frames):
    """Combine session frames and collapse to one row per device (MAC), keeping
    the earliest sighting and a TimesSeen count. Returns (unique_df, stats)."""
    combined = pd.concat(frames, ignore_index=True)
    raw_row_count = len(combined)

    combined = combined.drop_duplicates(
        subset=[c for c in combined.columns if c != "SourceFile"], keep="first"
    )
    after_exact_dedup = len(combined)
    combined = combined.sort_values("FirstSeen", kind="stable")

    times_seen = combined.groupby("MAC").size().rename("TimesSeen")
    unique = combined.drop_duplicates(subset=["MAC"], keep="first").merge(
        times_seen, on="MAC", how="left"
    )
    unique = unique.sort_values("FirstSeen", kind="stable").reset_index(drop=True)

    stats = {
        "raw_rows": raw_row_count,
        "after_exact_dedup": after_exact_dedup,
        "unique_devices": len(unique),
    }
    return unique, stats


def write_wigle_csv(df, path):
    """Write a DataFrame's WIGLE_COLUMNS as a WigleWifi-1.6 file WiGLE accepts."""
    with open(path, "w", newline="", encoding="utf-8") as f:
        f.write(WIGLE_HEADER + "\n")
        df[WIGLE_COLUMNS].to_csv(f, index=False)


def organize(folder):
    """Copy every session CSV off the card into a dated folder tree and write
    the combined outputs. Returns (output_dir, stats)."""
    files = sorted(glob.glob(os.path.join(folder, "*.csv")))
    if not files:
        raise ValueError(f"No .csv files found in:\n{folder}")

    out_dir = os.path.join(OUTPUT_ROOT, "upload_" + datetime.now().strftime("%Y-%m-%d_%H%M"))
    sessions_dir = os.path.join(out_dir, "sessions")
    combined_dir = os.path.join(out_dir, "combined")
    os.makedirs(sessions_dir, exist_ok=True)
    os.makedirs(combined_dir, exist_ok=True)

    frames, skipped, copied = [], [], 0
    for path in files:
        # Copy the raw file into sessions/<date>/, regardless of whether it has
        # usable rows - it's still part of the record of that drive.
        day_dir = os.path.join(sessions_dir, date_for_file(path))
        os.makedirs(day_dir, exist_ok=True)
        try:
            shutil.copy2(path, os.path.join(day_dir, os.path.basename(path)))
            copied += 1
        except Exception as e:
            skipped.append(f"{os.path.basename(path)} (copy failed: {e})")
            continue
        try:
            df = load_one_csv(path)
            if df is None or df.empty:
                continue
            frames.append(df)
        except Exception as e:
            skipped.append(f"{os.path.basename(path)} ({e})")

    stats = {"files_copied": copied, "files_skipped": skipped,
             "raw_rows": 0, "after_exact_dedup": 0, "unique_devices": 0,
             "wifi": 0, "ble": 0}

    if frames:
        unique, dstats = dedupe(frames)
        stats.update(dstats)
        wifi = unique[unique["Type"] == "WIFI"]
        ble = unique[unique["Type"] != "WIFI"]
        stats["wifi"], stats["ble"] = len(wifi), len(ble)

        write_wigle_csv(unique, os.path.join(combined_dir, "all_networks.wigle.csv"))
        if not wifi.empty:
            write_wigle_csv(wifi, os.path.join(combined_dir, "wifi_only.csv"))
        if not ble.empty:
            write_wigle_csv(ble, os.path.join(combined_dir, "ble_only.csv"))
        unique.to_excel(os.path.join(combined_dir, "all_devices.xlsx"),
                        index=False, sheet_name="Wardrive Devices")

    write_summary(out_dir, folder, stats)
    return out_dir, stats


def write_summary(out_dir, source, stats):
    lines = [
        "Nill OS Wardriver - upload summary",
        datetime.now().strftime("%Y-%m-%d %H:%M"),
        "",
        f"Source card folder: {source}",
        f"Session files copied: {stats['files_copied']}",
        f"Files skipped (empty/bad format): {len(stats['files_skipped'])}",
        "",
        f"Raw observations: {stats['raw_rows']}",
        f"After removing exact duplicates: {stats['after_exact_dedup']}",
        f"Unique devices: {stats['unique_devices']}  (WiFi {stats['wifi']}, BLE/other {stats['ble']})",
    ]
    if stats["files_skipped"]:
        lines += ["", "Skipped:"] + [f"  - {s}" for s in stats["files_skipped"]]
    with open(os.path.join(out_dir, "summary.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def open_path(path):
    if sys.platform.startswith("linux"):
        os.system(f'xdg-open "{path}" &')
    elif sys.platform == "darwin":
        os.system(f'open "{path}"')
    else:
        os.startfile(path)  # Windows


def run_upload():
    folder = find_session_folder()
    if not folder:
        folder = filedialog.askdirectory(
            title="Select the 'wardrive' folder on the rig's SD card"
        )
        if not folder:
            return  # user cancelled

    try:
        out_dir, stats = organize(folder)
    except Exception as e:
        messagebox.showerror("Nill OS Wardriver", f"Couldn't process that folder:\n\n{e}")
        return

    if stats["unique_devices"] == 0:
        messagebox.showwarning(
            "Nill OS Wardriver",
            f"Copied {stats['files_copied']} session file(s) into:\n{out_dir}\n\n"
            "But none of them had any logged APs or BLE devices (normal for "
            "bench-test files captured before the rig had a GPS fix). Go for an "
            "actual drive with the rig capturing, then run this again.",
        )
        open_path(out_dir)
        return

    summary = (
        f"Card folder:\n{folder}\n\n"
        f"Session files copied: {stats['files_copied']}\n"
        f"Raw observations: {stats['raw_rows']}\n"
        f"Unique devices: {stats['unique_devices']} "
        f"(WiFi {stats['wifi']}, BLE/other {stats['ble']})\n\n"
        f"Organized into:\n{out_dir}"
    )
    if messagebox.askyesno("Nill OS Wardriver - Done", summary + "\n\nOpen the folder now?"):
        open_path(out_dir)


def main():
    root = tk.Tk()
    root.title("Nill OS Wardriver")
    root.geometry("460x240")
    root.resizable(False, False)

    tk.Label(root, text="Nill OS Wardriver", font=("Sans", 16, "bold")).pack(pady=(24, 4))
    tk.Label(
        root,
        text="Plug in the rig's SD card and click Upload.\n"
             "Everything is copied off and sorted into dated folders\n"
             "under ~/Wardrive_Reports, with a combined WiGLE-ready CSV.",
        justify="center",
    ).pack(pady=(0, 18))

    tk.Button(
        root, text="Upload", font=("Sans", 13, "bold"),
        bg="#00838f", fg="white", padx=28, pady=12, command=run_upload,
    ).pack()

    root.mainloop()


if __name__ == "__main__":
    main()

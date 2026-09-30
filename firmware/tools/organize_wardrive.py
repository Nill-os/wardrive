#!/usr/bin/env python3
"""
Nill OS Wardriver - Upload & Field Report (one-button PC tool).

Plug the rig's microSD card into the PC, open this, click **Upload**. It pulls
every session file off the card, de-duplicates, and builds a full field report
under ~/Wardrive_Reports:

  ~/Wardrive_Reports/report_<date>_<time>/
    report.html               interactive field report - browse, filter,
                                search and view every device on a map
    sessions/2026-09-29/...   raw session files, grouped by drive date
    by-type/     wifi.csv  ble.csv  cell.csv
    by-band/     2.4GHz.csv  5GHz.csv  6GHz.csv
    by-security/ OPEN.csv  WEP.csv  WPA2.csv  WPA3.csv ...
    combined/    all_networks.wigle.csv (WiGLE-ready)  all_devices.xlsx
    summary.txt

Nothing is deleted from the card. The rig still does its own automatic WiGLE /
wdgwars uploads; this is for keeping, organizing and exploring your own copy.

Run it: tools/venv/bin/python3 tools/organize_wardrive.py
(or double-click run.sh in the same folder)
"""

import base64
import glob
import html
import json
import os
import re
import shutil
import string
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import tkinter as tk
import tkinter.font as tkfont
from datetime import datetime
from tkinter import filedialog, ttk

import pandas as pd

WIGLE_COLUMNS = [
    "MAC", "SSID", "AuthMode", "FirstSeen", "Channel", "Frequency",
    "RSSI", "CurrentLatitude", "CurrentLongitude", "AltitudeMeters",
    "AccuracyMeters", "Type",
]
WIGLE_HEADER = ("WigleWifi-1.6,appRelease=NillOSWardriver,model=Rig,release=1.0,"
                "device=ESP32,display=none,board=ESP32,brand=NillOS")
OUTPUT_ROOT = os.path.expanduser("~/Wardrive_Reports")
DATE_IN_NAME = re.compile(r"(\d{4})(\d{2})(\d{2})_\d{6}")


# ---------- SD discovery ----------
def find_session_folder():
    """Find a mounted SD card's wardrive/ folder (Linux, macOS, Windows).
    Returns the path if exactly one candidate is found, else None."""
    candidates = glob.glob("/media/*/*/wardrive") + glob.glob("/run/media/*/*/wardrive")
    candidates += glob.glob("/Volumes/*/wardrive")
    if os.name == "nt":
        for letter in string.ascii_uppercase:
            p = f"{letter}:\\wardrive"
            if os.path.isdir(p):
                candidates.append(p)
    seen, unique = set(), []
    for c in candidates:
        if os.path.isdir(c) and c not in seen:
            seen.add(c)
            unique.append(c)
    return unique[0] if len(unique) == 1 else None


def date_for_file(path):
    m = DATE_IN_NAME.search(os.path.basename(path))
    if m:
        return f"{m.group(1)}-{m.group(2)}-{m.group(3)}"
    return datetime.fromtimestamp(os.path.getmtime(path)).strftime("%Y-%m-%d")


# ---------- download over WiFi (no SD-card removal) ----------
def download_from_rig(host, password, dest, progress=lambda s: None):
    """Pull every session CSV off the rig over its service-mode web server, so
    you never have to remove the SD card. The rig must be in service mode
    (Settings -> RIG SERVICE MODE in the app, or CFG -> NETWORK & DEBUG on the
    rig). Returns the number of files downloaded. Raises on connection failure."""
    base = "http://" + host.strip().rstrip("/")

    def _get(url):
        req = urllib.request.Request(url)
        if password:
            tok = base64.b64encode(("wardrive:" + password).encode()).decode()
            req.add_header("Authorization", "Basic " + tok)
        return urllib.request.urlopen(req, timeout=12)

    progress(f"Connecting to {host}...")
    try:
        index = _get(base + "/").read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        if e.code == 401:
            raise ValueError("The rig needs the service-mode password (set it in the box above).")
        raise ValueError(f"The rig answered HTTP {e.code}. Is it in service mode?")
    except Exception as e:
        raise ValueError(f"Couldn't reach the rig at {host}.\nStart service mode on the rig first.\n\n({e})")

    names = sorted(set(re.findall(r"dl\?f=([^'\"<> ]+\.csv)", index)))
    if not names:
        return 0  # connected fine, but nothing logged yet - the caller shows a friendly note, not an error
    os.makedirs(dest, exist_ok=True)
    for i, name in enumerate(names, 1):
        progress(f"Downloading {name} ({i}/{len(names)})...")
        safe = os.path.basename(name)  # never let a path escape dest
        data = _get(base + "/dl?f=" + urllib.parse.quote(name)).read()
        with open(os.path.join(dest, safe), "wb") as f:
            f.write(data)
    return len(names)


# ---------- read over the CYD's USB serial (no SD-card removal, no WiFi) ----------
def _serial_ports():
    import glob as _g
    if os.name == "nt":
        return [f"COM{i}" for i in range(1, 33)]
    return sorted(_g.glob("/dev/ttyUSB*") + _g.glob("/dev/ttyACM*")
                  + _g.glob("/dev/tty.usbserial*") + _g.glob("/dev/cu.usb*"))


def _import_serial():
    try:
        import serial  # pyserial
        return serial
    except ImportError:
        raise ValueError("pyserial isn't installed.\nIn the tool's venv:  pip install pyserial")


def _open_cyd(serial, port):
    """Open a CYD serial port. On this board forcing DTR/RTS low pulses the
    auto-reset line, so we open with pyserial's defaults (which don't reset it)
    and just give it a moment in case a particular adapter does bounce."""
    s = serial.Serial(port, 115200, timeout=2)
    time.sleep(2.0)          # ride out any adapter-specific reset before talking
    s.reset_input_buffer()
    return s


def _probe_is_cyd(serial, port):
    try:
        s = _open_cyd(serial, port)
    except Exception:
        return False
    try:
        s.reset_input_buffer()
        s.write(b"sd list\n"); s.flush()
        end = time.time() + 3
        while time.time() < end:
            line = s.readline().decode("utf-8", "replace")
            if line.startswith("SDLIST") or line.strip() == "SDLISTEND":
                return True
        return False
    except Exception:
        return False
    finally:
        s.close()


def _port_descriptions():
    """Map port path -> USB description/hwid (from pyserial), used as a chip
    hint for a board that isn't running wardrive firmware yet."""
    try:
        from serial.tools import list_ports
        out = {}
        for p in list_ports.comports():
            bits = [b for b in (getattr(p, "description", ""), getattr(p, "hwid", "")) if b and b != "n/a"]
            out[p.device] = " ".join(bits)
        return out
    except Exception:
        return {}


_WD_ID_RE = re.compile(r"WD:ID\s+role=(\w+)\s+idx=(\d+)\s+n=(\d+)")
_ROLE_LABEL = {"wifi": "WiFi sniffer", "ble": "BLE scanner", "cyd": "CYD screen board"}


def detect_board(serial, port, seconds=4.0):
    """Listen on a port for a board's WD:ID identity line (its role and which
    node it is) and return a dict describing it. Falls back to the older boot
    banners / status line. role is None if nothing identifiable was heard."""
    info = {"port": port, "role": None, "index": None, "count": None}
    try:
        # Default open (no forced DTR/RTS) as with the CYD; if a board does
        # reset on open it just reprints its banner, which we still catch.
        s = serial.Serial(port, 115200, timeout=1)
    except Exception as e:
        info["error"] = str(e)
        return info
    try:
        time.sleep(0.3)
        s.reset_input_buffer()
        end = time.time() + seconds
        while time.time() < end:
            line = s.readline().decode("utf-8", "replace").strip()
            if not line:
                continue
            m = _WD_ID_RE.search(line)
            if m:
                info["role"], info["index"], info["count"] = m.group(1), int(m.group(2)), int(m.group(3))
                return info
            low = line.lower()
            if "wifi_node ready" in low:
                info["role"] = "wifi"
            elif "ble_node ready" in low:
                info["role"] = "ble"
            elif line.startswith("WD:STATUS"):
                info["role"] = "cyd"
        return info
    except Exception as e:
        info["error"] = str(e)
        return info
    finally:
        try:
            s.close()
        except Exception:
            pass


def describe_board(info, descriptions):
    """Human one-liner for a detect_board() result."""
    role = info.get("role")
    if role in _ROLE_LABEL:
        label = _ROLE_LABEL[role]
        if role in ("wifi", "ble") and info.get("count"):
            n = info["count"]
            label += (f", node {info['index']} of {n}" if n > 1 else " (single node)")
        return label
    if info.get("error"):
        return "couldn't open (" + info["error"] + ")"
    hint = descriptions.get(info["port"], "")
    return "not running wardrive firmware" + (f" [{hint}]" if hint else "")


def read_from_cyd_serial(port, dest, progress=lambda s: None):
    """Pull every session CSV off the rig over the CYD's USB serial cable - no
    WiFi, no SD-card removal. `port` may be a device path or 'auto'."""
    serial = _import_serial()
    if not port or port.strip().lower() == "auto":
        progress("Looking for the rig on a serial port...")
        found = next((p for p in _serial_ports() if _probe_is_cyd(serial, p)), None)
        if not found:
            raise ValueError("Couldn't find the rig on any serial port.\n"
                             "Plug the CYD in over USB and try again (or type its port).")
        port = found
    progress(f"Opening {port}...")
    try:
        s = _open_cyd(serial, port)
    except Exception as e:
        raise ValueError(f"Couldn't open {port}: {e}")
    try:
        s.reset_input_buffer()
        s.write(b"sd list\n"); s.flush()
        names, end = [], time.time() + 8
        while time.time() < end:
            line = s.readline().decode("utf-8", "replace").strip()
            if line.startswith("SDLIST name="):
                names.append(line.split("name=", 1)[1].split(" size=")[0])
            elif line == "SDLISTEND":
                break
        if not names:
            return 0  # connected fine, but nothing logged yet - the caller shows a friendly note, not an error
        os.makedirs(dest, exist_ok=True)
        for i, nm in enumerate(names, 1):
            progress(f"Reading {nm} ({i}/{len(names)})...")
            s.reset_input_buffer()
            s.write(("sd get " + nm + "\n").encode()); s.flush()
            rows, started, end = [], False, time.time() + 45
            while time.time() < end:
                line = s.readline().decode("utf-8", "replace").rstrip("\r\n")
                if not line:
                    continue
                if line.startswith("SDBEGIN "):
                    started = True
                elif line.startswith("SDEND"):
                    break
                elif line.startswith("SDERR"):
                    raise ValueError("Rig error reading " + nm + ": " + line)
                elif started and line.startswith("SDROW "):
                    rows.append(line[6:])
            with open(os.path.join(dest, os.path.basename(nm)), "w", encoding="utf-8", newline="\n") as f:
                f.write("\n".join(rows) + ("\n" if rows else ""))
        return len(names)
    finally:
        s.close()


def smart_get_source():
    """Pick the easiest available log source for the one-click 'get my logs'
    button, so the user doesn't have to know which of SD/USB/WiFi to use.
    Returns ("sd", folder) or ("usb", port), or (None, None) if neither is
    plugged in (WiFi still needs a manual address, so it's not auto-picked)."""
    folder = find_session_folder()
    if folder:
        return ("sd", folder)
    try:
        serial = _import_serial()
        for p in _serial_ports():
            if _probe_is_cyd(serial, p):
                return ("usb", p)
    except Exception:
        pass
    return (None, None)


# ---------- CSV load + dedup ----------
def load_one_csv(path):
    df = pd.read_csv(path, skiprows=1, dtype=str, keep_default_na=False)
    if list(df.columns) != WIGLE_COLUMNS:
        return None
    if df.empty:
        return df
    df["SourceFile"] = os.path.basename(path)
    return df


def dedupe(frames):
    combined = pd.concat(frames, ignore_index=True)
    raw = len(combined)
    combined = combined.drop_duplicates(
        subset=[c for c in combined.columns if c != "SourceFile"], keep="first")
    after = len(combined)
    combined = combined.sort_values("FirstSeen", kind="stable")
    times = combined.groupby("MAC").size().rename("TimesSeen")
    unique = combined.drop_duplicates(subset=["MAC"], keep="first").merge(times, on="MAC", how="left")
    unique = unique.sort_values("FirstSeen", kind="stable").reset_index(drop=True)
    return unique, {"raw_rows": raw, "after_exact_dedup": after, "unique_devices": len(unique)}


def write_wigle_csv(df, path):
    if df.empty:
        return
    with open(path, "w", newline="", encoding="utf-8") as f:
        f.write(WIGLE_HEADER + "\n")
        df[WIGLE_COLUMNS].to_csv(f, index=False)


# ---------- categorization ----------
def band_of(freq_str):
    try:
        f = int(float(freq_str))
    except (ValueError, TypeError):
        return None
    if 2400 <= f <= 2500:
        return "2.4GHz"
    if 4900 <= f <= 5900:
        return "5GHz"
    if f > 5900:
        return "6GHz"
    return None


def security_of(auth):
    a = (auth or "").upper()
    if "WPA3" in a:
        return "WPA3"
    if "WPA2" in a:
        return "WPA2"
    if "WPA" in a:
        return "WPA"
    if "WEP" in a:
        return "WEP"
    if "OWE" in a:
        return "OWE"
    if a in ("", "OPEN", "[ESS]", "NONE"):
        return "OPEN"
    return None  # BLE / cell etc.


# Notable-device detection, ported from the phone app's DeviceSignatureDetection
# for the fields a session CSV actually carries: the MAC (OUI prefix) and the
# name/SSID. The app's live scan can also use BLE manufacturer IDs and service
# UUIDs (companyId 0x09C8 for Flock, Remote ID / Meshtastic UUIDs) which aren't
# in the CSV, so those extra signals only fire on the phone - but the MAC-OUI
# and name signals below catch Flock cameras, Flipper Zeros, skimmers and the
# rest straight from the rig's own logs, even when no phone was connected.
_FLOCK_MAC = ("B4:1E:52", "00:03:7F")
_FLIPPER_MAC = ("0C:FA:22", "80:E1:26", "80:E1:27")
_SKIMMER_NAMES = {"HC-05", "HC-06", "HC-08", "HC-03", "FREE2MOVE"}


def detection_of(mac, name, typ):
    """Return a human label for a notable device, or None."""
    m = (mac or "").upper()
    n = (name or "").strip()
    nl = n.lower()
    if typ == "WIFI":
        return "WiFi Pineapple" if "pineapple" in nl else None
    # BLE / other radios:
    if m.startswith(_FLOCK_MAC) or nl.startswith("penguin-") or nl in ("fs battery", "dfutarg"):
        return "Flock camera"
    if m.startswith(_FLIPPER_MAC) or nl.startswith("flipper"):
        return "Flipper Zero"
    if n.upper() in _SKIMMER_NAMES:
        return "BLE skimmer"
    if nl.startswith("ray-ban") or nl.startswith("meta"):
        return "Smart glasses"
    if nl.startswith("gopro") or nl.startswith("insta360"):
        return "Action camera"
    if nl.startswith("axon"):
        return "Police camera"
    return None


# ---------- interactive HTML field report ----------
def build_report(unique, out_dir, stats):
    """Write a self-contained interactive field report: a Leaflet map of every
    located device plus a searchable, filterable, sortable table."""
    records = []
    for _, r in unique.iterrows():
        try:
            lat = float(r["CurrentLatitude"]); lon = float(r["CurrentLongitude"])
        except (ValueError, TypeError):
            lat = lon = 0.0
        typ = r["Type"] or "?"
        records.append({
            "mac": r["MAC"], "ssid": r["SSID"], "type": typ,
            "sec": security_of(r["AuthMode"]) or ("BLE" if typ == "BLE" else "-"),
            "band": band_of(r["Frequency"]) or "-",
            "ch": r["Channel"], "rssi": r["RSSI"],
            "lat": round(lat, 6), "lon": round(lon, 6),
            "seen": int(r["TimesSeen"]) if str(r["TimesSeen"]).isdigit() else 1,
            "first": r["FirstSeen"],
            "det": detection_of(r["MAC"], r["SSID"], typ) or "",
        })

    wifi = sum(1 for x in records if x["type"] == "WIFI")
    ble = sum(1 for x in records if x["type"] == "BLE")
    cell = sum(1 for x in records if x["type"] not in ("WIFI", "BLE"))
    located = sum(1 for x in records if x["lat"] or x["lon"])
    dets = sorted({x["det"] for x in records if x["det"]})
    det_count = sum(1 for x in records if x["det"])
    det_opts = "".join(f"<option>{html.escape(d)}</option>" for d in dets)

    data_json = json.dumps(records, separators=(",", ":"))
    html_doc = _REPORT_TEMPLATE
    html_doc = html_doc.replace("/*__DATA__*/", data_json)
    html_doc = html_doc.replace("__WIFI__", str(wifi)).replace("__BLE__", str(ble))
    html_doc = html_doc.replace("__CELL__", str(cell)).replace("__LOCATED__", str(located))
    html_doc = html_doc.replace("__TOTAL__", str(len(records)))
    html_doc = html_doc.replace("__DETCOUNT__", str(det_count))
    html_doc = html_doc.replace("<!--__DETOPTS__-->", det_opts)
    html_doc = html_doc.replace("__WHEN__", datetime.now().strftime("%Y-%m-%d %H:%M"))
    path = os.path.join(out_dir, "report.html")
    with open(path, "w", encoding="utf-8") as f:
        f.write(html_doc)
    return path


# The report page. Leaflet + markercluster from cdnjs (needs internet for the
# map tiles, like any web map); the device data is embedded so the file works
# offline for browsing/filtering. Table rendering is capped so even tens of
# thousands of devices stay responsive.
_REPORT_TEMPLATE = r"""<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Nill OS - Wardriver field report</title>
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.css">
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/leaflet.markercluster/1.5.3/MarkerCluster.min.css">
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/leaflet.markercluster/1.5.3/MarkerCluster.Default.min.css">
<style>
:root{--bg:#0A0A12;--panel:#12121c;--cyan:#00F0FF;--purple:#9D00FF;--dim:#7A7A92;--green:#22C55E;--red:#FF3333;--amber:#F59E0B}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:#E8E8F0;font-family:ui-monospace,Menlo,Consolas,monospace}
h1{color:var(--cyan);font-size:20px;margin:0 0 2px}
header{padding:14px 18px;border-bottom:1px solid #23233a}
.sub{color:var(--dim);font-size:12px}
.cards{display:flex;flex-wrap:wrap;gap:10px;padding:12px 18px}
.card{background:var(--panel);border:1px solid #23233a;border-radius:6px;padding:8px 14px;min-width:110px}
.card .n{font-size:22px;color:var(--cyan)} .card.p .n{color:var(--purple)} .card.d .n{color:var(--amber)} .card .l{color:var(--dim);font-size:11px}
.det{color:var(--amber);font-weight:bold}
#map{height:340px;margin:0 18px;border:1px solid #23233a;border-radius:6px}
.controls{display:flex;flex-wrap:wrap;gap:8px;padding:12px 18px}
input,select{background:var(--panel);color:#E8E8F0;border:1px solid #33334d;border-radius:5px;padding:7px 9px;font:inherit;font-size:13px}
input#q{flex:1;min-width:180px}
.wrap{padding:0 18px 24px}
table{width:100%;border-collapse:collapse;font-size:12px}
th,td{text-align:left;padding:6px 8px;border-bottom:1px solid #1c1c2c;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:260px}
th{color:var(--cyan);cursor:pointer;position:sticky;top:0;background:var(--bg)}
th:hover{color:#fff}
tr:hover td{background:#15151f}
.count{color:var(--dim);font-size:12px;padding:6px 18px}
.badge{padding:1px 6px;border-radius:4px;font-size:11px}
.WIFI{color:var(--cyan)} .BLE{color:var(--purple)} .CELL{color:var(--amber)}
a{color:var(--cyan)}
</style></head><body>
<header>
  <h1>&gt; NILL OS - WARDRIVER</h1>
  <div class="sub">Field report &middot; __WHEN__ &middot; local, nothing uploaded from this page</div>
</header>
<div class="cards">
  <div class="card"><div class="n">__TOTAL__</div><div class="l">DEVICES</div></div>
  <div class="card"><div class="n">__WIFI__</div><div class="l">WIFI</div></div>
  <div class="card p"><div class="n">__BLE__</div><div class="l">BLUETOOTH</div></div>
  <div class="card"><div class="n">__CELL__</div><div class="l">CELL</div></div>
  <div class="card"><div class="n">__LOCATED__</div><div class="l">ON MAP</div></div>
  <div class="card d"><div class="n">__DETCOUNT__</div><div class="l">NOTABLE</div></div>
</div>
<div id="map"></div>
<div class="controls">
  <input id="q" placeholder="Search SSID or MAC...">
  <select id="ft"><option value="">All types</option><option>WIFI</option><option>BLE</option><option>CELL</option></select>
  <select id="fb"><option value="">All bands</option><option>2.4GHz</option><option>5GHz</option><option>6GHz</option></select>
  <select id="fs"><option value="">All security</option><option>OPEN</option><option>WEP</option><option>WPA</option><option>WPA2</option><option>WPA3</option><option>OWE</option></select>
  <select id="fd"><option value="">All devices</option><option value="__ANY__">Notable only</option><!--__DETOPTS__--></select>
</div>
<div class="count" id="count"></div>
<div class="wrap"><table id="tbl"><thead><tr>
  <th data-k="ssid">SSID</th><th data-k="mac">MAC</th><th data-k="type">Type</th>
  <th data-k="sec">Security</th><th data-k="band">Band</th><th data-k="ch">Ch</th>
  <th data-k="rssi">RSSI</th><th data-k="seen">Seen</th><th data-k="det">Notable</th><th data-k="first">First seen</th>
</tr></thead><tbody id="tb"></tbody></table></div>
<script src="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/leaflet.markercluster/1.5.3/leaflet.markercluster.min.js"></script>
<script>
var DATA=/*__DATA__*/;
var CAP=1000; // max table rows drawn at once, for responsiveness
var sortK="seen", sortDir=-1;

// ---- map ----
var located=DATA.filter(function(d){return d.lat||d.lon});
var map=L.map('map',{preferCanvas:true});
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'&copy; OpenStreetMap'}).addTo(map);
var color={WIFI:'#00F0FF',BLE:'#9D00FF',CELL:'#F59E0B'};
var cluster=L.markerClusterGroup({chunkedLoading:true,maxClusterRadius:50});
located.forEach(function(d){
  var notable=!!d.det;
  var c=notable?'#F59E0B':(color[d.type]||'#22C55E');
  var m=L.circleMarker([d.lat,d.lon],{radius:notable?8:5,color:notable?'#F59E0B':'#fff',weight:notable?2:1,fillColor:c,fillOpacity:.85});
  var extra=notable?'<br><b style="color:#F59E0B">'+esc(d.det)+'</b>':'';
  m.bindPopup('<b>'+esc(d.ssid||'(hidden)')+'</b><br>'+d.mac+'<br>'+d.type+' &middot; '+d.sec+' &middot; '+d.band+'<br>ch '+d.ch+' &middot; '+d.rssi+' dBm &middot; seen '+d.seen+extra);
  // Notable devices go on their own always-visible layer so they never hide inside a cluster.
  if(notable){m.addTo(map);}else{cluster.addLayer(m);}
});
map.addLayer(cluster);
if(located.length){map.fitBounds(L.latLngBounds(located.map(function(d){return [d.lat,d.lon];})).pad(0.1));}else{map.setView([20,0],2);}

function esc(s){return (s||'').replace(/[&<>"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c];});}

// ---- table ----
function filtered(){
  var q=document.getElementById('q').value.toLowerCase();
  var ft=document.getElementById('ft').value, fb=document.getElementById('fb').value;
  var fs=document.getElementById('fs').value, fd=document.getElementById('fd').value;
  var out=DATA.filter(function(d){
    if(ft && d.type!==ft) return false;
    if(fb && d.band!==fb) return false;
    if(fs && d.sec!==fs) return false;
    if(fd==='__ANY__' && !d.det) return false;
    if(fd && fd!=='__ANY__' && d.det!==fd) return false;
    if(q && (d.ssid||'').toLowerCase().indexOf(q)<0 && d.mac.toLowerCase().indexOf(q)<0) return false;
    return true;
  });
  out.sort(function(a,b){
    var x=a[sortK],y=b[sortK];
    if(typeof x==='number'&&typeof y==='number') return (x-y)*sortDir;
    return String(x).localeCompare(String(y))*sortDir;
  });
  return out;
}
function render(){
  var rows=filtered();
  var tb=document.getElementById('tb'); tb.innerHTML='';
  var frag=document.createDocumentFragment();
  rows.slice(0,CAP).forEach(function(d){
    var tr=document.createElement('tr');
    tr.innerHTML='<td>'+esc(d.ssid||'(hidden)')+'</td><td>'+d.mac+'</td>'
      +'<td class="'+d.type+'">'+d.type+'</td><td>'+d.sec+'</td><td>'+d.band+'</td>'
      +'<td>'+d.ch+'</td><td>'+d.rssi+'</td><td>'+d.seen+'</td>'
      +'<td class="det">'+esc(d.det)+'</td><td>'+esc(d.first)+'</td>';
    frag.appendChild(tr);
  });
  tb.appendChild(frag);
  document.getElementById('count').textContent=
    'Showing '+Math.min(rows.length,CAP)+' of '+rows.length+' matching device(s)'+(rows.length>CAP?' (refine filters to see the rest)':'');
}
['q','ft','fb','fs','fd'].forEach(function(id){document.getElementById(id).addEventListener('input',render);});
document.querySelectorAll('th').forEach(function(th){th.addEventListener('click',function(){
  var k=th.getAttribute('data-k'); sortDir=(sortK===k)?-sortDir:1; sortK=k; render();
});});
render();
</script></body></html>"""


# ---------- orchestration ----------
def open_path(path):
    if sys.platform.startswith("linux"):
        os.system(f'xdg-open "{path}" &')
    elif sys.platform == "darwin":
        os.system(f'open "{path}"')
    else:
        os.startfile(path)  # Windows


def organize(folder, progress=lambda s: None):
    files = sorted(glob.glob(os.path.join(folder, "*.csv")))
    if not files:
        raise ValueError(f"No .csv files found in:\n{folder}")

    out_dir = os.path.join(OUTPUT_ROOT, "report_" + datetime.now().strftime("%Y-%m-%d_%H%M"))
    sessions_dir = os.path.join(out_dir, "sessions")
    for sub in ("sessions", "by-type", "by-band", "by-security", "combined"):
        os.makedirs(os.path.join(out_dir, sub), exist_ok=True)

    progress("Organizing session files...")
    frames, skipped, copied = [], [], 0
    for path in files:
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
            if df is not None and not df.empty:
                frames.append(df)
        except Exception as e:
            skipped.append(f"{os.path.basename(path)} ({e})")

    stats = {"files_copied": copied, "files_skipped": skipped, "raw_rows": 0,
             "after_exact_dedup": 0, "unique_devices": 0, "wifi": 0, "ble": 0, "cell": 0, "notable": 0}

    if frames:
        progress("De-duplicating...")
        unique, dstats = dedupe(frames)
        stats.update(dstats)
        wifi = unique[unique["Type"] == "WIFI"]
        ble = unique[unique["Type"] == "BLE"]
        cell = unique[~unique["Type"].isin(["WIFI", "BLE"])]
        stats["wifi"], stats["ble"], stats["cell"] = len(wifi), len(ble), len(cell)

        progress("Writing categorized CSVs...")
        write_wigle_csv(unique, os.path.join(out_dir, "combined", "all_networks.wigle.csv"))
        write_wigle_csv(wifi, os.path.join(out_dir, "by-type", "wifi.csv"))
        write_wigle_csv(ble, os.path.join(out_dir, "by-type", "ble.csv"))
        write_wigle_csv(cell, os.path.join(out_dir, "by-type", "cell.csv"))
        # by band
        bands = unique["Frequency"].map(band_of)
        for b in ["2.4GHz", "5GHz", "6GHz"]:
            write_wigle_csv(unique[bands == b], os.path.join(out_dir, "by-band", b + ".csv"))
        # by security (WiFi only)
        secs = wifi["AuthMode"].map(security_of)
        for s in ["OPEN", "WEP", "WPA", "WPA2", "WPA3", "OWE"]:
            write_wigle_csv(wifi[secs == s], os.path.join(out_dir, "by-security", s + ".csv"))
        # notable devices (Flock cameras, Flipper Zeros, skimmers, Pineapples, ...)
        det = unique.apply(lambda r: detection_of(r["MAC"], r["SSID"], r["Type"]), axis=1)
        notable = unique[det.notna()]
        stats["notable"] = len(notable)
        if not notable.empty:
            os.makedirs(os.path.join(out_dir, "notable"), exist_ok=True)
            for label in sorted(det.dropna().unique()):
                safe = re.sub(r"[^A-Za-z0-9]+", "_", label).strip("_")
                write_wigle_csv(unique[det == label], os.path.join(out_dir, "notable", safe + ".csv"))
        try:
            unique.to_excel(os.path.join(out_dir, "combined", "all_devices.xlsx"),
                            index=False, sheet_name="Wardrive Devices")
        except Exception:
            pass  # openpyxl missing - the CSVs and report still get written

        progress("Building the field report...")
        build_report(unique, out_dir, stats)

    write_summary(out_dir, folder, stats)
    return out_dir, stats


def write_summary(out_dir, source, stats):
    lines = [
        "Nill OS Wardriver - field report", datetime.now().strftime("%Y-%m-%d %H:%M"), "",
        f"Source card folder: {source}",
        f"Session files copied: {stats['files_copied']}",
        f"Files skipped: {len(stats['files_skipped'])}", "",
        f"Raw observations: {stats['raw_rows']}",
        f"Unique devices: {stats['unique_devices']}  "
        f"(WiFi {stats['wifi']}, BLE {stats['ble']}, cell {stats['cell']})",
        f"Notable devices (Flock/Flipper/skimmer/Pineapple/...): {stats['notable']}", "",
        "Open report.html for the interactive map + browsable/filterable list.",
    ]
    if stats["files_skipped"]:
        lines += ["", "Skipped:"] + [f"  - {s}" for s in stats["files_skipped"]]
    with open(os.path.join(out_dir, "summary.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


# ---------- flashing the ESP boards (PlatformIO) ----------
BOARDS = [
    ("wifi_node", "WiFi sniffer + GPS", "usb"),
    ("ble_node", "BLE scanner", "usb"),
    ("cyd_node", "Screen / storage / uploads (CYD) - USB", "usb"),
]

# The WiFi sniffer and BLE scanner each run the same firmware on more than one
# board. The label shows in the Flash tab's board picker; the value is the env.
WIFI_BOARDS = [
    ("ESP32-S3 DevKitC", "wifi_node"),
    ("Seeed XIAO ESP32-C3", "wifi_node_xiao_c3"),
    ("Seeed XIAO ESP32-S3", "wifi_node_xiao_s3"),
]
BLE_BOARDS = [
    ("ESP32-S3 DevKitC", "ble_node"),
    ("Seeed XIAO ESP32-C3", "ble_node_xiao_c3"),
    ("Seeed XIAO ESP32-S3", "ble_node_xiao_s3"),
]


def firmware_dir():
    # tools/ lives inside firmware/, which is the PlatformIO project root.
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def pio_exe():
    return shutil.which("pio") or shutil.which("platformio")


def flash_board(env, port, on_line, build_flags=None):
    """Flash one board with PlatformIO over USB, streaming output line by line.
    Returns the exit code (0 = success). `build_flags` (a string of extra -D
    flags) is injected via PLATFORMIO_BUILD_FLAGS - used to set the per-board
    channel/MAC split for a multi-node rig (e.g. "-DNODE_COUNT=3 -DNODE_INDEX=1")."""
    pio = pio_exe()
    if not pio:
        raise ValueError("PlatformIO not found on PATH.\nInstall it:  pip install platformio")
    cmd = [pio, "run", "-e", env, "-t", "upload"]
    if port and port.strip().lower() != "auto":
        cmd += ["--upload-port", port.strip()]
    run_env = os.environ.copy()
    if build_flags:
        # PlatformIO appends these to the env's own build_flags, so the -D here
        # overrides the firmware's default NODE_COUNT/NODE_INDEX.
        run_env["PLATFORMIO_BUILD_FLAGS"] = build_flags
        on_line("$ PLATFORMIO_BUILD_FLAGS='" + build_flags + "'")
    on_line("$ cd firmware && " + " ".join(cmd))
    try:
        p = subprocess.Popen(cmd, cwd=firmware_dir(), stdout=subprocess.PIPE,
                             stderr=subprocess.STDOUT, text=True, bufsize=1, env=run_env)
    except Exception as e:
        raise ValueError("Couldn't start PlatformIO: " + str(e))
    for line in p.stdout:
        on_line(line.rstrip("\n"))
    p.wait()
    return p.returncode


# ---------- Tactical Tk UI (matches the app's cyberdeck theme) ----------
BG = "#000000"; PANEL = "#080C10"; CYAN = "#00F0FF"; DIM = "#4A607A"
PURPLE = "#9D00FF"; GREEN = "#22C55E"; RED = "#FF3333"


def _tactical_button(parent, text, command, font, accent=CYAN):
    border = tk.Frame(parent, bg=accent, padx=2, pady=2)
    btn = tk.Button(border, text=text, command=command, font=font, bg=BG, fg=accent,
                    activebackground=accent, activeforeground=BG, disabledforeground=DIM,
                    relief="flat", bd=0, padx=22, pady=10, cursor="hand2")
    btn.pack(fill="both", expand=True)
    return border, btn


def ttk_combo(parent, var, mono, width=18):
    return ttk.Combobox(parent, textvariable=var, font=mono, width=width, state="readonly")


def _themed_entry(parent, var, mono, width, show=None):
    return tk.Entry(parent, textvariable=var, font=mono, bg=PANEL, fg="#E8E8F0", insertbackground=CYAN,
                    relief="flat", highlightbackground="#33334d", highlightthickness=1, width=width, show=show)


def _log_area(parent, mono, height):
    t = tk.Text(parent, height=height, font=mono, bg=PANEL, fg=CYAN, insertbackground=CYAN,
                relief="flat", wrap="word", highlightbackground=DIM, highlightthickness=1, padx=12, pady=10)
    t.tag_config("dim", foreground=DIM); t.tag_config("ok", foreground=GREEN)
    t.tag_config("err", foreground=RED); t.tag_config("accent", foreground=CYAN)
    return t


HELP_TEXT = """> NILL OS - WARDRIVER  (desktop tool)

Three things this app does, one per tab:

1 - LOGS & REPORT
   Plug in the rig's SD card, OR plug the rig into USB, then click
   GET MY LOGS. It pulls the logs and builds an interactive map/report
   (opens in your browser). No SD-card removal needed for USB.
   'From WiFi' works too when the rig is in service mode.

2 - FLASH BOARDS
   Plug a board into USB and click DETECT to see what it is and its
   node number. FLASH ALL reflashes every connected board. Pick a
   board type (incl. Seeed XIAO) and node number for multi-node rigs.

3 - CONSOLE
   A live serial console to the rig. CONNECT, then use the quick
   buttons or type a command (try 'help').

Needs PlatformIO on your PATH for flashing (pip install platformio).
Reports are saved under ~/Wardrive_Reports.
Full guides: github.com/Nill-os/wardrive/tree/main/docs
"""


def _show_help(root, mono, mono_b, title_f):
    win = tk.Toplevel(root); win.title("Help"); win.configure(bg=BG)
    win.geometry("640x560"); win.transient(root)
    tk.Label(win, text="Help", font=title_f, bg=BG, fg=CYAN, anchor="w").pack(fill="x", padx=18, pady=(14, 6))
    t = tk.Text(win, font=mono, bg=PANEL, fg="#E8E8F0", relief="flat", wrap="word",
                highlightbackground=DIM, highlightthickness=1, padx=14, pady=12)
    t.pack(fill="both", expand=True, padx=14, pady=(0, 10))
    t.insert("1.0", HELP_TEXT); t.configure(state="disabled")
    border, _ = _tactical_button(win, "> GOT IT", win.destroy, mono_b)
    border.pack(pady=(0, 14))


def main():
    root = tk.Tk()
    root.title("Nill OS - Wardriver")
    root.geometry("760x760")
    root.configure(bg=BG)
    root.minsize(700, 640)   # resizable, but never so small the buttons get clipped

    mono = tkfont.nametofont("TkFixedFont").copy(); mono.configure(size=11)
    mono_b = mono.copy(); mono_b.configure(weight="bold")
    title_f = mono.copy(); title_f.configure(size=18, weight="bold")

    style = ttk.Style()
    try: style.theme_use("clam")
    except Exception: pass
    style.configure("TNotebook", background=BG, borderwidth=0)
    style.configure("TNotebook.Tab", background=PANEL, foreground=DIM, padding=(16, 7), font=mono_b, borderwidth=0)
    style.map("TNotebook.Tab", background=[("selected", BG)], foreground=[("selected", CYAN)])

    header = tk.Frame(root, bg=BG); header.pack(fill="x", padx=20, pady=(16, 6))
    tk.Label(header, text="> NILL OS - WARDRIVER", font=title_f, bg=BG, fg=CYAN, anchor="w").pack(side="left")
    tk.Button(header, text="?  Help", command=lambda: _show_help(root, mono, mono_b, title_f),
              font=mono, bg=PANEL, fg=DIM, relief="flat", padx=10, pady=2, cursor="hand2").pack(side="right")

    # Shared across tabs: the rig's WiFi address / service password and USB port.
    host_var = tk.StringVar(value="nillos-wardriver.local")
    pass_var = tk.StringVar()
    port_var = tk.StringVar(value="auto")

    nb = ttk.Notebook(root)
    report_tab = tk.Frame(nb, bg=BG)
    flash_tab = tk.Frame(nb, bg=BG)
    manage_tab = tk.Frame(nb, bg=BG)
    nb.add(report_tab, text="  1 · Logs & Report  ")
    nb.add(flash_tab, text="  2 · Flash boards  ")
    nb.add(manage_tab, text="  3 · Console  ")
    nb.pack(fill="both", expand=True, padx=14, pady=(0, 14))

    _build_report_tab(report_tab, mono, mono_b, host_var, pass_var, port_var)
    _build_flash_tab(flash_tab, mono, mono_b, root)
    _build_manage_tab(manage_tab, mono, mono_b, root, port_var)

    root.mainloop()


def _build_report_tab(tab, mono, mono_b, host_var, pass_var, port_var):
    tk.Label(tab, text="Pull your rig's logs and build an interactive field report - a map you\n"
                       "can browse, filter and search, with Flock / Flipper / skimmer detection.",
             font=mono, bg=BG, fg=DIM, justify="left", anchor="w").pack(fill="x", padx=6, pady=(10, 6))

    state = {"report": None, "out": None}

    # --- the one big button most people need ---
    auto_border, auto_btn = _tactical_button(
        tab, "> GET MY LOGS", lambda: threading.Thread(target=do_auto, daemon=True).start(), mono_b, accent=GREEN)
    auto_border.pack(pady=(4, 2))
    tk.Label(tab, text="Finds your SD card or the rig on USB automatically. Just plug one in.",
             font=mono, bg=BG, fg=DIM).pack(pady=(0, 6))

    # --- results (shown once a report is built) ---
    results = tk.Frame(tab, bg=BG)
    rep_border, _rb = _tactical_button(results, "> VIEW FIELD REPORT",
                                       lambda: state["report"] and open_path(state["report"]), mono_b)
    rep_border.pack(side="left", padx=(0, 8))
    fol_border, _fb = _tactical_button(results, "> OPEN FOLDER",
                                       lambda: state["out"] and open_path(state["out"]), mono_b, accent=PURPLE)
    fol_border.pack(side="left")

    status = _log_area(tab, mono, 7)
    status.pack(fill="both", expand=True, padx=6, pady=(4, 6))

    def log(text="", tag=None):
        status.configure(state="normal"); status.insert("end", text + "\n", (tag,) if tag else ())
        status.see("end"); status.configure(state="disabled"); tab.update_idletasks()

    def clear():
        status.configure(state="normal"); status.delete("1.0", "end"); status.configure(state="disabled")

    def _no_logs():
        # Not an error - the rig just hasn't logged any GPS-fixed drives yet.
        log("")
        log("Connected fine - but there are no logged drives to pull yet.", "accent")
        log("The rig only saves sightings once it has a GPS fix, so a bench test", "dim")
        log("or a drive with no clear sky view logs nothing. Take it for a drive", "dim")
        log("with a good view of the sky, then pull the logs again.", "dim")

    def _process(folder):
        import glob as _g
        if not _g.glob(os.path.join(folder, "*.csv")):
            _no_logs(); return
        log("Building your report from " + folder, "accent")
        try:
            out_dir, st = organize(folder, progress=lambda s: log("  " + s, "dim"))
        except Exception as e:
            log("Something went wrong: " + str(e), "err"); return
        state["out"], state["report"] = out_dir, os.path.join(out_dir, "report.html")
        log("")
        log(f"  {st['unique_devices']} devices  (WiFi {st['wifi']} · BLE {st['ble']} · cell {st['cell']})")
        log(f"  {st['notable']} notable  (Flock / Flipper / skimmer / Pineapple / ...)",
            "accent" if st["notable"] else "dim")
        log("")
        if st["unique_devices"] == 0:
            log("No located devices in these logs yet (bench test, or no GPS fix during the drive).", "dim")
        else:
            log("Saved to: " + out_dir, "dim")
            log("> DONE - opening the report in your browser", "ok")
            results.pack(before=status, pady=(2, 6))  # reveal VIEW / OPEN buttons, above the log
            open_path(state["report"])                # auto-open so the result is right there

    def _busy(on):
        for b in (auto_btn, sd_btn, usb_btn, wifi_btn):
            b.configure(state="disabled" if on else "normal")

    def _start(msg=None):
        _busy(True); results.pack_forget(); clear()
        if msg: log(msg, "accent")

    def do_auto():
        _start("Looking for your logs...")
        kind, val = smart_get_source()
        if kind == "sd":
            log("Found the rig's SD card.", "ok"); _process(val)
        elif kind == "usb":
            log(f"Found the rig on USB ({val}) - reading over the cable...", "ok")
            tmp = os.path.join(tempfile.gettempdir(), "nillos_rig_auto"); shutil.rmtree(tmp, ignore_errors=True)
            try:
                n = read_from_cyd_serial(val, tmp, progress=lambda s: log("  " + s, "dim"))
                if n: log(f"  read {n} file(s)", "dim")
                _process(tmp)
            except Exception as e:
                log("Couldn't read over USB: " + str(e), "err")
        else:
            log("")
            log("Couldn't find the SD card or the rig on USB.", "err")
            log("Do one of these, then click GET MY LOGS again:", "dim")
            log("  - plug the rig's SD card into this computer, or", "dim")
            log("  - plug the rig into USB with a cable, or", "dim")
            log("  - use 'From WiFi' below (put the rig in service mode first).", "dim")
        _busy(False)

    def do_sd():
        _start("Reading the SD card...")
        folder = find_session_folder()
        if not folder:
            log("No SD card found - pick the 'wardrive' folder yourself...", "dim")
            folder = filedialog.askdirectory(title="Select the 'wardrive' folder on the rig's SD card")
            if not folder:
                log("Cancelled.", "dim"); _busy(False); return
        _process(folder); _busy(False)

    def do_wifi():
        _start("Downloading over WiFi...")
        if not host_var.get().strip():
            log("Type the rig's address in the WiFi field below first.", "err"); _busy(False); return
        tmp = os.path.join(tempfile.gettempdir(), "nillos_rig_dl"); shutil.rmtree(tmp, ignore_errors=True)
        try:
            n = download_from_rig(host_var.get(), pass_var.get(), tmp, progress=lambda s: log("  " + s, "dim"))
            if n: log(f"  downloaded {n} file(s)", "dim")
        except Exception as e:
            log("Couldn't reach the rig over WiFi: " + str(e), "err"); _busy(False); return
        _process(tmp); _busy(False)

    def do_usb():
        _start("Reading over the USB cable...")
        tmp = os.path.join(tempfile.gettempdir(), "nillos_rig_usb"); shutil.rmtree(tmp, ignore_errors=True)
        try:
            n = read_from_cyd_serial(port_var.get(), tmp, progress=lambda s: log("  " + s, "dim"))
            if n: log(f"  read {n} file(s)", "dim")
        except Exception as e:
            log("Couldn't read over USB: " + str(e), "err"); _busy(False); return
        _process(tmp); _busy(False)

    # --- manual sources (for when auto can't be used, e.g. WiFi) ---
    tk.Label(tab, text="— or pick a source —", font=mono, bg=BG, fg=DIM).pack(pady=(2, 4))
    srow = tk.Frame(tab, bg=BG); srow.pack(padx=6)
    def _small(parent, text, cmd, accent=CYAN):
        b = tk.Button(parent, text=text, command=cmd, font=mono, bg=PANEL, fg=accent,
                      activebackground=accent, activeforeground=BG, relief="flat", bd=0, padx=14, pady=7, cursor="hand2")
        b.pack(side="left", padx=4); return b
    sd_btn = _small(srow, "From SD card", lambda: threading.Thread(target=do_sd, daemon=True).start())
    usb_btn = _small(srow, "From USB", lambda: threading.Thread(target=do_usb, daemon=True).start())
    wifi_btn = _small(srow, "From WiFi", lambda: threading.Thread(target=do_wifi, daemon=True).start(), accent=PURPLE)

    frow = tk.Frame(tab, bg=BG); frow.pack(fill="x", padx=6, pady=(8, 8))
    tk.Label(frow, text="WiFi address:", font=mono, bg=BG, fg=DIM).pack(side="left")
    _themed_entry(frow, host_var, mono, 20).pack(side="left", padx=(6, 8))
    tk.Label(frow, text="password:", font=mono, bg=BG, fg=DIM).pack(side="left")
    _themed_entry(frow, pass_var, mono, 10, show="*").pack(side="left", padx=(6, 8))
    tk.Label(frow, text="(only for 'From WiFi')", font=mono, bg=BG, fg=DIM).pack(side="left")

    log("Ready. Plug in the SD card or the rig, then click GET MY LOGS.", "dim")


def _build_flash_tab(tab, mono, mono_b, root):
    tk.Label(tab, text="Flash firmware to the rig over USB.   1) plug a board in    2) DETECT\n"
                       "to see what it is and its node number    3) FLASH ALL (reflashes every\n"
                       "board found), or flash one board with the buttons below.",
             font=mono, bg=BG, fg=DIM, justify="left", anchor="w").pack(fill="x", padx=6, pady=(10, 8))

    prow = tk.Frame(tab, bg=BG); prow.pack(fill="x", padx=6, pady=(0, 6))
    tk.Label(prow, text="USB port:", font=mono, bg=BG, fg=DIM).pack(side="left")
    fport = tk.StringVar(value="auto")
    port_menu = ttk_combo(prow, fport, mono)
    port_menu.pack(side="left", padx=(6, 6))

    # Multi-node rig: how many WiFi sniffer nodes there are, and which one this
    # board will be. The firmware splits the 2.4 GHz channels across them.
    node_count = tk.IntVar(value=1)
    node_index = tk.IntVar(value=0)
    nrow = tk.Frame(tab, bg=BG); nrow.pack(fill="x", padx=6, pady=(0, 2))
    tk.Label(nrow, text="Sniffer nodes:", font=mono, bg=BG, fg=DIM).pack(side="left")
    idx_menu = ttk_combo(nrow, node_index, mono, width=4)

    def _on_count_change(*_):
        n = max(1, node_count.get())
        vals = [str(i) for i in range(n)]
        idx_menu["values"] = vals
        if str(node_index.get()) not in vals:
            node_index.set(0)
        # the index picker only matters when there's more than one node
        if n > 1:
            idx_menu.configure(state="readonly")
        else:
            node_index.set(0); idx_menu.configure(state="disabled")

    count_menu = ttk_combo(nrow, node_count, mono, width=4)
    count_menu["values"] = [str(i) for i in range(1, 21)]
    count_menu.pack(side="left", padx=(6, 10))
    node_count.trace_add("write", _on_count_change)
    tk.Label(nrow, text="this wifi_node is #", font=mono, bg=BG, fg=DIM).pack(side="left")
    idx_menu.pack(side="left", padx=(6, 0))
    _on_count_change()
    tk.Label(tab, text="1 node = channels 1/6/11. More nodes split channels 1-13 across them (flash each\n"
                       "wifi_node with its own #). Wiring more nodes in needs a transport change - see Design notes.",
             font=mono, bg=BG, fg=DIM, justify="left", anchor="w").pack(fill="x", padx=6, pady=(0, 6))

    # Multiple BLE scanners: BLE has no channels to divide (every node hears all
    # three advertising channels), so they split the MAC space instead - each
    # board forwards only its slice, so no one node's link queue is swamped.
    ble_count = tk.IntVar(value=1)
    ble_index = tk.IntVar(value=0)
    brow = tk.Frame(tab, bg=BG); brow.pack(fill="x", padx=6, pady=(0, 2))
    tk.Label(brow, text="BLE nodes:", font=mono, bg=BG, fg=DIM).pack(side="left")
    ble_idx_menu = ttk_combo(brow, ble_index, mono, width=4)

    def _on_ble_count_change(*_):
        n = max(1, ble_count.get())
        vals = [str(i) for i in range(n)]
        ble_idx_menu["values"] = vals
        if str(ble_index.get()) not in vals:
            ble_index.set(0)
        if n > 1:
            ble_idx_menu.configure(state="readonly")
        else:
            ble_index.set(0); ble_idx_menu.configure(state="disabled")

    ble_count_menu = ttk_combo(brow, ble_count, mono, width=4)
    ble_count_menu["values"] = [str(i) for i in range(1, 21)]
    ble_count_menu.pack(side="left", padx=(6, 10))
    ble_count.trace_add("write", _on_ble_count_change)
    tk.Label(brow, text="this ble_node is #", font=mono, bg=BG, fg=DIM).pack(side="left")
    ble_idx_menu.pack(side="left", padx=(6, 0))
    _on_ble_count_change()
    tk.Label(tab, text="1 BLE node hears everything. More nodes each report a share of devices (flash each\n"
                       "ble_node with its own #) so a busy area doesn't overflow one node's link.",
             font=mono, bg=BG, fg=DIM, justify="left", anchor="w").pack(fill="x", padx=6, pady=(0, 6))

    out = _log_area(tab, mono, 12)
    out.pack(fill="both", expand=True, padx=6, pady=(6, 8))

    def append(text, tag=None):
        root.after(0, lambda: (out.configure(state="normal"), out.insert("end", text + "\n", (tag,) if tag else ()),
                               out.see("end"), out.configure(state="disabled")))

    def refresh_ports():
        ports = ["auto"] + _serial_ports()
        port_menu["values"] = ports
        if fport.get() not in ports: fport.set("auto")
    refresh_ports()

    buttons = {}

    def do_flash(env):
        if not pio_exe():
            append("PlatformIO not found. Install it:  pip install platformio", "err"); return
        for b in buttons.values(): b.configure(state="disabled")
        out.configure(state="normal"); out.delete("1.0", "end"); out.configure(state="disabled")
        append(f"Flashing {env}...", "accent")

        # For a multi-node rig, tell wifi_node how many sniffers there are and
        # which one this board is, so the firmware picks this node's channels.
        build_flags = None
        if env.startswith("wifi_node") and node_count.get() > 1:
            n, i = node_count.get(), node_index.get()
            build_flags = f"-DNODE_COUNT={n} -DNODE_INDEX={i}"
            append(f"(node {i} of {n} - this board takes its slice of channels 1-13)", "dim")
        elif env.startswith("ble_node") and ble_count.get() > 1:
            n, i = ble_count.get(), ble_index.get()
            build_flags = f"-DBLE_NODE_COUNT={n} -DBLE_NODE_INDEX={i}"
            append(f"(BLE node {i} of {n} - this board reports its share of devices)", "dim")

        def worker():
            try:
                rc = flash_board(env, fport.get(), lambda l: append("  " + l), build_flags=build_flags)
                append("> SUCCESS" if rc == 0 else f"> FAILED (exit {rc})", "ok" if rc == 0 else "err")
            except Exception as e:
                append("ERROR: " + str(e), "err")
            finally:
                root.after(0, lambda: [b.configure(state="normal") for b in buttons.values()])
        threading.Thread(target=worker, daemon=True).start()

    tk.Button(prow, text="refresh", command=refresh_ports, font=mono, bg=PANEL, fg=CYAN,
              relief="flat", padx=8, cursor="hand2").pack(side="left")

    def detect_boards():
        refresh_ports()
        ports = _serial_ports()
        if not ports:
            append("No serial ports found. Plug a board in and hit detect.", "err"); return
        for b in buttons.values(): b.configure(state="disabled")
        out.configure(state="normal"); out.delete("1.0", "end"); out.configure(state="disabled")
        append("Detecting boards on " + str(len(ports)) + " port(s)...", "accent")

        def worker():
            try:
                serial = _import_serial()
            except Exception as e:
                append("ERROR: " + str(e), "err")
                root.after(0, lambda: [b.configure(state="normal") for b in buttons.values()]); return
            descriptions = _port_descriptions()
            found = []
            for port in ports:
                info = detect_board(serial, port)
                role = info.get("role")
                append(f"  {port}  ->  {describe_board(info, descriptions)}",
                       "ok" if role in _ROLE_LABEL else "dim")
                if role in _ROLE_LABEL:
                    found.append((port, info))
            if not found:
                append("No wardrive boards recognized. A blank board still flashes fine - "
                       "pick its port and choose a type/number.", "dim")
            else:
                port, info = found[0]

                def apply():
                    if port in port_menu["values"]:
                        fport.set(port)
                    if info["role"] == "wifi" and info.get("count"):
                        node_count.set(info["count"])
                        if info.get("index") is not None: node_index.set(info["index"])
                    elif info["role"] == "ble" and info.get("count"):
                        ble_count.set(info["count"])
                        if info.get("index") is not None: ble_index.set(info["index"])
                root.after(0, apply)
                append(f"Aimed the flasher at {port} ({_ROLE_LABEL[info['role']]}). "
                       "Change the type/number above to reassign it, then flash.", "accent")
            root.after(0, lambda: [b.configure(state="normal") for b in buttons.values()])
        threading.Thread(target=worker, daemon=True).start()

    detect_border, detect_btn = _tactical_button(prow, "> DETECT", detect_boards, mono_b, accent=GREEN)
    detect_border.pack(side="left", padx=(10, 0))
    buttons["_detect"] = detect_btn

    # Which board the WiFi sniffer / BLE scanner are flashed to (default DevKitC).
    wifi_labels = [lbl for lbl, _env in WIFI_BOARDS]
    wifi_env_of = dict(WIFI_BOARDS)
    wifi_choice = tk.StringVar(value=wifi_labels[0])
    ble_labels = [lbl for lbl, _env in BLE_BOARDS]
    ble_env_of = dict(BLE_BOARDS)
    ble_choice = tk.StringVar(value=ble_labels[0])

    def _board_picker_row(f, desc, label_text, button_env_getter, choice_var, labels, key):
        border, btn = _tactical_button(f, "> FLASH " + label_text,
                                       (lambda: do_flash(button_env_getter())), mono_b, accent=CYAN)
        border.pack(side="left")
        buttons[key] = btn
        tk.Label(f, text=desc + " on:", font=mono, bg=BG, fg=DIM).pack(side="left", padx=(10, 4))
        menu = ttk_combo(f, choice_var, mono, width=20)
        menu["values"] = labels
        menu.pack(side="left")

    for env, desc, kind in BOARDS:
        f = tk.Frame(tab, bg=BG); f.pack(fill="x", padx=6, pady=2)
        if env == "wifi_node":
            _board_picker_row(f, desc, "WIFI_NODE", lambda: wifi_env_of[wifi_choice.get()],
                              wifi_choice, wifi_labels, "wifi_node")
        elif env == "ble_node":
            _board_picker_row(f, desc, "BLE_NODE", lambda: ble_env_of[ble_choice.get()],
                              ble_choice, ble_labels, "ble_node")
        else:
            border, btn = _tactical_button(f, "> FLASH " + env.upper(), (lambda e=env: do_flash(e)), mono_b, accent=CYAN)
            border.pack(side="left")
            buttons[env] = btn
            tk.Label(f, text=desc, font=mono, bg=BG, fg=DIM).pack(side="left", padx=(10, 0))

    # ---- Bulk flash: detect every connected board and reflash each with the
    # matching firmware in one go, keeping the node number it already reports. ----
    def flash_all():
        if not pio_exe():
            append("PlatformIO not found. Install it:  pip install platformio", "err"); return
        ports = _serial_ports()
        if not ports:
            append("No serial ports found. Plug the boards in first.", "err"); return
        for b in buttons.values(): b.configure(state="disabled")
        out.configure(state="normal"); out.delete("1.0", "end"); out.configure(state="disabled")
        append("Bulk flash: detecting boards...", "accent")

        def flags_for(role, info):
            # Prefer the number the board already reports; fall back to the UI.
            if role == "wifi":
                n = info.get("count") or node_count.get()
                i = info.get("index") if info.get("index") is not None else node_index.get()
                return f"-DNODE_COUNT={n} -DNODE_INDEX={i}" if n and n > 1 else None
            if role == "ble":
                n = info.get("count") or ble_count.get()
                i = info.get("index") if info.get("index") is not None else ble_index.get()
                return f"-DBLE_NODE_COUNT={n} -DBLE_NODE_INDEX={i}" if n and n > 1 else None
            return None

        def worker():
            try:
                serial = _import_serial()
            except Exception as e:
                append("ERROR: " + str(e), "err")
                root.after(0, lambda: [b.configure(state="normal") for b in buttons.values()]); return
            descriptions = _port_descriptions()
            # role -> PlatformIO env to flash it with (WiFi/BLE use the picked board)
            env_for = {"wifi": wifi_env_of[wifi_choice.get()], "ble": ble_env_of[ble_choice.get()], "cyd": "cyd_node"}
            plan = []
            for port in ports:
                info = detect_board(serial, port)
                role = info.get("role")
                append(f"  {port}  ->  {describe_board(info, descriptions)}",
                       "ok" if role in _ROLE_LABEL else "dim")
                if role in env_for:
                    plan.append((port, role, info))
            if not plan:
                append("Nothing to flash - no wardrive boards recognized. Flash blank boards "
                       "individually so you can assign each a type and number.", "err")
                root.after(0, lambda: [b.configure(state="normal") for b in buttons.values()]); return
            append(f"Flashing {len(plan)} board(s) in sequence...", "accent")
            ok = 0
            for port, role, info in plan:
                env = env_for[role]
                append(f"--- {env} on {port} ---", "accent")
                try:
                    rc = flash_board(env, port, lambda l: append("  " + l), build_flags=flags_for(role, info))
                    if rc == 0:
                        ok += 1; append(f"> {env} OK", "ok")
                    else:
                        append(f"> {env} FAILED (exit {rc})", "err")
                except Exception as e:
                    append(f"> {env} ERROR: {e}", "err")
            append(f"Bulk flash done: {ok}/{len(plan)} succeeded.", "ok" if ok == len(plan) else "err")
            root.after(0, lambda: [b.configure(state="normal") for b in buttons.values()])
        threading.Thread(target=worker, daemon=True).start()

    af = tk.Frame(tab, bg=BG); af.pack(fill="x", padx=6, pady=(8, 2))
    all_border, all_btn = _tactical_button(af, "> FLASH ALL", flash_all, mono_b, accent=GREEN)
    all_border.pack(side="left")
    buttons["_flashall"] = all_btn
    tk.Label(af, text="detect every connected board and reflash each, keeping its number",
             font=mono, bg=BG, fg=DIM).pack(side="left", padx=(10, 0))


def _build_manage_tab(tab, mono, mono_b, root, port_var):
    tk.Label(tab, text="A live console to the rig. Pick the port (or leave 'auto') and press\n"
                       "CONNECT, then use the quick buttons below or type a command and Enter.\n"
                       "Try 'help' to list commands. Connecting won't disturb a run.",
             font=mono, bg=BG, fg=DIM, justify="left", anchor="w").pack(fill="x", padx=6, pady=(10, 6))

    prow = tk.Frame(tab, bg=BG); prow.pack(fill="x", padx=6, pady=(0, 6))
    tk.Label(prow, text="Port:", font=mono, bg=BG, fg=DIM).pack(side="left")
    mport = tk.StringVar(value="auto")
    pm = ttk_combo(prow, mport, mono); pm.pack(side="left", padx=(6, 6))
    tk.Button(prow, text="refresh", command=lambda: pm.configure(values=["auto"] + _serial_ports()),
              font=mono, bg=PANEL, fg=CYAN, relief="flat", padx=8, cursor="hand2").pack(side="left")
    pm.configure(values=["auto"] + _serial_ports())

    con = {"ser": None, "stop": False}
    out = _log_area(tab, mono, 11); out.pack(fill="both", expand=True, padx=6, pady=(6, 6))

    def append(text, tag=None):
        root.after(0, lambda: (out.configure(state="normal"), out.insert("end", text + "\n", (tag,) if tag else ()),
                               out.see("end"), out.configure(state="disabled")))

    def reader():
        s = con["ser"]
        while not con["stop"] and s and s.is_open:
            try:
                line = s.readline().decode("utf-8", "replace").rstrip("\r\n")
            except Exception:
                break
            if line:
                append(line, "err" if "[ALERT]" in line or "FAIL" in line else None)

    def connect():
        if con["ser"]:
            con["stop"] = True
            try: con["ser"].close()
            except Exception: pass
            con["ser"] = None; connect_btn.configure(text="> CONNECT"); append("disconnected.", "dim"); return
        try:
            serial = _import_serial()
            port = mport.get()
            if port == "auto":
                port = next((p for p in _serial_ports() if _probe_is_cyd(serial, p)), None)
                if not port:
                    append("Couldn't find the rig on a serial port.", "err"); return
            con["ser"] = _open_cyd(serial, port); con["stop"] = False
            connect_btn.configure(text="> DISCONNECT")
            append(f"connected to {port}", "accent")
            threading.Thread(target=reader, daemon=True).start()
        except Exception as e:
            append("ERROR: " + str(e), "err")

    def send(cmd):
        if not con["ser"]:
            append("Not connected.", "err"); return
        try:
            con["ser"].write((cmd + "\n").encode()); con["ser"].flush()
            append("> " + cmd, "accent")
        except Exception as e:
            append("write failed: " + str(e), "err")

    connect_border, connect_btn = _tactical_button(prow, "> CONNECT", connect, mono_b)
    connect_border.pack(side="left", padx=(10, 0))

    # command entry
    crow = tk.Frame(tab, bg=BG); crow.pack(fill="x", padx=6, pady=(0, 6))
    cmd_var = tk.StringVar()
    ce = _themed_entry(crow, cmd_var, mono, 40); ce.pack(side="left", fill="x", expand=True)
    def send_typed(*_):
        c = cmd_var.get().strip()
        if c: send(c); cmd_var.set("")
    ce.bind("<Return>", send_typed)
    tk.Button(crow, text="send", command=send_typed, font=mono_b, bg=PANEL, fg=CYAN,
              relief="flat", padx=10, cursor="hand2").pack(side="left", padx=(6, 0))

    # quick actions
    qrow = tk.Frame(tab, bg=BG); qrow.pack(fill="x", padx=6, pady=(0, 8))
    for label, cmd in [("Service ON", "rig service on"), ("Service OFF", "rig service off"),
                       ("List logs", "sd list"), ("Status", "wdstream status")]:
        tk.Button(qrow, text=label, command=(lambda c=cmd: send(c)), font=mono, bg=PANEL, fg=CYAN,
                  relief="flat", padx=8, pady=4, cursor="hand2").pack(side="left", padx=(0, 6))
    append("Pick a port and press CONNECT. Opening the port won't disturb a run.", "dim")


if __name__ == "__main__":
    main()

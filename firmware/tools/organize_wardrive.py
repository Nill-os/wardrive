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
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import tkinter as tk
import tkinter.font as tkfont
from datetime import datetime
from tkinter import filedialog

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
        raise ValueError("Connected, but the rig has no session CSVs to download yet.")
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
            raise ValueError("Connected, but the card has no session CSVs.")
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


def main():
    root = tk.Tk()
    root.title("Nill OS - Wardriver")
    root.geometry("680x700")
    root.configure(bg=BG)
    root.resizable(False, False)

    mono = tkfont.nametofont("TkFixedFont").copy(); mono.configure(size=11)
    mono_b = mono.copy(); mono_b.configure(weight="bold")
    title_f = mono.copy(); title_f.configure(size=18, weight="bold")

    tk.Label(root, text="> NILL OS - WARDRIVER", font=title_f, bg=BG, fg=CYAN, anchor="w").pack(fill="x", padx=20, pady=(18, 2))
    tk.Label(root, text="UPLOAD & FIELD REPORT", font=mono, bg=BG, fg=DIM, anchor="w").pack(fill="x", padx=20, pady=(0, 10))
    tk.Label(root, text="Get the logs off the rig three ways: the SD card, over the CYD's\n"
                        "USB cable (no card removal), or over WiFi with the rig in service\n"
                        "mode. Either way it builds an interactive field report (map +\n"
                        "browse / filter / search, with Flock/Flipper/skimmer detection)\n"
                        "under ~/Wardrive_Reports. Nothing is deleted from the card.",
             font=mono, bg=BG, fg=DIM, justify="left", anchor="w").pack(fill="x", padx=20, pady=(0, 10))

    # rig address + service password (for GET FROM RIG)
    row = tk.Frame(root, bg=BG); row.pack(fill="x", padx=20, pady=(0, 6))
    tk.Label(row, text="Rig:", font=mono, bg=BG, fg=DIM).pack(side="left")
    host_var = tk.StringVar(value="nillos-wardriver.local")
    tk.Entry(row, textvariable=host_var, font=mono, bg=PANEL, fg="#E8E8F0", insertbackground=CYAN,
             relief="flat", highlightbackground="#33334d", highlightthickness=1, width=22).pack(side="left", padx=(6, 10))
    tk.Label(row, text="Pass:", font=mono, bg=BG, fg=DIM).pack(side="left")
    pass_var = tk.StringVar()
    tk.Entry(row, textvariable=pass_var, font=mono, bg=PANEL, fg="#E8E8F0", insertbackground=CYAN, show="*",
             relief="flat", highlightbackground="#33334d", highlightthickness=1, width=14).pack(side="left", padx=(6, 0))
    row2 = tk.Frame(root, bg=BG); row2.pack(fill="x", padx=20, pady=(0, 4))
    tk.Label(row2, text="USB port:", font=mono, bg=BG, fg=DIM).pack(side="left")
    port_var = tk.StringVar(value="auto")
    tk.Entry(row2, textvariable=port_var, font=mono, bg=PANEL, fg="#E8E8F0", insertbackground=CYAN,
             relief="flat", highlightbackground="#33334d", highlightthickness=1, width=22).pack(side="left", padx=(6, 0))

    status = tk.Text(root, height=7, font=mono, bg=PANEL, fg=CYAN, insertbackground=CYAN,
                     relief="flat", wrap="word", highlightbackground=DIM, highlightthickness=1, padx=12, pady=10)
    status.pack(fill="both", expand=True, padx=20, pady=(6, 10))
    status.tag_config("dim", foreground=DIM); status.tag_config("ok", foreground=GREEN)
    status.tag_config("err", foreground=RED); status.tag_config("accent", foreground=CYAN)

    def log(text="", tag=None):
        status.configure(state="normal")
        status.insert("end", text + "\n", (tag,) if tag else ())
        status.see("end"); status.configure(state="disabled"); root.update_idletasks()

    def clear():
        status.configure(state="normal"); status.delete("1.0", "end"); status.configure(state="disabled")

    state = {"report": None, "out": None}

    def _process(folder):
        """Organize a folder of session CSVs and show the result."""
        log("Building from " + folder, "accent")
        try:
            out_dir, st = organize(folder, progress=lambda s: log("  " + s, "dim"))
        except Exception as e:
            log("ERROR: " + str(e), "err"); return
        state["out"] = out_dir
        state["report"] = os.path.join(out_dir, "report.html")
        log("")
        log(f"  unique devices : {st['unique_devices']}  (WiFi {st['wifi']}, BLE {st['ble']}, cell {st['cell']})")
        log(f"  notable        : {st['notable']}  (Flock / Flipper / skimmer / Pineapple / ...)",
            "accent" if st["notable"] else "dim")
        log("")
        if st["unique_devices"] == 0:
            log("No logged devices in these files (bench-test / no GPS fix yet).", "dim")
        else:
            log("field report + organized folders in:", "dim")
            log("  " + out_dir, "accent")
            log("> DONE", "ok")
            report_border.pack(pady=(0, 8))
        folder_border.pack(pady=(0, 14))

    def _busy(on):
        for b in (sd_btn, wifi_btn, usb_btn):
            b.configure(state="disabled" if on else "normal")

    def do_sd():
        _busy(True); report_border.pack_forget(); folder_border.pack_forget(); clear()
        folder = find_session_folder()
        if not folder:
            log("No SD card auto-detected - pick the 'wardrive' folder...", "dim")
            folder = filedialog.askdirectory(title="Select the 'wardrive' folder on the rig's SD card")
            if not folder:
                log("Cancelled.", "dim"); _busy(False); return
        _process(folder); _busy(False)

    def do_wifi():
        _busy(True); report_border.pack_forget(); folder_border.pack_forget(); clear()
        host = host_var.get().strip()
        if not host:
            log("Enter the rig's address (e.g. nillos-wardriver.local or its IP).", "err"); _busy(False); return
        tmp = os.path.join(tempfile.gettempdir(), "nillos_rig_dl")
        shutil.rmtree(tmp, ignore_errors=True)
        try:
            n = download_from_rig(host, pass_var.get(), tmp, progress=lambda s: log("  " + s, "dim"))
            log(f"  downloaded {n} file(s) from the rig", "dim")
        except Exception as e:
            log("ERROR: " + str(e), "err"); _busy(False); return
        _process(tmp); _busy(False)

    def do_usb():
        _busy(True); report_border.pack_forget(); folder_border.pack_forget(); clear()
        tmp = os.path.join(tempfile.gettempdir(), "nillos_rig_usb")
        shutil.rmtree(tmp, ignore_errors=True)
        try:
            n = read_from_cyd_serial(port_var.get(), tmp, progress=lambda s: log("  " + s, "dim"))
            log(f"  read {n} file(s) from the rig over USB", "dim")
        except Exception as e:
            log("ERROR: " + str(e), "err"); _busy(False); return
        _process(tmp); _busy(False)

    sd_border, sd_btn = _tactical_button(root, "> GET FROM SD CARD", do_sd, mono_b)
    sd_border.pack(pady=(0, 6))
    usb_border, usb_btn = _tactical_button(root, "> GET FROM RIG (USB)", do_usb, mono_b)
    usb_border.pack(pady=(0, 6))
    wifi_border, wifi_btn = _tactical_button(root, "> GET FROM RIG (WIFI)", do_wifi, mono_b, accent=PURPLE)
    wifi_border.pack(pady=(0, 6))
    report_border, _rb = _tactical_button(root, "> VIEW FIELD REPORT",
                                          lambda: state["report"] and open_path(state["report"]), mono_b)
    folder_border, _fb = _tactical_button(root, "> OPEN FOLDER",
                                          lambda: state["out"] and open_path(state["out"]), mono_b, accent=PURPLE)

    log("Ready. SD card, USB cable, or WiFi (rig in service mode).", "dim")
    root.mainloop()


if __name__ == "__main__":
    main()

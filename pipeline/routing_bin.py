#!/usr/bin/env python3
"""Compact binary trail network for TrailGuide GPS (routing.bin).

The phone memory-maps this file and routes on it directly, so the layout is
fixed-width little-endian arrays that can be read without parsing:

  header (256 bytes)
    0   magic "TGRG"
    4   u32 version (1)
    8   u32 vertex count V
    12  u32 edge count E
    16  u32 coordinate count C
    20  u32 grid rows
    24  u32 grid cols
    28  u32 name count
    32  f64 grid cell size (degrees)
    40  f64 grid min latitude
    48  f64 grid min longitude
    56  u64[17] byte offset of each section, in SECTIONS order

  sections (each 8-byte aligned)
    vlat, vlon        i32[V]   vertex latitude / longitude, degrees * 1e6
    adj_start         i32[V+1] CSR offsets into adj_edges
    adj_edges         i32[2E]  edges touching each vertex
    e_from, e_to      i32[E]   endpoint vertices
    e_len             f32[E]   length in metres
    e_gain, e_loss    f32[E]   climb / descent in metres, walking from -> to
    e_name            i32[E]   index into names, -1 for unnamed
    e_kind            u8[E]    0 trail, 1 track, 2 road
    geom_start        i32[E+1] offsets into c_lat / c_lon
    c_lat, c_lon      i32[C]   edge geometry, degrees * 1e6, from -> to
    cell_start        i32[R*C+1] CSR offsets into cell_edges (row-major grid)
    cell_edges        i32[...] edges passing through each grid cell
    names             u32 count, then per name: u16 byte length + UTF-8
"""
import gzip
import math
import struct

import numpy as np

MAGIC = b"TGRG"
VERSION = 1
HEADER_SIZE = 256
CELL_DEG = 0.01
SECTIONS = [
    "vlat", "vlon", "adj_start", "adj_edges", "e_from", "e_to", "e_len", "e_gain", "e_loss",
    "e_name", "e_kind", "geom_start", "c_lat", "c_lon", "cell_start", "cell_edges", "names",
]
KINDS = ["trail", "track", "road"]


def _cells_for_edge(coords, min_lat, min_lon, rows, cols):
    """Grid cells an edge passes through, sampling each segment finely enough not to skip any."""
    cells = set()
    step = CELL_DEG / 3
    for (x1, y1), (x2, y2) in zip(coords, coords[1:]):
        n = max(1, int(max(abs(x2 - x1), abs(y2 - y1)) / step))
        for i in range(n + 1):
            t = i / n
            x, y = x1 + (x2 - x1) * t, y1 + (y2 - y1) * t
            r = int((y - min_lat) / CELL_DEG)
            c = int((x - min_lon) / CELL_DEG)
            if 0 <= r < rows and 0 <= c < cols:
                cells.add(r * cols + c)
    if len(coords) == 1:
        x, y = coords[0]
        cells.add(int((y - min_lat) / CELL_DEG) * cols + int((x - min_lon) / CELL_DEG))
    return cells


def build(vertices, edges, names, bbox):
    """vertices: [(lon, lat)]; edges: [(from, to, len_m, gain_m, loss_m, name_idx, kind_idx, [(lon, lat), ...])]."""
    nv, ne = len(vertices), len(edges)
    vlat = np.array([round(v[1] * 1e6) for v in vertices], dtype="<i4")
    vlon = np.array([round(v[0] * 1e6) for v in vertices], dtype="<i4")
    e_from = np.array([e[0] for e in edges], dtype="<i4")
    e_to = np.array([e[1] for e in edges], dtype="<i4")
    e_len = np.array([e[2] for e in edges], dtype="<f4")
    e_gain = np.array([e[3] for e in edges], dtype="<f4")
    e_loss = np.array([e[4] for e in edges], dtype="<f4")
    e_name = np.array([e[5] for e in edges], dtype="<i4")
    e_kind = np.array([e[6] for e in edges], dtype="u1")

    geom_start = np.zeros(ne + 1, dtype="<i4")
    lat_list, lon_list = [], []
    for i, e in enumerate(edges):
        for x, y in e[7]:
            lon_list.append(round(x * 1e6))
            lat_list.append(round(y * 1e6))
        geom_start[i + 1] = len(lat_list)
    c_lat = np.array(lat_list, dtype="<i4")
    c_lon = np.array(lon_list, dtype="<i4")

    # Adjacency (undirected): each edge is listed under both of its endpoints.
    ends = np.concatenate([e_from, e_to]).astype(np.int64)
    eids = np.concatenate([np.arange(ne), np.arange(ne)]).astype("<i4")
    order = np.argsort(ends, kind="stable")
    adj_edges = eids[order].astype("<i4")
    deg = np.bincount(ends, minlength=nv) if ne else np.zeros(nv, dtype=np.int64)
    adj_start = np.concatenate([[0], np.cumsum(deg)]).astype("<i4")

    # Spatial grid of edges for snapping a tap to the nearest trail.
    min_lat, min_lon = bbox[1] - CELL_DEG, bbox[0] - CELL_DEG
    rows = int(math.ceil((bbox[3] - bbox[1]) / CELL_DEG)) + 3
    cols = int(math.ceil((bbox[2] - bbox[0]) / CELL_DEG)) + 3
    pair_cells, pair_edges = [], []
    for i, e in enumerate(edges):
        for c in _cells_for_edge(e[7], min_lat, min_lon, rows, cols):
            pair_cells.append(c)
            pair_edges.append(i)
    pc = np.array(pair_cells, dtype=np.int64)
    pe = np.array(pair_edges, dtype="<i4")
    order = np.argsort(pc, kind="stable")
    cell_edges = pe[order].astype("<i4")
    counts = np.bincount(pc, minlength=rows * cols) if len(pc) else np.zeros(rows * cols, dtype=np.int64)
    cell_start = np.concatenate([[0], np.cumsum(counts)]).astype("<i4")

    name_bytes = bytearray(struct.pack("<I", len(names)))
    for n in names:
        b = n.encode("utf-8")[:65535]
        name_bytes += struct.pack("<H", len(b)) + b

    data = {
        "vlat": vlat.tobytes(), "vlon": vlon.tobytes(), "adj_start": adj_start.tobytes(),
        "adj_edges": adj_edges.tobytes(), "e_from": e_from.tobytes(), "e_to": e_to.tobytes(),
        "e_len": e_len.tobytes(), "e_gain": e_gain.tobytes(), "e_loss": e_loss.tobytes(),
        "e_name": e_name.tobytes(), "e_kind": e_kind.tobytes(), "geom_start": geom_start.tobytes(),
        "c_lat": c_lat.tobytes(), "c_lon": c_lon.tobytes(), "cell_start": cell_start.tobytes(),
        "cell_edges": cell_edges.tobytes(), "names": bytes(name_bytes),
    }
    body = bytearray()
    offsets = []
    pos = HEADER_SIZE
    for name in SECTIONS:
        pad = (-pos) % 8
        body += b"\0" * pad
        pos += pad
        offsets.append(pos)
        body += data[name]
        pos += len(data[name])
    header = bytearray(MAGIC)
    header += struct.pack("<7I", VERSION, nv, ne, len(c_lat), rows, cols, len(names))
    header += struct.pack("<3d", CELL_DEG, min_lat, min_lon)
    header += struct.pack(f"<{len(SECTIONS)}Q", *offsets)
    header += b"\0" * (HEADER_SIZE - len(header))
    return bytes(header) + bytes(body)


def write(path, vertices, edges, names, bbox):
    blob = build(vertices, edges, names, bbox)
    # mtime=0 so an unchanged network produces an identical file (the app skips re-downloading it).
    with open(path, "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", mtime=0, compresslevel=6) as gz:
        gz.write(blob)
    return len(blob)

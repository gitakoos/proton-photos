#!/usr/bin/env python3
"""Builds app/src/main/assets/world_detail.bin from Natural Earth 10m GeoJSON (public domain).

Inputs (from https://github.com/nvkelso/natural-earth-vector, folder geojson/):
  ne_10m_admin_0_countries.geojson
  ne_10m_admin_1_states_provinces.geojson
  ne_10m_populated_places_simple.geojson

Usage: build_world_data.py <folder with the three files> <output .bin>

Every ring is simplified once with Douglas-Peucker and each kept point records the coarsest detail level that
still draws it, so the app picks a level from the zoom and skips the rest. All values are little-endian:

  magic "PWD2"
  u32 countries, each: u16 nameLen, utf8 name, 2 bytes ISO A2, u8 flags, f32 centreLat, f32 centreLng
  u32 provinces, each: u16 nameLen, utf8 name, 2 bytes ISO A2, u8 flags, f32 centreLat, f32 centreLng
  (flags bit 0: a city-level unit that belongs to the province around it, such as a Hungarian city with county rights)
  country rings, then province rings, each: u32 ringCount, then per ring
      u32 owner (index above), u32 n, f32 minLat, f32 minLng, f32 maxLat, f32 maxLng,
      f32[n] lat, f32[n] lng, u8[n] level
  u32 places, each: f32 lat, f32 lng, i32 population, f32 labelZoom, u16 nameLen, utf8 name
"""
import json
import os
import struct
import sys

import numpy as np

# Level k draws a point when its importance is at least LEVEL_TOLERANCE_DEG[k] degrees.
LEVEL_TOLERANCE_DEG = [0.30, 0.12, 0.05, 0.02, 0.01, 0.005, 0.0]
# Points less important than this are not stored at all: country outlines and the finer province borders.
MIN_KEEP_COUNTRY_DEG = 0.003
MIN_KEEP_PROVINCE_DEG = 0.007
# Natural Earth unit types that are a city carved out of the province around it.
CITY_LEVEL_TYPES = {'Urban county', 'Highly Urbanized City'}


def importance(lat, lng):
    """Douglas-Peucker importance of every point of an open polyline: the distance at which it would be dropped."""
    n = len(lat)
    rank = np.zeros(n, dtype=np.float64)
    rank[0] = rank[n - 1] = np.inf
    stack = [(0, n - 1)]
    while stack:
        i, j = stack.pop()
        if j - i < 2:
            continue
        ax, ay, bx, by = lng[i], lat[i], lng[j], lat[j]
        px = lng[i + 1:j]
        py = lat[i + 1:j]
        dx, dy = bx - ax, by - ay
        length = (dx * dx + dy * dy) ** 0.5
        if length == 0.0:
            d = np.hypot(px - ax, py - ay)
        else:
            d = np.abs(dy * (px - ax) - dx * (py - ay)) / length
        k = int(np.argmax(d))
        rank[i + 1 + k] = d[k]
        m = i + 1 + k
        stack.append((i, m))
        stack.append((m, j))
    return rank


def level_of(rank):
    for k, tol in enumerate(LEVEL_TOLERANCE_DEG):
        if rank >= tol:
            return k
    return len(LEVEL_TOLERANCE_DEG) - 1


def rings_of(geometry):
    if geometry is None:
        return
    polys = geometry['coordinates'] if geometry['type'] == 'MultiPolygon' else [geometry['coordinates']]
    for poly in polys:
        for ring in poly:
            if len(ring) >= 4:
                yield ring


def simplify(ring, min_keep):
    arr = np.asarray(ring, dtype=np.float64)
    lng, lat = arr[:, 0], arr[:, 1]
    rank = importance(lat, lng)
    keep = rank >= min_keep
    if keep.sum() < 4:
        return None
    lat32 = lat[keep].astype(np.float32)
    lng32 = lng[keep].astype(np.float32)
    levels = np.array([level_of(r) for r in rank[keep]], dtype=np.uint8)
    return lat32, lng32, levels


def ring_centre(lat, lng):
    x, y = lng.astype(np.float64), lat.astype(np.float64)
    x1, y1 = np.roll(x, -1), np.roll(y, -1)
    cross = x * y1 - x1 * y
    area = cross.sum() / 2.0
    if abs(area) < 1e-12:
        return float(y.mean()), float(x.mean())
    cx = ((x + x1) * cross).sum() / (6.0 * area)
    cy = ((y + y1) * cross).sum() / (6.0 * area)
    return float(cy), float(cx)


def text(s):
    b = s.encode('utf-8')
    return struct.pack('<H', len(b)) + b


def iso_bytes(code):
    code = (code or '').upper()
    if len(code) != 2 or not code.isalpha():
        code = '--'
    return code.encode('ascii')


def build_layer(features, iso_of, name_of, min_keep, flags_of=lambda p: 0):
    owners = []
    ring_blobs = []
    for feature in features:
        best = None
        rings = []
        for ring in rings_of(feature['geometry']):
            simplified = simplify(ring, min_keep)
            if simplified is None:
                continue
            rings.append(simplified)
            if best is None or len(simplified[0]) > len(best[0]):
                best = simplified
        if not rings:
            continue
        centre_lat, centre_lng = ring_centre(best[0], best[1])
        owner = len(owners)
        props = feature['properties']
        owners.append(text(name_of(props)) + iso_bytes(iso_of(props)) + struct.pack('<Bff', flags_of(props), centre_lat, centre_lng))
        for lat, lng, levels in rings:
            n = len(lat)
            ring_blobs.append(
                struct.pack('<II4f', owner, n, float(lat.min()), float(lng.min()), float(lat.max()), float(lng.max()))
                + lat.astype('<f4').tobytes() + lng.astype('<f4').tobytes() + levels.tobytes()
            )
    return owners, ring_blobs


def load(folder, name):
    with open(os.path.join(folder, name), encoding='utf-8') as f:
        return json.load(f)['features']


def main():
    folder, out = sys.argv[1], sys.argv[2]

    def country_iso(p):
        for key in ('ISO_A2_EH', 'ISO_A2'):
            v = p.get(key)
            if v and v != '-99':
                return v
        return None

    countries, country_rings = build_layer(
        load(folder, 'ne_10m_admin_0_countries.geojson'), country_iso, lambda p: p.get('NAME') or '',
        MIN_KEEP_COUNTRY_DEG)
    provinces, province_rings = build_layer(
        load(folder, 'ne_10m_admin_1_states_provinces.geojson'), lambda p: p.get('iso_a2'), lambda p: p.get('name') or '',
        MIN_KEEP_PROVINCE_DEG, lambda p: 1 if p.get('type_en') in CITY_LEVEL_TYPES else 0)

    places = []
    for feature in load(folder, 'ne_10m_populated_places_simple.geojson'):
        p = feature['properties']
        name = p.get('name')
        if not name:
            continue
        label_zoom = p.get('min_zoom')
        places.append(
            struct.pack('<ffif', float(p['latitude']), float(p['longitude']), int(p.get('pop_max') or 0),
                        float(label_zoom if label_zoom is not None else 10.0)) + text(name)
        )

    with open(out, 'wb') as f:
        f.write(b'PWD2')
        for owners, rings in ((countries, country_rings), (provinces, province_rings)):
            f.write(struct.pack('<I', len(owners)))
            for owner in owners:
                f.write(owner)
        for rings in (country_rings, province_rings):
            f.write(struct.pack('<I', len(rings)))
            for blob in rings:
                f.write(blob)
        f.write(struct.pack('<I', len(places)))
        for place in places:
            f.write(place)

    print('countries', len(countries), 'rings', len(country_rings))
    print('provinces', len(provinces), 'rings', len(province_rings))
    print('places', len(places))
    print('bytes', os.path.getsize(out))


if __name__ == '__main__':
    main()

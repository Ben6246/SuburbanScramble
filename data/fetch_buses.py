"""
Fetches CTA bus routes from Overpass and saves as <city>_buses.geojson.
Usage: python3 fetch_buses.py <city>   (default: chicago)
The bounding box is derived from the existing <city>.geojson file.
"""

import json
import sys
import urllib.request
import urllib.parse

def get_bbox(geojson_path):
    with open(geojson_path) as f:
        data = json.load(f)
    lats, lons = [], []
    for feat in data["features"]:
        geom = feat.get("geometry", {})
        gtype = geom.get("type", "")
        coords = []
        if gtype == "Polygon":
            for ring in geom.get("coordinates", []):
                coords.extend(ring)
        elif gtype == "MultiPolygon":
            for poly in geom.get("coordinates", []):
                for ring in poly:
                    coords.extend(ring)
        for lon, lat in coords:
            lats.append(lat)
            lons.append(lon)
    return min(lats), min(lons), max(lats), max(lons)

def fetch_routes(bbox):
    s, w, n, e = bbox
    query = f"""
    [out:json][timeout:120];
    relation["route"="bus"]["network"="CTA"]({s},{w},{n},{e});
    out geom;
    """
    data = urllib.parse.urlencode({"data": query}).encode()
    req = urllib.request.Request(
        "https://overpass-api.de/api/interpreter",
        data=data,
        headers={"User-Agent": "UrbEx/1.0 bus-fetch"}
    )
    with urllib.request.urlopen(req, timeout=150) as resp:
        return json.loads(resp.read())

def to_geojson(elements):
    seen_refs = set()
    features = []
    for el in elements:
        if el["type"] != "relation":
            continue
        tags = el.get("tags", {})
        ref = tags.get("ref", str(el["id"]))
        if ref in seen_refs:
            continue
        seen_refs.add(ref)

        segments = []
        for member in el.get("members", []):
            if member.get("type") == "way" and "geometry" in member:
                coords = [[pt["lon"], pt["lat"]] for pt in member["geometry"]]
                if coords:
                    segments.append(coords)

        if not segments:
            continue

        features.append({
            "type": "Feature",
            "properties": {
                "route": "bus",
                "ref": ref,
                "name": tags.get("name", ""),
                "colour": tags.get("colour", "#1e90ff"),
            },
            "geometry": {"type": "MultiLineString", "coordinates": segments}
        })

    return {"type": "FeatureCollection", "features": features}

def main():
    city = sys.argv[1] if len(sys.argv) > 1 else "chicago"
    print(f"Computing bounding box from {city}.geojson...")
    bbox = get_bbox(f"{city}.geojson")
    print(f"Bounding box: {bbox}")

    print("Querying Overpass for CTA bus routes (this may take ~30s)...")
    raw = fetch_routes(bbox)
    elements = raw.get("elements", [])
    print(f"Got {len(elements)} route relations.")

    geojson = to_geojson(elements)
    out = f"{city}_buses.geojson"
    print(f"Converted to {len(geojson['features'])} unique routes.")

    with open(out, "w") as f:
        json.dump(geojson, f)
    print(f"Saved to {out}.")

if __name__ == "__main__":
    main()

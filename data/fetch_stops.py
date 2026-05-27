"""
Fetches CTA train stations and bus stops from Overpass.
Saves as <city>_stations.geojson and <city>_bus_stops.geojson.
Usage: python3 fetch_stops.py <city>   (default: chicago)
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

def fetch(query):
    data = urllib.parse.urlencode({"data": query}).encode()
    req = urllib.request.Request(
        "https://overpass-api.de/api/interpreter",
        data=data,
        headers={"User-Agent": "UrbEx/1.0 stops-fetch"}
    )
    with urllib.request.urlopen(req, timeout=90) as resp:
        return json.loads(resp.read())["elements"]

def nodes_to_geojson(nodes, extra_props={}):
    features = []
    for el in nodes:
        if el["type"] != "node":
            continue
        props = {**el.get("tags", {}), **extra_props}
        features.append({
            "type": "Feature",
            "properties": props,
            "geometry": {"type": "Point", "coordinates": [el["lon"], el["lat"]]}
        })
    return {"type": "FeatureCollection", "features": features}

def main():
    city = sys.argv[1] if len(sys.argv) > 1 else "chicago"
    bbox = get_bbox(f"{city}.geojson")
    s, w, n, e = bbox
    print(f"Bounding box: {bbox}")

    print("Fetching CTA train stations...")
    station_query = f"""
    [out:json][timeout:60];
    node["station"="subway"]["network"="CTA"]({s},{w},{n},{e});
    out body;
    """
    stations = fetch(station_query)
    print(f"  Got {len(stations)} stations.")
    with open(f"{city}_stations.geojson", "w") as f:
        json.dump(nodes_to_geojson(stations), f)
    print(f"  Saved to {city}_stations.geojson.")

    print("Fetching CTA bus stops...")
    stop_query = f"""
    [out:json][timeout:60];
    node["highway"="bus_stop"]["network"="CTA"]({s},{w},{n},{e});
    out body;
    """
    stops = fetch(stop_query)
    print(f"  Got {len(stops)} bus stops.")
    with open(f"{city}_bus_stops.geojson", "w") as f:
        json.dump(nodes_to_geojson(stops), f)
    print(f"  Saved to {city}_bus_stops.geojson.")

if __name__ == "__main__":
    main()

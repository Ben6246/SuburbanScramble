"""
Enriches a city GeoJSON file with population data from Wikidata (property P1082).
Usage: python3 fetch_population.py <city>.geojson
Reads the file in-place, adding a "population" field to each administrative feature.
"""

import json
import sys
import urllib.request
import urllib.parse

def fetch_populations(qids):
    values = " ".join(f"wd:{q}" for q in qids)
    query = f"""
    SELECT ?item ?pop WHERE {{
      VALUES ?item {{ {values} }}
      ?item wdt:P1082 ?pop.
    }}
    """
    url = "https://query.wikidata.org/sparql?" + urllib.parse.urlencode({
        "query": query,
        "format": "json"
    })
    req = urllib.request.Request(url, headers={"User-Agent": "UrbEx/1.0 population-enrichment"})
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read())
    result = {}
    for row in data["results"]["bindings"]:
        qid = row["item"]["value"].split("/")[-1]
        result[qid] = int(row["pop"]["value"])
    return result

def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "chicago.geojson"
    with open(path) as f:
        geojson = json.load(f)

    qids = []
    for feature in geojson["features"]:
        props = feature.get("properties", {})
        if props.get("boundary") == "administrative" and props.get("wikidata"):
            qids.append(props["wikidata"])

    print(f"Fetching population for {len(qids)} features...")
    populations = fetch_populations(qids)
    print(f"Got data for {len(populations)} of them.")

    updated = 0
    for feature in geojson["features"]:
        props = feature.get("properties", {})
        qid = props.get("wikidata")
        if qid and qid in populations:
            props["population"] = populations[qid]
            updated += 1

    with open(path, "w") as f:
        json.dump(geojson, f)

    print(f"Updated {updated} features. Saved to {path}.")

if __name__ == "__main__":
    main()

# Virestrom
Virestrom is a simple, automated tagging and geometry-generation tool suite designed for OpenGeofiction (OGF): Mishota.

More documentation can be found [here](https://minimapper.net/docs/virestrom.html).

## Features
All tools can be accessed from JOSM's **Tools** menu or via their keyboard shortcuts.

---

### 1. Building Creator
* **Shortcut:** `CTRL+SHIFT+A`

Procedurally generates rectangular or custom geometric buildings inside selected landuse/residential areas. 
* Automatically determines alignment and rotations matching nearby road ways.
* Simulates realistic building footprints based on configurable aspect ratios (e.g., standard 25ft × 60ft short-side shapes, 65ft × 30ft long-side shapes, or square/angled layouts).
* Automatically calculates and projects exact latitude/longitude coordinates based on regional meters-per-degree values.

---

### 2. Building Tagger
* **Shortcut:** `CTRL+SHIFT+X`

An interactive GUI panel that automates complex housing, apartment, and commercial tags.
* **Smart Height Calculator:** Input target levels, and the tool calculates randomized, realistic building heights using a variation factor (e.g., ~3.0m per floor for houses, ~3.2m for apartments, ~4.0m for commercial).
* **Weighted Resident Math:** Pick a target population value. The tool generates a weighted average population value around your target.
* **Apartment Math:** Calculates `building:units` automatically based on specified unit sizes, floor counts, and target residency.
* **Address Sequencing:** Remembers city, postcode, and street names across selections, automatically incrementing house numbers.

---

### 3. Rural Farmland & Parcel Splitter
* **Shortcut:** `CTRL+SHIFT+Y` or `ALT+SHIFT+Y`

Powered by the **Java Topology Suite (JTS)**, this tool splits larger, selected polygons into smaller, divided parcels (such as rural farmlands, residential blocks, or subdivisions).
* Splits boundaries along randomly generated or structured dividing lines.
* Retains and applies logical agricultural or residential tags across the newly subdivided areas.

---

### 4. Admin Level Tagger
* **Shortcut:** `CTRL+SHIFT+Q` or `ALT+SHIFT+Q`

Designed to streamline the creation of administrative boundary relations (e.g., states, counties, townships).
* Allows rapid entry of boundary names, populations, administrative levels, and parent regions.
* Automatically constructs JOSM boundary relations, pre-assigning member roles like `outer`.
* Saves session preferences for `admin_level` and `county` so you do not have to re-enter them constantly. Saves `is_in:state` persistently across JOSM sessions.
* *Note: Intended to accelerate basic boundary layouts. Complex multi-nested relation setups (like intricate inner-way holes) should still be verified or constructed manually.*

---

## Installation
1. Ensure you have JOSM's official **`jts`** plugin installed via JOSM's Plugin Manager.
2. Download the built `virestrom-2.jar` and copy it into your JOSM plugins directory. 

Information on locating your system's plugins folder can be found on the [JOSM Plugins Wiki](https://wiki.openstreetmap.org/wiki/JOSM/Plugins#Manually_install_JOSM_plugins).

---

## Development & Compiling

The project compiles with Java 11. It compiles against `josm-tested.jar` and compiles against `jts-core` (which JOSM resolves at runtime via the `Plugin-Requires: jts` manifest header).

### Compile and Package
Use the included `build.sh` script to clean, compile, and bundle your JAR:
```bash
./build.sh

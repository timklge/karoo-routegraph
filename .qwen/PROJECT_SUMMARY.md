# Project Summary

## Overall Goal
Develop a Karoo bicycle computer extension (karoo-routegraph) that provides route elevation graphs, POI management, and minimap functionality, with per-ride-profile POI visibility settings and locally-generated OSM POI databases.

## Key Knowledge

### Project Identity
- **Package:** `de.timklge.karooroutegraph`
- **Repository:** `https://github.com/masiina/karoo-routegraph`
- **Remote:** `git@github.com:masiina/karoo-routegraph.git`
- **Branch:** `master` (current)

### Tech Stack
- **Language:** Kotlin 2.3.20
- **UI:** Jetpack Compose
- **Build:** Gradle 8.13, AGP 8.13.2
- **Target SDK:** 28 (minSdk 26, compileSdk 36)
- **Key Libraries:** karoo-ext 1.1.9 (Hammerhead SDK), Koin 4.2.0, DataStore, Mapbox Turf, OkHttp

### Build Commands
```bash
# Prerequisites
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=$HOME/Android/Sdk

# Build debug APK
./gradlew assembleDebug

# APK location: app/build/outputs/apk/debug/app-debug.apk
```

### Architecture
- **Settings Storage:** Jetpack DataStore (Preferences) with JSON-serialized data classes
- **Two settings objects:** `RouteGraphSettings` (global) and `RouteGraphPoiSettings` (per-profile)
- **Karoo SDK events:** Uses `ActiveRideProfile` for ride profile detection, `UserProfile` for rider data
- **Navigation:** Manual Compose state-based (no NavHost)
- **Dependency Injection:** Koin

### POI Database Pipeline
- **OSM data extraction:** Docker-based pipeline in `osm/` folder
- **Source:** Geofabrik country extracts (e.g., `finland-latest.osm.pbf`)
- **Filter:** `osm/poi-filter.txt` defines which OSM tags to extract
- **Output:** SQLite database per country, served from GitHub repo at `pois/pois.fi.db`
- **Download URL:** `https://raw.githubusercontent.com/masiina/karoo-routegraph/master/pois/`

### Current POI Filter (`osm/poi-filter.txt`)
```
amenity=drinking_water,restaurant,cafe,ice_cream,fast_food
shop=supermarket,bakery,convenience
tourism=camp_site,attraction,viewpoint
natural=beach
leisure=swimming_area
```

### Ride Profile System
- **Detection:** Listens to `ActiveRideProfile` KarooEvent (fires when user switches profiles on device)
- **Storage:** Profile ID → custom name mapping in DataStore (`profileIdToNameKey`)
- **Settings:** Each Karoo ride profile gets its own POI settings (categories, sort options, toggles)
- **Fallback:** Uses Karoo's built-in profile name if no custom name is set
- **API:** `streamActiveKarooProfileName()`, `saveProfileViewSettings()`, `streamProfileViewSettings()`

### Important Code Files
| File | Purpose |
|------|---------|
| `KarooSystemServiceProvider.kt` | Settings storage, profile detection, DataStore operations |
| `RouteGraphUpdateManager.kt` | Main route/POI processing, combines profile settings |
| `PointsOfInterestScreen.kt` | POI settings UI (now profile-aware) |
| `ProfileSelectionScreen.kt` | Profile naming UI |
| `NearbyPoiCategory.kt` | POI category enum matching poi-filter.txt |
| `NearbyPOIPbfDownloadService.kt` | POI database download from GitHub |
| `osm/extract.sh` | OSM to SQLite extraction script |

## Completed Features

1. **[DONE]** OSM POI extraction pipeline for Finland
   - Docker-based extraction from Geofabrik country files
   - POI filter matching `poi-filter.txt`
   - Generated `pois.fi.db` served from GitHub repo

2. **[DONE]** POI category filtering matching `poi-filter.txt`
   - 13 categories: Water, Restaurant, Café, Ice Cream, Fast Food, Supermarket, Convenience, Bakery, Camping Site, Attraction, Viewpoint, Beach, Swimming Area
   - Unnamed POIs show category name instead of "Unnamed POI"

3. **[DONE]** POI database source from GitHub repo (replaced `routegraph.timklge.de`)

4. **[DONE]** Per-profile POI visibility settings
   - Uses `ActiveRideProfile` KarooEvent for reliable profile detection
   - Each Karoo ride profile gets independent POI settings
   - Settings: auto-add categories, sort options, offline storage toggle
   - Optional custom profile naming
   - **Tested on real Karoo device** - profile switching works correctly

5. **[DONE]** Merged all development branches to `master`

## Future Enhancements
- Release signing for production APK (requires keystore setup)
- Update `pois.fi.db` when OSM data changes or filter is modified
- Add more country POI databases
- Profile-specific settings for other features (elevation graph zoom, chevron display, etc.)

---

## Summary Metadata
**Update time**: 2026-04-06T14:48:25.669Z

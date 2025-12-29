package de.timklge.karooroutegraph.screens

import de.timklge.karooroutegraph.pois.NearbyPOI
import io.hammerhead.karooext.models.Symbol

data class NearbyPOISymbol(val element: NearbyPOI, val poi: Symbol.POI)
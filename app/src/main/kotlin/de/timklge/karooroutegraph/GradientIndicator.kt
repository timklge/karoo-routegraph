package de.timklge.karooroutegraph

import androidx.annotation.DrawableRes
import com.mapbox.geojson.Point

class GradientIndicator(val id: String, val distance: Float, val gradientPercent: Float, val position: Point, @DrawableRes val drawableRes: Int){
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as GradientIndicator

        if (id != other.id) return false

        return true
    }

    override fun hashCode(): Int {
        val result = id.hashCode()
        return result
    }

    override fun toString(): String {
        return "GradientIndicator(id='$id', distance=$distance, $gradientPercent)"
    }
}
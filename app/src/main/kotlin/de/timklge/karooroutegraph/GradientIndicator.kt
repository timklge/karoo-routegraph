/*
 * Copyright 2026 timklge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
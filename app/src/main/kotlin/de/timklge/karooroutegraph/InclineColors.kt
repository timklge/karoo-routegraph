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

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes

@DrawableRes
fun getInclineIndicator(percent: Float): Int? {
    return when (percent){
        in -Float.MAX_VALUE..<-8f -> R.drawable.chevrondown2 // Dark blue
        in -8f..<-5f -> R.drawable.chevrondown1 // Light blue
        in -5f..<-2f -> R.drawable.chevrondown0 // White
        in 1f..<2f -> R.drawable.chevron1 // Light green
        in 2f..<5f -> R.drawable.chevron0 // Dark green
        in 5f..<8f -> R.drawable.chevron2 // Yellow
        in 8f..<11f -> R.drawable.chevron3 // Light Orange
        in 11f..<14f -> R.drawable.chevron4 // Dark Orange
        in 14f..<20f -> R.drawable.chevron5 // Red
        in 20f..Float.MAX_VALUE -> R.drawable.chevron6 // Purple
        else -> null
    }
}

@ColorRes
fun getInclineIndicatorColor(percent: Float): Int? {
    return when(percent) {
        in -Float.MAX_VALUE..<-8f -> R.color.eleDarkBlue // Dark blue
        in -8f..<-5f -> R.color.eleLightBlue // Light blue
        in -5f..<-2f -> R.color.eleWhite // White
        in 1f..<2f -> R.color.eleLightGreen // Light green
        in 2f..<5f -> R.color.eleDarkGreen // Dark green
        in 5f..<8f -> R.color.eleYellow // Yellow
        in 8f..<11f -> R.color.eleLightOrange // Light Orange
        in 11f..<14f -> R.color.eleDarkOrange // Dark Orange
        in 14f..<20f -> R.color.eleRed // Red
        in 20f..Float.MAX_VALUE -> R.color.elePurple // Purple
        else -> null
    }
}
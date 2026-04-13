package de.timklge.karooroutegraph

import androidx.annotation.ColorRes

@ColorRes
fun getInclineIndicatorColor(percent: Float): Int? {
    return when(percent) {
        in -Float.MAX_VALUE..<-7.5f -> R.color.eleDarkBlue // Dark blue
        in -7.5f..<-4.6f -> R.color.eleLightBlue // Light blue
        in -4.6f..<-2f -> R.color.eleWhite // White
        in 2f..<4.6f -> R.color.eleDarkGreen // Dark green
        in 4.6f..<7.5f -> R.color.eleLightGreen // Light green
        in 7.5f..<12.5f -> R.color.eleYellow // Yellow
        in 12.5f..<15.5f -> R.color.eleLightOrange // Light Orange
        in 15.5f..<19.5f -> R.color.eleDarkOrange // Dark Orange
        in 19.5f..<23.5f -> R.color.eleRed // Red
        in 23.5f..Float.MAX_VALUE -> R.color.elePurple // Purple
        else -> null
    }
}
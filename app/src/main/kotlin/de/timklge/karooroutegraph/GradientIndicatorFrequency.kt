package de.timklge.karooroutegraph

enum class GradientIndicatorFrequency(val stepsPerDisplayDiagonal: Int, val labelResourceId: Int) {
    LOW(3, R.string.gradient_frequency_low),
    MEDIUM(6, R.string.gradient_frequency_medium),
    HIGH(14, R.string.gradient_frequency_high),
    MAX(19, R.string.gradient_frequency_max);
}
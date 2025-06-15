package de.timklge.karooroutegraph

enum class GradientIndicatorFrequency(val stepsPerDisplayDiagonal: Int) {
    LOW(3),
    MEDIUM(6),
    HIGH(14),
    MAX(19);
}
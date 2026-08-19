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

enum class GradientIndicatorFrequency(val stepsPerDisplayDiagonal: Int, val labelResourceId: Int) {
    LOW(3, R.string.gradient_frequency_low),
    MEDIUM(6, R.string.gradient_frequency_medium),
    HIGH(14, R.string.gradient_frequency_high),
    MAX(19, R.string.gradient_frequency_max);
}
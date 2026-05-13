package com.dangerfield.cards.libraries.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset

@Immutable
class Point(val x: Float, val y: Float) {

    fun offset(x: Float = 0f, y: Float = 0f): Point {
        return Point(this.x + x, this.y + y)
    }

    fun offset(offset: Offset): Point {
        return Point(this.x + offset.x, this.y + offset.y)
    }

    fun minus(other: Point): Point {
        return Point(x - other.x, y - other.y)
    }

    fun minusOffset(other: Offset): Point {
        return Point(x - other.x, y - other.y)
    }

    fun midpoint(other: Point): Point {
        return Point((x + other.x) / 2, (y + other.y) / 2)
    }

    fun scale(scale: Float): Point {
        return Point(x * scale, y * scale)
    }
    operator fun times(scale: Float): Point {
        return scale(scale)
    }

    override operator fun equals(other: Any?): Boolean {
        if (this === other) return true
        val point = other as Point
        return x == point.x && y == point.y
    }

    override fun hashCode(): Int {
        var result = x
        result = 31 * result + y
        return result.toInt()
    }
}
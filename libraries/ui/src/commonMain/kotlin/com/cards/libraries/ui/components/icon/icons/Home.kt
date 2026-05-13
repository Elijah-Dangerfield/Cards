package com.dangerfield.cards.libraries.ui.components.icon.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val Home: ImageVector
    get() {
        if (_Home != null) return _Home!!

        _Home = ImageVector.Builder(
            name = "Home",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFF0F172A)),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(2.25f, 12f)
                lineTo(11.2045f, 3.04549f)
                curveTo(11.6438f, 2.60615f, 12.3562f, 2.60615f, 12.7955f, 3.04549f)
                lineTo(21.75f, 12f)
                moveTo(4.5f, 9.75f)
                verticalLineTo(19.875f)
                curveTo(4.5f, 20.4963f, 5.00368f, 21f, 5.625f, 21f)
                horizontalLineTo(9.75f)
                verticalLineTo(16.125f)
                curveTo(9.75f, 15.5037f, 10.2537f, 15f, 10.875f, 15f)
                horizontalLineTo(13.125f)
                curveTo(13.7463f, 15f, 14.25f, 15.5037f, 14.25f, 16.125f)
                verticalLineTo(21f)
                horizontalLineTo(18.375f)
                curveTo(18.9963f, 21f, 19.5f, 20.4963f, 19.5f, 19.875f)
                verticalLineTo(9.75f)
                moveTo(8.25f, 21f)
                horizontalLineTo(16.5f)
            }
        }.build()

        return _Home!!
    }

private var _Home: ImageVector? = null

val HomeFilled: ImageVector
    get() {
        if (_HomeFilled != null) return _HomeFilled!!

        _HomeFilled = ImageVector.Builder(
            name = "Home",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = SolidColor(Color(0xFF0F172A))
            ) {
                moveTo(11.4697f, 2.42007f)
                curveTo(11.7626f, 2.12717f, 12.2374f, 2.12717f, 12.5303f, 2.42007f)
                lineTo(22.2803f, 12.1701f)
                curveTo(22.5732f, 12.463f, 22.5732f, 12.9378f, 22.2803f, 13.2307f)
                curveTo(21.9874f, 13.5236f, 21.5126f, 13.5236f, 21.2197f, 13.2307f)
                lineTo(20.25f, 12.2611f)
                verticalLineTo(19.875f)
                curveTo(20.25f, 20.9105f, 19.4105f, 21.75f, 18.375f, 21.75f)
                horizontalLineTo(14.25f)
                curveTo(13.8358f, 21.75f, 13.5f, 21.4142f, 13.5f, 21f)
                verticalLineTo(16.125f)
                curveTo(13.5f, 15.9179f, 13.3321f, 15.75f, 13.125f, 15.75f)
                horizontalLineTo(10.875f)
                curveTo(10.6679f, 15.75f, 10.5f, 15.9179f, 10.5f, 16.125f)
                verticalLineTo(21f)
                curveTo(10.5f, 21.4142f, 10.1642f, 21.75f, 9.75f, 21.75f)
                horizontalLineTo(5.625f)
                curveTo(4.58947f, 21.75f, 3.75f, 20.9105f, 3.75f, 19.875f)
                verticalLineTo(12.2611f)
                lineTo(2.78033f, 13.2307f)
                curveTo(2.48744f, 13.5236f, 2.01256f, 13.5236f, 1.71967f, 13.2307f)
                curveTo(1.42678f, 12.9378f, 1.42678f, 12.463f, 1.71967f, 12.1701f)
                lineTo(11.4697f, 2.42007f)
                close()
            }
        }.build()

        return _HomeFilled!!
    }

private var _HomeFilled: ImageVector? = null
package com.dangerfield.cards.libraries.ui.components.icon

import BootstrapFillBarChart
import BootstrapOutlineBarChart
import EraserIcon
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bathtub
import androidx.compose.material.icons.filled.Blind
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Egg
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Person4
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Window
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Egg
import androidx.compose.material.icons.rounded.EggAlt
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LocationCity
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.QuestionMark
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.VolunteerActivism
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.icon.icons.App_shortcut
import com.dangerfield.cards.libraries.ui.components.icon.icons.HomeFilled
import com.dangerfield.cards.libraries.ui.components.icon.icons.TipJarIcon
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.HorizontalSpacerD800
import com.dangerfield.cards.libraries.ui.system.LocalBuildInfo
import com.dangerfield.cards.system.VerticalSpacerD500
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Design‑system icon resource, similar to `ColorResource`/`TypographyResource`.
 *
 * Icons are always referenced through this type so callers cannot
 * reach directly for raw Material icons.
 */
@Immutable
data class IconResource(
    val imageVector: ImageVector,
    val contentDescription: String?,
    val identifier: String? = null,
)

enum class IconStroke {
    Thin,
    Regular,
    Thick,
}

enum class Icons(
    internal val default: ImageVector,
    internal val filled: ImageVector? = null,
    internal val outlined: ImageVector? = null ,
    internal val outlinedThin: ImageVector? = null ,
    internal val outlinedThick: ImageVector? = null ,
    internal val twoTone: ImageVector? = null,
) {

    Home(
        default = com.dangerfield.cards.libraries.ui.components.icon.icons.Home,
        filled = HomeFilled,
        outlined = com.dangerfield.cards.libraries.ui.components.icon.icons.Home,
    ),

    Shop(
        default = androidx.compose.material.icons.Icons.Outlined.ShoppingBag,
        filled = androidx.compose.material.icons.Icons.Filled.ShoppingBag,
        outlined = androidx.compose.material.icons.Icons.Outlined.ShoppingBag,
    ),

    Time(
        default = androidx.compose.material.icons.Icons.Filled.AccessTime,
        filled = androidx.compose.material.icons.Icons.Filled.AccessTime,
        outlined = androidx.compose.material.icons.Icons.Outlined.AccessTime,
    ),

    Chart(
        default = BootstrapFillBarChart,
        filled = BootstrapFillBarChart,
        outlined = BootstrapOutlineBarChart,
    ),

    Tools(
        default = androidx.compose.material.icons.Icons.Default.Build,
    ),

    Pencil(
        default = androidx.compose.material.icons.Icons.Rounded.Edit,
    ),

    Refresh(
        default = androidx.compose.material.icons.Icons.Rounded.Refresh,
    ),

    Receipt(
        default = androidx.compose.material.icons.Icons.Rounded.Receipt,
    ),

    Warning(
        default = androidx.compose.material.icons.Icons.Default.Warning,
    ),

    CloudOff(
        default = androidx.compose.material.icons.Icons.Default.CloudOff,
    ),

    TipJar(
        default = TipJarIcon,
    ),

    Delete(
        default = androidx.compose.material.icons.Icons.Default.Delete,
    ),

    Charity(
        default = androidx.compose.material.icons.Icons.Rounded.VolunteerActivism,
    ),

    Dot(
        default = androidx.compose.material.icons.Icons.Default.Adjust,
    ),

    Location(
        default = androidx.compose.material.icons.Icons.Rounded.LocationOn,
    ),

    Lock(
        default = androidx.compose.material.icons.Icons.Rounded.Lock,
    ),

    ThumbsUp(
        default = androidx.compose.material.icons.Icons.Rounded.ThumbUp,
    ),

    Question(
        default = androidx.compose.material.icons.Icons.Rounded.QuestionMark,
    ),

    MoreHoriz(
        default = androidx.compose.material.icons.Icons.Rounded.MoreHoriz,
    ),

    Chat(
        default = androidx.compose.material.icons.Icons.Rounded.ChatBubble,
    ),

    Bug(
        default = androidx.compose.material.icons.Icons.Rounded.BugReport,
    ),

    ThumbsDown(
        default = androidx.compose.material.icons.Icons.Rounded.ThumbDown,
    ),


    AppShortCut(
        default = App_shortcut
    ),


    Eraser(
        default = EraserIcon
    ),


    Plus(
        default = androidx.compose.material.icons.Icons.Default.Add,
    ),

    X(androidx.compose.material.icons.Icons.Rounded.Close),

    Check(androidx.compose.material.icons.Icons.Rounded.Check),

    SnowFlake(com.dangerfield.cards.libraries.ui.components.icon.icons.SnowFlake),

    Person(
        androidx.compose.material.icons.Icons.Rounded.Person,
        androidx.compose.material.icons.Icons.Rounded.Person,
         androidx.compose.material.icons.Icons.Outlined.Person,
        ),

    Info(
        androidx.compose.material.icons.Icons.Outlined.Info,
        androidx.compose.material.icons.Icons.Rounded.Info,
        androidx.compose.material.icons.Icons.Outlined.Info
    ),

    Settings(androidx.compose.material.icons.Icons.Rounded.Settings),

    ChevronLeft(androidx.compose.material.icons.Icons.Rounded.ChevronLeft),

    ChevronRight(androidx.compose.material.icons.Icons.Rounded.ChevronRight),


    ArrowBack(
        default = androidx.compose.material.icons.Icons.AutoMirrored.Default.ArrowBack,
    ),

    ArrowUp(
        default = androidx.compose.material.icons.Icons.Default.ArrowUpward,
    ),

    Camera(
        default = androidx.compose.material.icons.Icons.Rounded.CameraAlt,
    ),

    PhotoLibrary(
        default = androidx.compose.material.icons.Icons.Rounded.PhotoLibrary,
    ),

    Split(
        default = androidx.compose.material.icons.Icons.Filled.CallSplit,
    ),

    Add(
        default = androidx.compose.material.icons.Icons.Default.Add,
    ),

    Close(
        default = androidx.compose.material.icons.Icons.Rounded.Close,
    ),

    Undo(
        default = androidx.compose.material.icons.Icons.AutoMirrored.Filled.Undo,
    ),

    Redo(
        default = androidx.compose.material.icons.Icons.AutoMirrored.Filled.Redo,
    ),

    FitScreen(
        default = androidx.compose.material.icons.Icons.Rounded.FitScreen,
    ),

    Visibility(
        default = androidx.compose.material.icons.Icons.Outlined.Visibility,
        filled = androidx.compose.material.icons.Icons.Filled.Visibility,
        outlined = androidx.compose.material.icons.Icons.Outlined.Visibility,
    ),

    VisibilityOff(
        default = androidx.compose.material.icons.Icons.Outlined.VisibilityOff,
        filled = androidx.compose.material.icons.Icons.Filled.VisibilityOff,
        outlined = androidx.compose.material.icons.Icons.Outlined.VisibilityOff,
    )

    ;

    operator fun invoke(contentDescription: String?): IconResource {
        return IconResource(
            imageVector = default,
            contentDescription = contentDescription,
            identifier = name,
        )
    }

}

@Composable
fun Icons.Filled(contentDescription: String?): IconResource {
    return IconResource(
        imageVector = filled ?: run {
            if (LocalBuildInfo.current.isDebug) throw IllegalStateException("Missing filled icon for $name") else default
        },
        contentDescription = contentDescription,
    )
}

@Composable
fun Icons.Outlined(contentDescription: String?, stroke: IconStroke = IconStroke.Regular): IconResource {
    return IconResource(
        imageVector = when (stroke) {
            IconStroke.Thin -> outlinedThin
            IconStroke.Regular -> outlined
            IconStroke.Thick -> outlinedThick
        } ?: run {
            if (LocalBuildInfo.current.isDebug) throw IllegalStateException("Missing outline ${stroke.name} icon for $name") else (outlined ?: default)
        },

        contentDescription = contentDescription,
    )
}

@Composable
fun Icons.TwoTone(contentDescription: String?): IconResource {
    return IconResource(
        imageVector = twoTone ?: run {
            if (LocalBuildInfo.current.isDebug) throw IllegalStateException("Missing two tone icon for $name") else default
        },
        contentDescription = contentDescription,
    )
}
@Preview
@Composable
private fun IconPreviewGrid() {
    PreviewContent {
        androidx.compose.foundation.lazy.LazyColumn {
            items(Icons.entries.size) { index ->
                val icon = Icons.entries[index]
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    modifier = Modifier.border(1.dp, Color.Gray).padding(vertical = 32.dp)
                ) {
                    @Composable
                    fun IconCell(
                        modifier: Modifier = Modifier,
                        label: String,
                        image: ImageVector?
                    ) {
                        Column(
                            modifier,
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                        ) {
                            if (image != null) {
                                androidx.compose.material3.Icon(
                                    imageVector = image,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            } else {
                                Box(
                                    contentAlignment = androidx.compose.ui.Alignment.Center,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .border(1.dp, Color.Gray)
                                ) {
                                    Text("?")
                                }
                            }
                            
                            VerticalSpacerD500()

                            Text(
                                text = label,
                                maxLines = 1
                            )
                        }
                    }

                    val default = icon.default
                    val filled = icon.filled
                    val outlined = icon.outlined
                    val twoTone = icon.twoTone

                    IconCell(modifier = Modifier.weight(1f), icon.name, default)
                    HorizontalSpacerD800()
                    IconCell(modifier = Modifier.weight(1f), "Filled", filled)
                    HorizontalSpacerD800()
                    IconCell(modifier = Modifier.weight(1f), "Outlined", outlined)
                    HorizontalSpacerD800()
                    IconCell(modifier = Modifier.weight(1f), "TwoTone", twoTone)
                }
            }
        }
    }
}





















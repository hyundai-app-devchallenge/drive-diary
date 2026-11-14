package com.dongbantest.app.ui.v3.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 웹 프로토타입이 쓰는 Lucide 아이콘(ISC 라이선스, lucide-react 1.71.0)의 경로를 그대로 옮긴 것.
 * 24×24, 선 굵기 2, 둥근 끝. 색은 Icon(tint)으로 정한다(선 색은 틴트용 자리값).
 */
object DbIcons {
    val House: ImageVector by lazy { lucide("House", "M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8", "M3 10a2 2 0 0 1 .709-1.528l7-6a2 2 0 0 1 2.582 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z") }
    val Car: ImageVector by lazy { lucide("Car", "M19 17h2c.6 0 1-.4 1-1v-3c0-.9-.7-1.7-1.5-1.9C18.7 10.6 16 10 16 10s-1.3-1.4-2.2-2.3c-.5-.4-1.1-.7-1.8-.7H5c-.6 0-1.1.4-1.4.9l-1.4 2.9A3.7 3.7 0 0 0 2 12v4c0 .6.4 1 1 1h2", "M5 17a2 2 0 1 0 4 0a2 2 0 1 0 -4 0", "M9 17h6", "M15 17a2 2 0 1 0 4 0a2 2 0 1 0 -4 0") }
    val MapIcon: ImageVector by lazy { lucide("MapIcon", "M14.106 5.553a2 2 0 0 0 1.788 0l3.659-1.83A1 1 0 0 1 21 4.619v12.764a1 1 0 0 1-.553.894l-4.553 2.277a2 2 0 0 1-1.788 0l-4.212-2.106a2 2 0 0 0-1.788 0l-3.659 1.83A1 1 0 0 1 3 19.381V6.618a1 1 0 0 1 .553-.894l4.553-2.277a2 2 0 0 1 1.788 0z", "M15 5.764v15", "M9 3.236v15") }
    val SquareParking: ImageVector by lazy { lucide("SquareParking", "M9 12h4a2 2 0 0 0 0-4H9.5a.5.5 0 0 0-.5.5V16", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2z") }
    val CircleParking: ImageVector by lazy { lucide("CircleParking", "M9 12h4a2 2 0 0 0 0-4H9.5a.5.5 0 0 0-.5.5V16", "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0") }
    val Pause: ImageVector by lazy { lucide("Pause", "M15 3h3a1 1 0 0 1 1 1v16a1 1 0 0 1 -1 1h-3a1 1 0 0 1 -1 -1v-16a1 1 0 0 1 1 -1z", "M6 3h3a1 1 0 0 1 1 1v16a1 1 0 0 1 -1 1h-3a1 1 0 0 1 -1 -1v-16a1 1 0 0 1 1 -1z") }
    val Play: ImageVector by lazy { lucide("Play", "M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z") }
    val MicOff: ImageVector by lazy { lucide("MicOff", "M12 19v3", "M15 9.34V5a3 3 0 0 0-5.68-1.33", "M16.95 16.95A7 7 0 0 1 5 12v-2", "M18.89 13.23A7 7 0 0 0 19 12v-2", "m2 2 20 20", "M9 9v3a3 3 0 0 0 5.12 2.12") }
    val Mic: ImageVector by lazy { lucide("Mic", "M12 19v3", "M19 10v2a7 7 0 0 1-14 0v-2", "M12 2h0a3 3 0 0 1 3 3v7a3 3 0 0 1 -3 3h0a3 3 0 0 1 -3 -3v-7a3 3 0 0 1 3 -3z") }
    val BookmarkPlus: ImageVector by lazy { lucide("BookmarkPlus", "m19 21-7-4-7 4V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v16z", "M12 7v6", "M15 10H9") }
    val Check: ImageVector by lazy { lucide("Check", "M20 6 9 17l-5-5") }
    val Navigation: ImageVector by lazy { lucide("Navigation", "M3 11L22 2L13 21L11 13L3 11Z") }
    val Image: ImageVector by lazy { lucide("Image", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2z", "M7 9a2 2 0 1 0 4 0a2 2 0 1 0 -4 0", "m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21") }
    val Cloud: ImageVector by lazy { lucide("Cloud", "M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9z") }
    val Lock: ImageVector by lazy { lucide("Lock", "M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-7a2 2 0 0 1 2 -2z", "M7 11V7a5 5 0 0 1 10 0v4") }
    val MapPin: ImageVector by lazy { lucide("MapPin", "M20 10c0 4.993-5.539 10.193-7.399 11.799a1 1 0 0 1-1.202 0C9.539 20.193 4 14.993 4 10a8 8 0 0 1 16 0", "M9 10a3 3 0 1 0 6 0a3 3 0 1 0 -6 0") }
    val Minus: ImageVector by lazy { lucide("Minus", "M5 12h14") }
    val Plus: ImageVector by lazy { lucide("Plus", "M5 12h14", "M12 5v14") }
    val Eye: ImageVector by lazy { lucide("Eye", "M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0", "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0") }
    val Trash2: ImageVector by lazy { lucide("Trash2", "M10 11v6", "M14 11v6", "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6", "M3 6h18", "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2") }
    val Flag: ImageVector by lazy { lucide("Flag", "M4 22V4a1 1 0 0 1 .4-.8A6 6 0 0 1 8 2c3 0 5 2 7.333 2q2 0 3.067-.8A1 1 0 0 1 20 4v10a1 1 0 0 1-.4.8A6 6 0 0 1 16 16c-3 0-5-2-8-2a6 6 0 0 0-4 1.528") }
    val Users: ImageVector by lazy { lucide("Users", "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2", "M16 3.128a4 4 0 0 1 0 7.744", "M22 21v-2a4 4 0 0 0-3-3.87", "M5 7a4 4 0 1 0 8 0a4 4 0 1 0 -8 0") }
    val Volume1: ImageVector by lazy { lucide("Volume1", "M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z", "M16 9a5 5 0 0 1 0 6") }
    val Ear: ImageVector by lazy { lucide("Ear", "M6 8.5a6.5 6.5 0 1 1 13 0c0 6-6 6-6 10a3.5 3.5 0 1 1-7 0", "M15 8.5a2.5 2.5 0 0 0-5 0v1a2 2 0 1 1 0 4") }
    val CircleCheck: ImageVector by lazy { lucide("CircleCheck", "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0", "m16 9-5.5 5.5L8 12") }
    val LoaderCircle: ImageVector by lazy { lucide("LoaderCircle", "M21 12a9 9 0 1 1-6.219-8.56") }
    val Circle: ImageVector by lazy { lucide("Circle", "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0") }
    val MessageCircle: ImageVector by lazy { lucide("MessageCircle", "M2.992 16.342a2 2 0 0 1 .094 1.167l-1.065 3.29a1 1 0 0 0 1.236 1.168l3.413-.998a2 2 0 0 1 1.099.092 10 10 0 1 0-4.777-4.719") }
    val Route: ImageVector by lazy { lucide("Route", "M3 19a3 3 0 1 0 6 0a3 3 0 1 0 -6 0", "M9 19h8.5a3.5 3.5 0 0 0 0-7h-11a3.5 3.5 0 0 1 0-7H15", "M15 5a3 3 0 1 0 6 0a3 3 0 1 0 -6 0") }
    val ArrowLeft: ImageVector by lazy { lucide("ArrowLeft", "m12 19-7-7 7-7", "M19 12H5") }
    val X: ImageVector by lazy { lucide("X", "M18 6 6 18", "m6 6 12 12") }
    val ChevronLeft: ImageVector by lazy { lucide("ChevronLeft", "m15 18-6-6 6-6") }
    val ChevronRight: ImageVector by lazy { lucide("ChevronRight", "m9 18 6-6-6-6") }
    val ChevronDown: ImageVector by lazy { lucide("ChevronDown", "m6 9 6 6 6-6") }
    val ChevronUp: ImageVector by lazy { lucide("ChevronUp", "m18 15-6-6-6 6") }

    private fun lucide(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        paths.forEach { d ->
            b.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }
}

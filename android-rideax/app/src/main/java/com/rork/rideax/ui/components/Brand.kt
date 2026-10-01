package com.rork.rideax.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rork.rideax.ui.theme.RideColors

/** A road sweeping from the lower left up to the top right. */
fun roadCurve(size: Size): Path = Path().apply {
    moveTo(size.width * 0.05f, size.height)
    cubicTo(size.width * 0.1f, size.height * 0.5f, size.width * 0.75f, size.height * 0.32f, size.width, 0f)
}

/** The RideAX monogram: an orange "R" whose bowl opens into a curving highway. */
@Composable
fun BrandMark(modifier: Modifier = Modifier, markSize: Dp = 96.dp) {
    val fontSize = with(LocalDensity.current) { markSize.toSp() }
    Box(modifier.size(markSize * 1.1f), contentAlignment = Alignment.Center) {
        Text(
            text = "R",
            color = RideColors.Accent,
            fontSize = fontSize,
            lineHeight = fontSize,
            fontWeight = FontWeight.Black,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
            modifier = Modifier.offset(x = -markSize * 0.04f),
        )
        Canvas(
            Modifier
                .offset(x = markSize * 0.02f, y = markSize * 0.06f)
                .size(width = markSize * 0.62f, height = markSize * 0.78f)
        ) {
            val unit = markSize.toPx()
            val path = roadCurve(size)
            drawPath(path, RideColors.Canvas, style = Stroke(width = unit * 0.13f, cap = StrokeCap.Round))
            drawPath(
                path,
                Color.White.copy(alpha = 0.85f),
                style = Stroke(
                    width = unit * 0.02f,
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(unit * 0.05f, unit * 0.05f)),
                ),
            )
        }
    }
}

/** "Ride" in white, "AX" in orange, with the kerned tagline underneath. */
@Composable
fun BrandWordmark(modifier: Modifier = Modifier, fontSize: TextUnit = 44.sp, showsTagline: Boolean = true) {
    Column(
        modifier.clearAndSetSemantics { contentDescription = "RideAX. Your ride, our priority." },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = RideColors.TextPrimary)) { append("Ride") }
                withStyle(SpanStyle(color = RideColors.Accent)) { append("AX") }
            },
            fontSize = fontSize,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.5).sp,
        )
        if (showsTagline) {
            Text(
                text = "YOUR RIDE, OUR PRIORITY",
                fontSize = (fontSize.value * 0.24f).coerceAtLeast(10f).sp,
                letterSpacing = (fontSize.value * 0.06f).sp,
                fontWeight = FontWeight.Medium,
                color = RideColors.TextSecondary,
            )
        }
    }
}

/** Navy wash with a soft orange highway sweep — used behind the logo. */
@Composable
fun BrandBackdrop(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(RideColors.Canvas, RideColors.CanvasDeep)))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width * 0.75f, size.height * 0.18f)
            val radius = 320.dp.toPx()
            drawCircle(
                brush = Brush.radialGradient(listOf(RideColors.Accent.copy(alpha = 0.22f), Color.Transparent), center, radius),
                radius = radius,
                center = center,
            )
        }
        Canvas(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 90.dp, y = (-40).dp)
                .size(380.dp, 420.dp)
                .blur(26.dp)
        ) {
            drawPath(
                roadCurve(size),
                Brush.linearGradient(
                    listOf(RideColors.Accent.copy(alpha = 0f), RideColors.Accent.copy(alpha = 0.35f)),
                    start = Offset(0f, size.height),
                    end = Offset(size.width, 0f),
                ),
                style = Stroke(width = 46.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

/** Provider marks for the sign-in buttons. */
object BrandIcons {
    val Apple: ImageVector by lazy {
        ImageVector.Builder(name = "Apple", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = addPathNodes(APPLE_PATH), fill = SolidColor(Color.Black))
            .build()
    }

    val Google: ImageVector by lazy {
        ImageVector.Builder(name = "Google", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(
                pathData = addPathNodes(GOOGLE_PATH),
                fill = Brush.linearGradient(
                    listOf(Color(0xFF4285F4), Color(0xFFEA4335), Color(0xFFFBBC05), Color(0xFF34A853)),
                    start = Offset(0f, 0f),
                    end = Offset(24f, 24f),
                ),
            )
            .build()
    }

    private const val APPLE_PATH =
        "M12.152 6.896c-.948 0-2.415-1.078-3.96-1.04-2.04.027-3.91 1.183-4.961 3.014-2.117 3.675-.546 9.103 1.519 12.09 1.013 1.454 2.208 3.09 3.792 3.039 1.52-.065 2.09-.987 3.935-.987 1.831 0 2.35.987 3.96.948 1.637-.026 2.676-1.48 3.676-2.948 1.156-1.688 1.636-3.325 1.662-3.415-.039-.013-3.182-1.221-3.22-4.857-.026-3.04 2.48-4.494 2.597-4.559-1.429-2.09-3.623-2.324-4.39-2.376-2-.156-3.675 1.09-4.61 1.09zM15.53 3.83c.843-1.012 1.4-2.427 1.245-3.83-1.207.052-2.662.805-3.532 1.818-.78.896-1.454 2.338-1.273 3.714 1.338.104 2.715-.688 3.559-1.701"

    private const val GOOGLE_PATH =
        "M12.48 10.92v3.28h7.84c-.24 1.84-.853 3.187-1.787 4.133-1.147 1.147-2.933 2.4-6.053 2.4-4.827 0-8.6-3.893-8.6-8.72s3.773-8.72 8.6-8.72c2.6 0 4.507 1.027 5.907 2.347l2.307-2.307C18.747 1.44 16.133 0 12.48 0 5.867 0 .307 5.387.307 12s5.56 12 12.173 12c3.573 0 6.267-1.173 8.373-3.36 2.16-2.16 2.84-5.213 2.84-7.667 0-.76-.053-1.467-.173-2.053H12.48z"
}

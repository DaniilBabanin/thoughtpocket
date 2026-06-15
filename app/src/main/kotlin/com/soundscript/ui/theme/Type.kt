package com.soundscript.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.soundscript.R

/** "Reach" typography: Sora for UI/body, Instrument Serif for display/headline accents. */
val Sora = FontFamily(
    Font(R.font.sora_regular, FontWeight.Normal),
    Font(R.font.sora_medium, FontWeight.Medium),
    Font(R.font.sora_semibold, FontWeight.SemiBold),
    Font(R.font.sora_bold, FontWeight.Bold),
)

val InstrumentSerif = FontFamily(
    Font(R.font.instrument_serif_regular, FontWeight.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)

private val base = Typography()

/** Body/label/title use Sora; display & headline use Instrument Serif (the brand voice). */
val ReachTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = InstrumentSerif),
    displayMedium = base.displayMedium.copy(fontFamily = InstrumentSerif),
    displaySmall = base.displaySmall.copy(fontFamily = InstrumentSerif),
    headlineLarge = base.headlineLarge.copy(fontFamily = InstrumentSerif),
    headlineMedium = base.headlineMedium.copy(fontFamily = InstrumentSerif),
    headlineSmall = base.headlineSmall.copy(fontFamily = InstrumentSerif),
    titleLarge = base.titleLarge.copy(fontFamily = Sora, fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontFamily = Sora, fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontFamily = Sora, fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(fontFamily = Sora),
    bodyMedium = base.bodyMedium.copy(fontFamily = Sora),
    bodySmall = base.bodySmall.copy(fontFamily = Sora),
    labelLarge = base.labelLarge.copy(fontFamily = Sora, fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontFamily = Sora),
    labelSmall = base.labelSmall.copy(fontFamily = Sora),
)

/** Italic serif greeting on the home header ("good evening"). */
val GreetingStyle = TextStyle(
    fontFamily = InstrumentSerif,
    fontStyle = FontStyle.Italic,
    fontSize = 15.sp,
)

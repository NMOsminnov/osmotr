package kg.osmotr.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import kg.osmotr.ui.res.Res
import kg.osmotr.ui.res.golos_400
import kg.osmotr.ui.res.golos_500
import kg.osmotr.ui.res.golos_600
import kg.osmotr.ui.res.golos_700
import org.jetbrains.compose.resources.Font

/**
 * Вид приложения: Golos Text (нарисован для кириллицы — «д», «з», «ы» не выдают латинскую
 * гарнитуру; тот же выбор, что у APCS), светлая и тёмная тема — как в системе: на улице и в цеху
 * при солнце тёмная читается плохо. Шрифт — свой, в приложении (интернет на выездах слабый).
 */
@Composable
fun OsmotrTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val golos = FontFamily(
        Font(Res.font.golos_400, FontWeight.Normal), Font(Res.font.golos_500, FontWeight.Medium),
        Font(Res.font.golos_600, FontWeight.SemiBold), Font(Res.font.golos_700, FontWeight.Bold),
    )
    val base = Typography()
    fun TextStyle.g() = copy(fontFamily = golos)
    val type = Typography(
        displayLarge = base.displayLarge.g(), displayMedium = base.displayMedium.g(), displaySmall = base.displaySmall.g(),
        headlineLarge = base.headlineLarge.g(), headlineMedium = base.headlineMedium.g(),
        headlineSmall = base.headlineSmall.g().copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.g().copy(fontWeight = FontWeight.SemiBold), titleMedium = base.titleMedium.g().copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.g(), bodyLarge = base.bodyLarge.g(), bodyMedium = base.bodyMedium.g(), bodySmall = base.bodySmall.g(),
        labelLarge = base.labelLarge.g().copy(fontWeight = FontWeight.SemiBold), labelMedium = base.labelMedium.g(), labelSmall = base.labelSmall.g(),
    )
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFF4C8DF6), onPrimary = Color.White, primaryContainer = Color(0xFF1B3A66), onPrimaryContainer = Color(0xFFD6E4FF),
        secondary = Color(0xFFFFC857), onSecondary = Color(0xFF2B1D00),
        background = Color(0xFF0D1117), onBackground = Color(0xFFE6EDF3),
        surface = Color(0xFF161B22), onSurface = Color(0xFFE6EDF3),
        surfaceVariant = Color(0xFF21262D), onSurfaceVariant = Color(0xFF9DA7B3),
        surfaceContainer = Color(0xFF161B22), surfaceContainerHigh = Color(0xFF1C2128), surfaceContainerHighest = Color(0xFF262C34),
        secondaryContainer = Color(0xFF2D333B), onSecondaryContainer = Color(0xFFE6EDF3),
        outline = Color(0xFF3D444D), outlineVariant = Color(0xFF2D333B), error = Color(0xFFF85149),
    ) else lightColorScheme(
        primary = Color(0xFF1F6FEB), onPrimary = Color.White, primaryContainer = Color(0xFFDDEBFF), onPrimaryContainer = Color(0xFF0A3069),
        secondary = Color(0xFF8A5A00), onSecondary = Color.White,
        background = Color(0xFFF4F6F8), onBackground = Color(0xFF1F2328),
        surface = Color.White, onSurface = Color(0xFF1F2328),
        surfaceVariant = Color(0xFFEAEEF2), onSurfaceVariant = Color(0xFF59636E),
        surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFF6F8FA), surfaceContainerHighest = Color(0xFFEAEEF2),
        secondaryContainer = Color(0xFFE6EBF1), onSecondaryContainer = Color(0xFF1F2328),
        outline = Color(0xFFD0D7DE), outlineVariant = Color(0xFFE1E6EB), error = Color(0xFFCF222E),
    )
    // «Осмотрено» и «нерабочее» — свои оттенки на каждую тему: текст не ниже 4,5:1 к фону.
    val tones = if (dark) Tones(done = Color(0xFF3FB950), broken = Color(0xFFF85149)) else Tones(done = Color(0xFF1A7F37), broken = Color(0xFFCF222E))
    CompositionLocalProvider(LocalTones provides tones) {
        MaterialTheme(colorScheme = colors, typography = type, content = content)
    }
}

class Tones(val done: Color, val broken: Color)
private val LocalTones = staticCompositionLocalOf { Tones(Color(0xFF3FB950), Color(0xFFF85149)) }

/** Зелёный «осмотрено» — один на всё приложение, под тему. */
val DONE_GREEN: Color @Composable @ReadOnlyComposable get() = LocalTones.current.done
/** Красный «нерабочее» — в описи, в папке предмета и на кнопках, под тему. */
val BROKEN_RED: Color @Composable @ReadOnlyComposable get() = LocalTones.current.broken

package com.pinotrouge.messaging.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Version 5 accent themes — derived from prototype RAMPS + TINTS
 * (`PinotPhone.dc.html`). Do not hand-write eighteen palettes; use [pinotColors].
 *
 * Launch-window colors.xml stays Pinot-only (draws before prefs).
 */
enum class PinotThemeKey {
    Pinot,
    Bordeaux,
    Rose,
    Violet,
    Atlantic,
    Laurel,
    Amber,
    Tangerine,
    Grigio,
    Blanc,
    ;

    val storageKey: String
        get() = when (this) {
            Pinot -> "pinot"
            Bordeaux -> "bordeaux"
            Rose -> "rose"
            Violet -> "violet"
            Atlantic -> "atlantic"
            Laurel -> "laurel"
            Amber -> "amber"
            Tangerine -> "tangerine"
            Grigio -> "grigio"
            Blanc -> "blanc"
        }

    companion object {
        fun fromStorageKey(raw: String?): PinotThemeKey {
            val k = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.storageKey == k } ?: Pinot
        }

        /**
         * Themes offered in Settings — Version 5 `accentOptions` order.
         * [Bordeaux] stays on the enum and in the ramps so a persisted key still
         * resolves, but it is never listed here (near-duplicate of [Pinot]).
         */
        val offeredAccents: List<PinotThemeKey> = listOf(
            Pinot,
            Rose,
            Violet,
            Atlantic,
            Laurel,
            Amber,
            Tangerine,
            Grigio,
            Blanc,
        )
    }
}

internal data class AccentRamp(
    val steps: List<Color>, // 9 steps, 0 lightest → 8 darkest
) {
    operator fun get(i: Int): Color = steps[i]
}

/**
 * Per-mode neutrals and optional overrides. Explicit [accent] / [ink] / [outBub]
 * / ramp steps let grigio, blanc and rose light diverge from pure ramp derivation.
 */
internal data class ThemeTints(
    val bg: Color,
    val surface: Color,
    val text: Color,
    val dim: Color,
    val dimmer: Color,
    val divider: Color,
    val onAccent: Color,
    val scrim: Color,
    val track: Color,
    val trackB: Color,
    val knob: Color,
    val inBub: Color,
    val n900: Color,
    val n800: Color,
    val n700: Color,
    val n100: Color,
    val outText: Color? = null,
    val outBub: Color? = null,
    val accent: Color? = null,
    val ink: Color? = null,
    val a900: Color? = null,
    val a800: Color? = null,
    val a300: Color? = null,
    val a200: Color? = null,
    val a100: Color? = null,
)

private val Ramps: Map<PinotThemeKey, AccentRamp> = mapOf(
    PinotThemeKey.Pinot to AccentRamp(listOf(
        Color(0xFFFDF0F2), Color(0xFFF8D9DE), Color(0xFFE9A8B4), Color(0xFFD4798C), Color(0xFFB8506A), Color(0xFF9A3B53), Color(0xFF7D2D42), Color(0xFF5C2131), Color(0xFF3A1520),
    )),
    PinotThemeKey.Bordeaux to AccentRamp(listOf(
        Color(0xFFFCF0F3), Color(0xFFF6D8E0), Color(0xFFDFA3B4), Color(0xFFC4738A), Color(0xFFA44A64), Color(0xFF87374E), Color(0xFF6D2B3E), Color(0xFF4F1F2D), Color(0xFF34141E),
    )),
    PinotThemeKey.Rose to AccentRamp(listOf(
        Color(0xFFFEF3F1), Color(0xFFFBE2DF), Color(0xFFF4C2BD), Color(0xFFE79F9C), Color(0xFFD2777A), Color(0xFFB85F64), Color(0xFF984C52), Color(0xFF71383D), Color(0xFF4A2427),
    )),
    PinotThemeKey.Violet to AccentRamp(listOf(
        Color(0xFFF7EEFD), Color(0xFFEDDAF9), Color(0xFFDBB7F0), Color(0xFFC491E2), Color(0xFFA86BC8), Color(0xFF8D54A8), Color(0xFF714388), Color(0xFF533164), Color(0xFF361F41),
    )),
    PinotThemeKey.Atlantic to AccentRamp(listOf(
        Color(0xFFEEF1FC), Color(0xFFD9DFF7), Color(0xFFB0BBEA), Color(0xFF8492D5), Color(0xFF5A68B8), Color(0xFF46539C), Color(0xFF37417E), Color(0xFF28305C), Color(0xFF1A1F3B),
    )),
    PinotThemeKey.Laurel to AccentRamp(listOf(
        Color(0xFFEDF6EF), Color(0xFFD5EBD9), Color(0xFFA4D1AD), Color(0xFF71B382), Color(0xFF4A9160), Color(0xFF38754C), Color(0xFF2C5D3D), Color(0xFF20452D), Color(0xFF142C1D),
    )),
    PinotThemeKey.Amber to AccentRamp(listOf(
        Color(0xFFFDF8E6), Color(0xFFF8EEC0), Color(0xFFEDDC85), Color(0xFFD9C14C), Color(0xFFB89F27), Color(0xFF96811F), Color(0xFF786719), Color(0xFF584C12), Color(0xFF39310B),
    )),
    PinotThemeKey.Tangerine to AccentRamp(listOf(
        Color(0xFFFDF0E9), Color(0xFFFADCCA), Color(0xFFF2B392), Color(0xFFE58A58), Color(0xFFD0632C), Color(0xFFAB4C22), Color(0xFF893D1C), Color(0xFF652D15), Color(0xFF411C0D),
    )),
    PinotThemeKey.Grigio to AccentRamp(listOf(
        Color(0xFFF7F8FA), Color(0xFFEAECF0), Color(0xFFD0D4DB), Color(0xFFABB1BB), Color(0xFF7D848F), Color(0xFF5A606A), Color(0xFF41464D), Color(0xFF292D33), Color(0xFF131519),
    )),
    PinotThemeKey.Blanc to AccentRamp(listOf(
        Color(0xFFF7F7F7), Color(0xFFEBEBEB), Color(0xFFCFCFCF), Color(0xFFA8A8A8), Color(0xFF7A7A7A), Color(0xFF565656), Color(0xFF3E3E3E), Color(0xFF292929), Color(0xFF151515),
    )),
)

private val Tints: Map<PinotThemeKey, Pair<ThemeTints, ThemeTints>> = mapOf(
    PinotThemeKey.Pinot to (
        /* dark */ ThemeTints(
            bg = Color(0xFF17141C),
            surface = Color(0xFF221E29),
            text = Color(0xFFEFEAEE),
            dim = Color(0xFFC0B7C0),
            dimmer = Color(0xFF9A919B),
            divider = Color(0xFF332C39),
            onAccent = Color(0xFF241019),
            scrim = Color(0xB80A070D),
            track = Color(0xFF2B2531),
            trackB = Color(0xFF443B4A),
            knob = Color(0xFF7D7381),
            inBub = Color(0xFF262029),
            n900 = Color(0xFF221E29),
            n800 = Color(0xFF332C39),
            n700 = Color(0xFF4D444F),
            n100 = Color(0xFFEFEAEE),
        ) to
        /* light */ ThemeTints(
            bg = Color(0xFFFAF7F8),
            surface = Color(0xFFF1EAED),
            text = Color(0xFF241F26),
            dim = Color(0xFF5C535C),
            dimmer = Color(0xFF6F656F),
            divider = Color(0xFFDED3D8),
            onAccent = Color(0xFFFDF0F2),
            scrim = Color(0x80241F26),
            track = Color(0xFFE0D5DA),
            trackB = Color(0xFFC2B4BB),
            knob = Color(0xFF9C8F97),
            inBub = Color(0xFFF1EAED),
            n900 = Color(0xFFF1EAED),
            n800 = Color(0xFFE6DBE0),
            n700 = Color(0xFFC2B4BB),
            n100 = Color(0xFF241F26),
            outText = Color(0xFF4A1526),
            a900 = Color(0xFFFBEEF1),
            a200 = Color(0xFF6D2438),
            a100 = Color(0xFF4A1526),
        )
    ),
    PinotThemeKey.Bordeaux to (
        /* dark */ ThemeTints(
            bg = Color(0xFF17141C),
            surface = Color(0xFF221E29),
            text = Color(0xFFEFEAEE),
            dim = Color(0xFFC0B7C0),
            dimmer = Color(0xFF9A919B),
            divider = Color(0xFF332C39),
            onAccent = Color(0xFF241019),
            scrim = Color(0xB80A070D),
            track = Color(0xFF2B2531),
            trackB = Color(0xFF443B4A),
            knob = Color(0xFF7D7381),
            inBub = Color(0xFF262029),
            n900 = Color(0xFF221E29),
            n800 = Color(0xFF332C39),
            n700 = Color(0xFF4D444F),
            n100 = Color(0xFFEFEAEE),
        ) to
        /* light */ ThemeTints(
            bg = Color(0xFFFAF7F8),
            surface = Color(0xFFF1EAED),
            text = Color(0xFF241F26),
            dim = Color(0xFF5C535C),
            dimmer = Color(0xFF6F656F),
            divider = Color(0xFFDED3D8),
            onAccent = Color(0xFFFDF0F2),
            scrim = Color(0x80241F26),
            track = Color(0xFFE0D5DA),
            trackB = Color(0xFFC2B4BB),
            knob = Color(0xFF9C8F97),
            inBub = Color(0xFFF1EAED),
            n900 = Color(0xFFF1EAED),
            n800 = Color(0xFFE6DBE0),
            n700 = Color(0xFFC2B4BB),
            n100 = Color(0xFF241F26),
            outText = Color(0xFF4A1526),
            a900 = Color(0xFFFBEEF1),
            a200 = Color(0xFF6D2438),
            a100 = Color(0xFF4A1526),
        )
    ),
    PinotThemeKey.Rose to (
        /* dark */ ThemeTints(
            bg = Color(0xFF191212),
            surface = Color(0xFF241A1A),
            text = Color(0xFFF4E9E8),
            dim = Color(0xFFC8B5B4),
            dimmer = Color(0xFFA08E8D),
            divider = Color(0xFF382625),
            onAccent = Color(0xFF2B1214),
            scrim = Color(0xB80D0808),
            track = Color(0xFF2F2221),
            trackB = Color(0xFF4A3634),
            knob = Color(0xFF84706E),
            inBub = Color(0xFF281E1D),
            n900 = Color(0xFF241A1A),
            n800 = Color(0xFF382625),
            n700 = Color(0xFF523D3B),
            n100 = Color(0xFFF4E9E8),
        ) to
        /* light */ ThemeTints(
            bg = Color(0xFFFFFBFA),
            surface = Color(0xFFFBF0EE),
            text = Color(0xFF2A1E1E),
            dim = Color(0xFF5F504F),
            dimmer = Color(0xFF726261),
            divider = Color(0xFFEFDCD9),
            onAccent = Color(0xFFFEF3F1),
            scrim = Color(0x802A1E1E),
            track = Color(0xFFF0DEDB),
            trackB = Color(0xFFD5B8B5),
            knob = Color(0xFFA58E8C),
            inBub = Color(0xFFF7EAE8),
            n900 = Color(0xFFFBF0EE),
            n800 = Color(0xFFF4E3E1),
            n700 = Color(0xFFD5B8B5),
            n100 = Color(0xFF2A1E1E),
            outText = Color(0xFF4A2427),
            outBub = Color(0xFFE7B8B4),
            accent = Color(0xFFC2646C),
            ink = Color(0xFF9C4A45),
            a900 = Color(0xFFFDF1F0),
            a200 = Color(0xFF984C52),
            a100 = Color(0xFF4A2427),
        )
    ),
    PinotThemeKey.Violet to (
        /* dark */ ThemeTints(
            bg = Color(0xFF18131F),
            surface = Color(0xFF231B2B),
            text = Color(0xFFF0E9F4),
            dim = Color(0xFFC4B6CC),
            dimmer = Color(0xFF9E91A7),
            divider = Color(0xFF33283D),
            onAccent = Color(0xFF1E1029),
            scrim = Color(0xB80B070F),
            track = Color(0xFF2B2135),
            trackB = Color(0xFF453750),
            knob = Color(0xFF82738A),
            inBub = Color(0xFF271E2E),
            n900 = Color(0xFF231B2B),
            n800 = Color(0xFF33283D),
            n700 = Color(0xFF4E4159),
            n100 = Color(0xFFF0E9F4),
        ) to
        /* light */ ThemeTints(
            bg = Color(0xFFFDF9FE),
            surface = Color(0xFFF5EDF8),
            text = Color(0xFF251F2B),
            dim = Color(0xFF5B5064),
            dimmer = Color(0xFF6D6276),
            divider = Color(0xFFE3D5EA),
            onAccent = Color(0xFFF7EEFD),
            scrim = Color(0x80251F2B),
            track = Color(0xFFE5D8EC),
            trackB = Color(0xFFC8B6D2),
            knob = Color(0xFF9F92A8),
            inBub = Color(0xFFF5EDF8),
            n900 = Color(0xFFF5EDF8),
            n800 = Color(0xFFECE0F1),
            n700 = Color(0xFFC8B6D2),
            n100 = Color(0xFF251F2B),
            outText = Color(0xFF361F41),
            a900 = Color(0xFFF9F0FD),
            a200 = Color(0xFF714388),
            a100 = Color(0xFF361F41),
        )
    ),
    PinotThemeKey.Atlantic to (
        /* dark */ ThemeTints(
            bg = Color(0xFF12141F),
            surface = Color(0xFF1C1F2B),
            text = Color(0xFFE9EBF3),
            dim = Color(0xFFB6BACB),
            dimmer = Color(0xFF9195A6),
            divider = Color(0xFF2A2E3D),
            onAccent = Color(0xFF101527),
            scrim = Color(0xB807080E),
            track = Color(0xFF232634),
            trackB = Color(0xFF393E50),
            knob = Color(0xFF757A89),
            inBub = Color(0xFF1E212D),
            n900 = Color(0xFF1C1F2B),
            n800 = Color(0xFF2A2E3D),
            n700 = Color(0xFF454A59),
            n100 = Color(0xFFE9EBF3),
        ) to
        /* light */ ThemeTints(
            bg = Color(0xFFF8F9FD),
            surface = Color(0xFFEBEDF7),
            text = Color(0xFF1F2130),
            dim = Color(0xFF535668),
            dimmer = Color(0xFF65687A),
            divider = Color(0xFFD5D9EA),
            onAccent = Color(0xFFEEF1FC),
            scrim = Color(0x801F2130),
            track = Color(0xFFDCDFEE),
            trackB = Color(0xFFBCC1D6),
            knob = Color(0xFF9498AC),
            inBub = Color(0xFFEBEDF7),
            n900 = Color(0xFFEBEDF7),
            n800 = Color(0xFFDFE2F0),
            n700 = Color(0xFFBCC1D6),
            n100 = Color(0xFF1F2130),
            outText = Color(0xFF1A1F3B),
            a900 = Color(0xFFF0F2FD),
            a200 = Color(0xFF37417E),
            a100 = Color(0xFF1A1F3B),
        )
    ),
    PinotThemeKey.Laurel to (
        /* dark */ ThemeTints(
            bg = Color(0xFF131A16),
            surface = Color(0xFF1D251F),
            text = Color(0xFFE9F0EA),
            dim = Color(0xFFB7C4BA),
            dimmer = Color(0xFF929F95),
            divider = Color(0xFF2B352E),
            onAccent = Color(0xFF0D1C12),
            scrim = Color(0xB8070D09),
            track = Color(0xFF242D27),
            trackB = Color(0xFF3A463D),
            knob = Color(0xFF768175),
            inBub = Color(0xFF1F2822),
            n900 = Color(0xFF1D251F),
            n800 = Color(0xFF2B352E),
            n700 = Color(0xFF45524A),
            n100 = Color(0xFFE9F0EA),
        ) to
        /* light */ ThemeTints(
            bg = Color(0xFFF7FBF8),
            surface = Color(0xFFEAF3EC),
            text = Color(0xFF1F2A22),
            dim = Color(0xFF4F5F55),
            dimmer = Color(0xFF626F66),
            divider = Color(0xFFD3E2D8),
            onAccent = Color(0xFFEDF6EF),
            scrim = Color(0x801F2A22),
            track = Color(0xFFD5E5DA),
            trackB = Color(0xFFB4C7BA),
            knob = Color(0xFF8FA094),
            inBub = Color(0xFFEAF3EC),
            n900 = Color(0xFFEAF3EC),
            n800 = Color(0xFFDBEADE),
            n700 = Color(0xFFB4C7BA),
            n100 = Color(0xFF1F2A22),
            outText = Color(0xFF142C1D),
            a900 = Color(0xFFEEF7F0),
            a200 = Color(0xFF2B5C3A),
            a100 = Color(0xFF142C1D),
        )
    ),
    PinotThemeKey.Amber to (
        /* dark */ ThemeTints(
            bg = Color(0xFF191810),
            surface = Color(0xFF242218),
            text = Color(0xFFF1EFE1),
            dim = Color(0xFFC3C0A8),
            dimmer = Color(0xFF9C9985),
            divider = Color(0xFF363320),
            onAccent = Color(0xFF221F06),
            scrim = Color(0xB80C0B05),
            track = Color(0xFF2D2A18),
            trackB = Color(0xFF48442F),
            knob = Color(0xFF817E65),
            inBub = Color(0xFF282619),
            n900 = Color(0xFF242218),
            n800 = Color(0xFF363320),
            n700 = Color(0xFF514D36),
            n100 = Color(0xFFF1EFE1),
        ) to
        /* light */ ThemeTints(
            bg = Color(0xFFFBFAF3),
            surface = Color(0xFFF2F0DF),
            text = Color(0xFF25241A),
            dim = Color(0xFF5A5843),
            dimmer = Color(0xFF6D6B55),
            divider = Color(0xFFDFDCC3),
            onAccent = Color(0xFFFDF8E6),
            scrim = Color(0x8025241A),
            track = Color(0xFFE1DEC5),
            trackB = Color(0xFFC2BFA1),
            knob = Color(0xFF9A9880),
            inBub = Color(0xFFF2F0DF),
            n900 = Color(0xFFF2F0DF),
            n800 = Color(0xFFE7E4CC),
            n700 = Color(0xFFC2BFA1),
            n100 = Color(0xFF25241A),
            outText = Color(0xFF39310B),
            ink = Color(0xFF6D5C16),
            a900 = Color(0xFFFAF7E4),
            a200 = Color(0xFF786719),
            a100 = Color(0xFF39310B),
        )
    ),
    PinotThemeKey.Tangerine to (
        /* dark */ ThemeTints(
            bg = Color(0xFF1B1410),
            surface = Color(0xFF261D17),
            text = Color(0xFFF2E9E3),
            dim = Color(0xFFC5B7AD),
            dimmer = Color(0xFF9D9087),
            divider = Color(0xFF382B22),
            onAccent = Color(0xFF2B1206),
            scrim = Color(0xB80E0905),
            track = Color(0xFF2F251E),
            trackB = Color(0xFF4A3B30),
            knob = Color(0xFF837569),
            inBub = Color(0xFF29211A),
            n900 = Color(0xFF261D17),
            n800 = Color(0xFF382B22),
            n700 = Color(0xFF524338),
            n100 = Color(0xFFF2E9E3),
        ) to
        /* light */ ThemeTints(
            bg = Color(0xFFFDF8F4),
            surface = Color(0xFFF5EAE2),
            text = Color(0xFF271E18),
            dim = Color(0xFF5F5346),
            dimmer = Color(0xFF716658),
            divider = Color(0xFFE5D6CB),
            onAccent = Color(0xFFFDF0E9),
            scrim = Color(0x80271E18),
            track = Color(0xFFE7D9CE),
            trackB = Color(0xFFC9BAAB),
            knob = Color(0xFF9E9285),
            inBub = Color(0xFFF5EAE2),
            n900 = Color(0xFFF5EAE2),
            n800 = Color(0xFFECDFD5),
            n700 = Color(0xFFC9BAAB),
            n100 = Color(0xFF271E18),
            outText = Color(0xFF411C0D),
            a900 = Color(0xFFFDF1EA),
            a200 = Color(0xFF893D1C),
            a100 = Color(0xFF411C0D),
        )
    ),
    PinotThemeKey.Grigio to (
        /* dark */ ThemeTints(
            bg = Color(0xFF101216),
            surface = Color(0xFF1C1F24),
            text = Color(0xFFF2F4F7),
            dim = Color(0xFFBCC2CA),
            dimmer = Color(0xFF979DA5),
            divider = Color(0xFF2F333A),
            onAccent = Color(0xFF131519),
            scrim = Color(0xBD060709),
            track = Color(0xFF25292F),
            trackB = Color(0xFF41464E),
            knob = Color(0xFF7B818B),
            inBub = Color(0xFF23272D),
            n900 = Color(0xFF1C1F24),
            n800 = Color(0xFF2F333A),
            n700 = Color(0xFF4A4F57),
            n100 = Color(0xFFF2F4F7),
            outText = Color(0xFF15171A),
            outBub = Color(0xFFEEF0F4),
            accent = Color(0xFFE3E7EC),
            a900 = Color(0xFF1C1F24),
            a800 = Color(0xFF2F333A),
            a300 = Color(0xFFD0D4DB),
            a200 = Color(0xFFEAECF0),
            a100 = Color(0xFFF7F8FA),
        ) to
        /* light */ ThemeTints(
            bg = Color(0xFFEDEFF3),
            surface = Color(0xFFFDFDFE),
            text = Color(0xFF131519),
            dim = Color(0xFF535962),
            dimmer = Color(0xFF666C75),
            divider = Color(0xFFDADDE3),
            onAccent = Color(0xFFF7F8FA),
            scrim = Color(0x80131519),
            track = Color(0xFFDADDE3),
            trackB = Color(0xFFB6BCC5),
            knob = Color(0xFF8F959E),
            inBub = Color(0xFFFDFDFE),
            n900 = Color(0xFFFDFDFE),
            n800 = Color(0xFFE4E7EC),
            n700 = Color(0xFFB6BCC5),
            n100 = Color(0xFF131519),
            outText = Color(0xFFF2F4F7),
            outBub = Color(0xFF1B1E23),
            accent = Color(0xFF2B2F36),
            a900 = Color(0xFFFDFDFE),
            a800 = Color(0xFFE4E7EC),
            a300 = Color(0xFF41464E),
            a200 = Color(0xFF2B2F36),
            a100 = Color(0xFF131519),
        )
    ),
    PinotThemeKey.Blanc to (
        /* dark */ ThemeTints(
            bg = Color(0xFF141414),
            surface = Color(0xFF1F1F1F),
            text = Color(0xFFF0F0F0),
            dim = Color(0xFFBDBDBD),
            dimmer = Color(0xFF9A9A9A),
            divider = Color(0xFF333333),
            onAccent = Color(0xFF0A2F6B),
            scrim = Color(0xB8080808),
            track = Color(0xFF292929),
            trackB = Color(0xFF474747),
            knob = Color(0xFF7D7D7D),
            inBub = Color(0xFF242424),
            n900 = Color(0xFF1F1F1F),
            n800 = Color(0xFF333333),
            n700 = Color(0xFF4D4D4D),
            n100 = Color(0xFFF0F0F0),
            outText = Color(0xFFF7F7F7),
            outBub = Color(0xFF3F3F3F),
            accent = Color(0xFFA8C7FA),
            a900 = Color(0xFF1F1F1F),
            a800 = Color(0xFF333333),
            a300 = Color(0xFFA8C7FA),
            a200 = Color(0xFFDCDCDC),
            a100 = Color(0xFFF0F0F0),
        ) to
        /* light */ ThemeTints(
            bg = Color(0xFFFCFCFC),
            surface = Color(0xFFF2F2F2),
            text = Color(0xFF1C1C1C),
            dim = Color(0xFF565656),
            dimmer = Color(0xFF6A6A6A),
            divider = Color(0xFFD8D8D8),
            onAccent = Color(0xFFF7FBFF),
            scrim = Color(0x801C1C1C),
            track = Color(0xFFDCDCDC),
            trackB = Color(0xFFBBBBBB),
            knob = Color(0xFF949494),
            inBub = Color(0xFFECECEC),
            n900 = Color(0xFFF2F2F2),
            n800 = Color(0xFFE6E6E6),
            n700 = Color(0xFFBBBBBB),
            n100 = Color(0xFF1C1C1C),
            outText = Color(0xFFF4F4F4),
            outBub = Color(0xFF2B2B2B),
            accent = Color(0xFF0B57D0),
            a900 = Color(0xFFF2F2F2),
            a800 = Color(0xFFE4E4E4),
            a300 = Color(0xFF0B57D0),
            a200 = Color(0xFF3E3E3E),
            a100 = Color(0xFF1C1C1C),
        )
    ),
)

/**
 * Build [PinotColors] the way the Version 5 prototype's `palette()` does:
 * ramp indices differ by mode, then merge that theme's tint object (tint wins
 * for explicit `accent`, `ink`, `outBub`, `outText`, and ramp steps).
 *
 * Unknown / missing keys fall back to [PinotThemeKey.Pinot] (prototype `themeKey()`).
 */
fun pinotColors(
    theme: PinotThemeKey = PinotThemeKey.Pinot,
    dark: Boolean,
): PinotColors {
    val key = if (theme in Ramps) theme else PinotThemeKey.Pinot
    val r = Ramps.getValue(key)
    val pair = Tints.getValue(key)
    val t = if (dark) pair.first else pair.second
    val accent: Color
    val accent900: Color
    val accent800: Color
    val accent300: Color
    val accent200: Color
    val accent100: Color
    val outgoingBubble: Color
    val outgoingBubbleText: Color
    if (dark) {
        // defaults: accent r[3], outBub r[7], outText r[0], a900 r[8], a800 r[7], a300 r[2], a200 r[1], a100 r[0]
        accent = t.accent ?: r[3]
        accent900 = t.a900 ?: r[8]
        accent800 = t.a800 ?: r[7]
        accent300 = t.a300 ?: r[2]
        accent200 = t.a200 ?: r[1]
        accent100 = t.a100 ?: r[0]
        outgoingBubble = t.outBub ?: r[7]
        outgoingBubbleText = t.outText ?: r[0]
    } else {
        // defaults: accent r[5], outBub r[1], a800 r[1], a300 r[6]; a900/a200/a100/outText from tints
        accent = t.accent ?: r[5]
        accent900 = t.a900 ?: r[8]
        accent800 = t.a800 ?: r[1]
        accent300 = t.a300 ?: r[6]
        accent200 = t.a200 ?: r[7]
        accent100 = t.a100 ?: r[8]
        outgoingBubble = t.outBub ?: r[1]
        outgoingBubbleText = t.outText ?: r[8]
    }
    val ink = t.ink ?: accent
    return PinotColors(
        isDark = dark,
        bg = t.bg,
        surface = t.surface,
        text = t.text,
        dim = t.dim,
        dimmer = t.dimmer,
        divider = t.divider,
        accent = accent,
        ink = ink,
        onAccent = t.onAccent,
        accent900 = accent900,
        accent800 = accent800,
        accent300 = accent300,
        accent200 = accent200,
        accent100 = accent100,
        neutral900 = t.n900,
        neutral800 = t.n800,
        neutral700 = t.n700,
        neutral100 = t.n100,
        switchTrackOff = t.track,
        switchTrackBorderOff = t.trackB,
        switchKnobOff = t.knob,
        switchTrackOn = accent.copy(alpha = 0.38f),
        switchTrackBorderOn = accent,
        switchKnobOn = accent,
        incomingBubble = t.inBub,
        outgoingBubble = outgoingBubble,
        outgoingBubbleText = outgoingBubbleText,
        shadowSmBorder = t.n800,
        pressedOverlay = t.text.copy(alpha = 0.08f),
        selection = accent.copy(alpha = 0.13f),
        scrim = t.scrim,
    )
}

/** Back-compat wrappers — identical to [pinotColors] with [PinotThemeKey.Pinot]. */
fun pinotColorsDark(): PinotColors = pinotColors(PinotThemeKey.Pinot, dark = true)

fun pinotColorsLight(): PinotColors = pinotColors(PinotThemeKey.Pinot, dark = false)

/**
 * Swatch fill colour for the Settings picker — overrides for rose / grigio / blanc,
 * otherwise ramp step 5 (light) / 3 (dark). Selected ring and 14% selected bg use this.
 */
fun PinotThemeKey.swatchColor(dark: Boolean): Color {
    val override = when (this) {
        PinotThemeKey.Rose -> if (dark) Color(0xFFE79F9C) else Color(0xFFC2646C)
        PinotThemeKey.Grigio -> if (dark) Color(0xFFE3E7EC) else Color(0xFF2B2F36)
        PinotThemeKey.Blanc -> if (dark) Color(0xFFA8C7FA) else Color(0xFF0B57D0)
        else -> null
    }
    if (override != null) return override
    val r = Ramps.getValue(this)
    return if (dark) r[3] else r[5]
}

/**
 * Two-tone split for grigio / blanc swatches (135°). Null for solid dots.
 * Pair is (start, end) of the gradient.
 */
fun PinotThemeKey.swatchSplit(dark: Boolean): Pair<Color, Color>? = when (this) {
    PinotThemeKey.Grigio ->
        if (dark) Color(0xFFEEF0F4) to Color(0xFF23272D)
        else Color(0xFFFDFDFE) to Color(0xFF1B1E23)
    PinotThemeKey.Blanc ->
        if (dark) Color(0xFF333333) to Color(0xFFA8C7FA)
        else Color(0xFFEDEDED) to Color(0xFF0B57D0)
    else -> null
}

/** 1px ring so a near-white half stays visible; transparent for other swatches. */
fun PinotThemeKey.swatchDotRing(dark: Boolean): Color? = when (this) {
    PinotThemeKey.Grigio, PinotThemeKey.Blanc ->
        if (dark) Color(0xFF4D5158) else Color(0xFFC9CCD3)
    else -> null
}

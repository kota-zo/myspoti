package com.music.spotui.ui.immersive.pages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.myspoti.engine.R
import com.music.spotui.data.preferences.EqPreset
import com.music.spotui.data.preferences.EqualizerPref
import com.music.spotui.di.EqBands
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.look.ThinSlider
import com.music.spotui.ui.screens.dbText
import com.music.spotui.ui.screens.eqBandLabel
import com.music.spotui.ui.screens.eqPresetLabel
import com.music.spotui.ui.screens.speedText

/**
 * Settings > Playback > Equalizer (Immersive): the Classic page's settings (see EqualizerScreen)
 * in grouped cards; the preset opens its list as a page of its own.
 */
@Composable
fun ImmersiveEqualizerScreen(navController: NavController) {
    val ctx = LocalContext.current
    val pref = EqualizerPref
    var presets by rememberSaveable { mutableStateOf(false) }
    if (presets) {
        BackHandler { presets = false }
        ImmersivePage(title = stringResource(R.string.equalizer_preset), onBack = { presets = false }) {
            immersiveGroup("presets") {
                for (p in EqPreset.entries.filter { it != EqPreset.CUSTOM || pref.preset == EqPreset.CUSTOM }) row {
                    ImmersiveChoiceRow(stringResource(eqPresetLabel(p)), selected = pref.preset == p,
                        onClick = { pref.setPreset(ctx, p); presets = false })
                }
            }
        }
        return
    }
    ImmersivePage(title = stringResource(R.string.equalizer_title), onBack = { navController.popBackStack() }) {
        immersiveBlock("scope") {
            Text(stringResource(R.string.equalizer_scope), style = Immersive.Caption, textAlign = TextAlign.Start)
        }
        immersiveGroup("eq", footerRes = R.string.equalizer_enable_detail) {
            row { ImmersiveSwitchRow(stringResource(R.string.equalizer_enable), checked = pref.enabled, onChecked = { pref.setEnabled(ctx, it) }) }
            row {
                ImmersiveGroupRow(stringResource(R.string.equalizer_preset), value = stringResource(eqPresetLabel(pref.preset)),
                    chevron = true, enabled = pref.enabled, onClick = { presets = true })
            }
        }
        immersiveGroup("bands", headerRes = R.string.equalizer_bands, footerRes = R.string.equalizer_preamp_detail) {
            val gains = pref.gains
            for (band in 0 until EqBands.COUNT) row {
                DbRow(eqBandLabel(band), gains[band], EqBands.MIN_DB, EqBands.MAX_DB, pref.enabled,
                    onChange = { pref.setGain(ctx, band, it, save = false) }, onDone = { pref.save(ctx) })
            }
            row {
                DbRow(stringResource(R.string.equalizer_preamp), pref.preamp, EqBands.PREAMP_MIN_DB, EqBands.PREAMP_MAX_DB, pref.enabled,
                    onChange = { pref.setPreamp(ctx, it, save = false) }, onDone = { pref.save(ctx) })
            }
            row {
                ImmersiveGroupRow(stringResource(R.string.equalizer_reset), enabled = pref.enabled,
                    onClick = { pref.setPreset(ctx, EqPreset.FLAT) })
            }
        }
        immersiveGroup("speed", headerRes = R.string.equalizer_speed, footerRes = R.string.equalizer_speed_detail) {
            row {
                ImmersiveSegmentedControl(
                    options = EqualizerPref.SPEEDS.map(::speedText),
                    selectedIndex = EqualizerPref.SPEEDS.indexOf(pref.speed).coerceAtLeast(0),
                    onSelect = { pref.setSpeed(ctx, EqualizerPref.SPEEDS[it]) },
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        immersiveGroup("silences") {
            row {
                ImmersiveSwitchRow(stringResource(R.string.equalizer_skip_silence), checked = pref.skipSilence,
                    onChecked = { pref.setSkipSilence(ctx, it) }, detail = stringResource(R.string.equalizer_skip_silence_detail))
            }
        }
    }
}

/** One band (or the preamp): its name, the look's thin slider in 0.5 dB steps, its gain. */
@Composable
private fun DbRow(label: String, value: Float, min: Float, max: Float, enabled: Boolean, onChange: (Float) -> Unit, onDone: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp)) {
        Text(label, style = Immersive.Body.copy(color = if (enabled) Immersive.Text else Immersive.TextTertiary), modifier = Modifier.width(64.dp))
        ThinSlider(
            value = (value - min) / (max - min),
            onValueChange = { if (enabled) onChange(Math.round((min + it * (max - min)) * 2) / 2f) },
            onValueChangeFinished = onDone,
            modifier = Modifier.weight(1f),
            activeColor = if (enabled) Immersive.Accent else Immersive.TextTertiary,
        )
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.equalizer_db, dbText(value)), style = Immersive.Caption, modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
    }
}

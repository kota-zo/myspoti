package com.music.spotui.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.myspoti.engine.R
import com.music.spotui.data.preferences.SponsorBlockPref

/** The Podcasts group of the settings: SponsorBlock and the parts it skips (see [com.music.spotui.podcast.SponsorSkip]). */
@Composable
internal fun SponsorBlockSettings() {
    val ctx = LocalContext.current
    Section(R.string.settings_section_podcasts)
    OptionRow(
        label = stringResource(R.string.sponsorblock_title),
        detail = stringResource(R.string.sponsorblock_detail),
        checked = SponsorBlockPref.on,
        onChecked = { SponsorBlockPref.set(ctx, it) },
    )
    if (!SponsorBlockPref.on) return
    OptionRow(stringResource(R.string.sponsorblock_cat_sponsor), stringResource(R.string.sponsorblock_cat_sponsor_detail),
        SponsorBlockPref.sponsor, onChecked = { SponsorBlockPref.setSponsor(ctx, it) })
    OptionRow(stringResource(R.string.sponsorblock_cat_selfpromo), stringResource(R.string.sponsorblock_cat_selfpromo_detail),
        SponsorBlockPref.selfPromo, onChecked = { SponsorBlockPref.setSelfPromo(ctx, it) })
    OptionRow(stringResource(R.string.sponsorblock_cat_intro_outro), stringResource(R.string.sponsorblock_cat_intro_outro_detail),
        SponsorBlockPref.introOutro, onChecked = { SponsorBlockPref.setIntroOutro(ctx, it) })
    OptionRow(stringResource(R.string.sponsorblock_cat_interaction), stringResource(R.string.sponsorblock_cat_interaction_detail),
        SponsorBlockPref.interaction, onChecked = { SponsorBlockPref.setInteraction(ctx, it) })
}

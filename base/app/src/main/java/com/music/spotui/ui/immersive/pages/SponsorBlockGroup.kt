package com.music.spotui.ui.immersive.pages

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.myspoti.engine.R
import com.music.spotui.data.preferences.SponsorBlockPref

/** The Podcasts group of the Immersive settings: SponsorBlock and the parts it skips (see [com.music.spotui.podcast.SponsorSkip]). */
internal fun LazyListScope.sponsorBlockGroup() {
    immersiveGroup("podcasts", headerRes = R.string.settings_section_podcasts) {
        row {
            val ctx = LocalContext.current
            ImmersiveSwitchRow(
                stringResource(R.string.sponsorblock_title),
                checked = SponsorBlockPref.on,
                onChecked = { SponsorBlockPref.set(ctx, it) },
                detail = stringResource(R.string.sponsorblock_detail),
            )
        }
    }
    if (SponsorBlockPref.on) immersiveGroup("sponsorblock", headerRes = R.string.sponsorblock_skip_header) {
        row {
            val ctx = LocalContext.current
            ImmersiveSwitchRow(stringResource(R.string.sponsorblock_cat_sponsor), SponsorBlockPref.sponsor,
                onChecked = { SponsorBlockPref.setSponsor(ctx, it) }, detail = stringResource(R.string.sponsorblock_cat_sponsor_detail))
        }
        row {
            val ctx = LocalContext.current
            ImmersiveSwitchRow(stringResource(R.string.sponsorblock_cat_selfpromo), SponsorBlockPref.selfPromo,
                onChecked = { SponsorBlockPref.setSelfPromo(ctx, it) }, detail = stringResource(R.string.sponsorblock_cat_selfpromo_detail))
        }
        row {
            val ctx = LocalContext.current
            ImmersiveSwitchRow(stringResource(R.string.sponsorblock_cat_intro_outro), SponsorBlockPref.introOutro,
                onChecked = { SponsorBlockPref.setIntroOutro(ctx, it) }, detail = stringResource(R.string.sponsorblock_cat_intro_outro_detail))
        }
        row {
            val ctx = LocalContext.current
            ImmersiveSwitchRow(stringResource(R.string.sponsorblock_cat_interaction), SponsorBlockPref.interaction,
                onChecked = { SponsorBlockPref.setInteraction(ctx, it) }, detail = stringResource(R.string.sponsorblock_cat_interaction_detail))
        }
    }
}

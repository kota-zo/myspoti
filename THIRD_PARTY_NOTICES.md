# Third-party notices

Myspoti is free software under the GNU General Public License v3.0 (see `LICENSE`).
It builds on the projects below; their licences are compatible with the GPL-3.0.

## Spotui — GPL-3.0
https://github.com/Spotui/Spotui

The Kotlin lossless resolver, the DASH-to-FLAC remux and the first verification
screen (`base/app/src/main/java/com/music/spotui/lossless/`, `SpotiflacScreen.kt`)
are ported from Spotui and stay under the GPL-3.0. Each file says so in its header.

## SpotiFLAC — MIT
https://github.com/spotbye/SpotiFLAC

The lossless resolving approach and its community backend protocol.

> MIT License — Copyright (c) 2026 afkarxyz.
> Permission is hereby granted, free of charge, to any person obtaining a copy of this
> software and associated documentation files (the "Software"), to deal in the Software
> without restriction, including without limitation the rights to use, copy, modify,
> merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
> permit persons to whom the Software is furnished to do so, subject to the following
> conditions: The above copyright notice and this permission notice shall be included in
> all copies or substantial portions of the Software. THE SOFTWARE IS PROVIDED "AS IS",
> WITHOUT WARRANTY OF ANY KIND.

## EeveeSpotifyReincarnated — GPL-3.0
https://github.com/SideloadLabs/EeveeSpotifyReincarnated

The podcast sponsor skipper (`di/SponsorSkip.kt`, `data/api/SponsorBlock.kt`) follows its
SponsorBlock skipper: the hash-prefix query, the default categories and colours, the
cooldown between two skips, and letting a segment play after the listener seeks into it.

## SponsorBlock — data under CC BY-NC-SA 4.0
https://sponsor.ajay.app — https://github.com/ajayyy/SponsorBlockServer

The podcast segments come from the SponsorBlock database (the `Spotify` service of the
[Spot SponsorBlock](https://github.com/Spot-SponsorBlock/Spot-SponsorBlock-Extension)
extension), submitted by its users. Myspoti credits it in Settings and uses it free of charge.

## SpotiFLAC Mobile — MIT
https://github.com/zarzet/SpotiFLAC-Mobile

`ZarzSession.kt` implements the signed-session protocol of its extension runtime
(bootstrap, challenge, grant exchange, ZARZ-HMAC-V1 signatures, download tickets),
written in Kotlin from that runtime.

> MIT License — Copyright (c) 2026 zarzet. Same permission notice as above.

## SpotiFLAC Extensions — Apache-2.0
https://github.com/zarzet/SpotiFLAC-Extension

The request shapes of the `tidal-web` and `qobuz-web` extensions (download endpoints,
quality names, the Tidal web token and Qobuz app id they use) are followed in
`LosslessSource.kt`. No extension code is included.

> Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0

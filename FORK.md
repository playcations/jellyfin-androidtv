# playcations fork of jellyfin-androidtv

Changes here require **rebuilding and sideloading the app** (debug build installs as "Jellyfin Debug",
`org.jellyfin.androidtv.debug`, next to the official app). Features that only need a server plugin live in
github.com/playcations/jellyfin-plugins as `plugin/*` branches.

Branching: `dev` (integration, based on upstream `master`) ← `app/*` (one branch per app feature).

| Branch | What | Needs server plugin |
|---|---|---|
| `app/foryou-home-rows` | For You rows on the home screen (Recommended for You / Movies / Shows / New / Trending) | For You (`/ForYou/Sections`) |
| `app/recommended-sort` | "Recommended" in the Movies/Shows library Sort menu | For You (`/ForYou/Recommended`) |

Without the plugin both features hide themselves and the app behaves like upstream.

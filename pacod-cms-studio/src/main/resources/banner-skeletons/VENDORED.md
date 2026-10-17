# Built-in banner skeletons

`sidebar/` and `top/` are the two looks an HTML5 booking can be built from out of the box: a
fixed-size frame for the side column and a fluid one for the leaderboard slot above the content.
They are here so the console can build a banner folder rather than an operator copying one by hand.

`BannerSkeletonBootstrap` seeds both into the `bannerSkeleton` collection **while it is empty**.
Editing a file here after that collection exists changes nothing; edit the skeleton at
`/mgmt/banners/layouts/skeletons`.

## What a skeleton holds, and what it does not

| | |
|---|---|
| `index.html` | the frame only: `.banner-container-inline` holds a comment, because the console replaces that element's contents with the layout's slides when it generates a folder. |
| `banner.css` | the look, written as the folder's `css/banner.css`, byte for byte. See below. |
| `js/banner.js` | **not part of a skeleton.** The console writes `rotator.js` there in every generated folder — see `BannerSkeleton`'s javadoc. |
| images | none. Creatives belong to a campaign rather than to a look, and live in the layout's folder. |

## The top skeleton's CSS is broken, deliberately

`top/banner.css` reads:

```css
.banner-img {
  height: 100% !important
  width: 100%;
}
```

The missing semicolon after `!important` makes `width: 100%` part of the same declaration, so it is
dropped: the image is sized by **height alone, with its aspect ratio preserved**. That is exactly
what makes a 728x90 creative fill the 90px `.partner-card__frame--top` box without distortion. "Fixing"
the semicolon stretches every top banner on the site to the frame width.

Keep it byte-for-byte for that reason.

## Tag Manager

The built-in skeletons load no tag manager. An advertiser who measures through Google Tag Manager
supplies a skeleton that does: `BannerSkeletonParser` finds the container id at import, records it as
the skeleton's default and replaces every occurrence with a token, so a layout can be booked under a
different container. A skeleton that carries a GTM block requires an id on every layout built from
it; there is nothing sensible to write into the `gtm.js?id=` URL otherwise.

## Upgrading

There is nothing to upgrade — these are not a dependency. If the advertiser-facing markup changes,
edit the skeleton in the console. Editing the files here only affects a database that has never seen
a skeleton.

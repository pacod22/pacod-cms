# The space demo's advertisers

Five invented advertisers of the space demo ("Starward"): Aurelian Lines, Lumen Assurance,
Rimstone Lodge, Kestrel & Vane and Nightglass Tours. They and everything they offer are fictional;
each creative says "Advertisement", and each advertiser's page on the website (`/p/<slug>`, in the
seed's `pages`) says that it is invented.

Written by `StudioDemo` from the seed's `partners` (`demo/demo-space.json` in core):

- a folder here with HTML5 bookings becomes a banner layout of the advertiser's id, IMPORTED: the
  folder is packed into a ZIP in memory -- its files, and the demo photographs the seed names,
  cut to 1200x800 into `art/` -- and goes through `MgmtBannerLayoutService.importArchive`, so
  every rule of an uploaded archive applies;
- each booking is made through `MgmtBannerService` at the size, placement and pages the seed
  gives it (an imported layout has no dimensions, and `suggestedSize` would say TOP);
- `nightglass-tours/still.jpg` is an IMAGE booking's creative, uploaded through
  `uploadCreative`.

## The creatives

Hand-written HTML and CSS, an inline SVG mark each, nothing from another address: they link the
website's own faces (`/site-assets/fonts/fonts.css`, same origin) and their own copied photographs.
Every one is fluid -- a leaderboard is 90 high at any width (about 676 on the minimal set, over 1200
on editorial and magazine, a phone's width when the row stacks), a rectangle 250 high and a half
page 600 high at whatever width the column has -- and every animation stops under
`prefers-reduced-motion`. The whole creative is one link, `target="_blank"`, to the advertiser's
page; the website's `site.js` counts the click from inside the frame, which works because the frame
is same-origin and unsandboxed (see `fragments/ad-sidebar.html`).

## Names

Every folder id, file name, slug, class and id here, the URLs they make and the magazine set's own
classes were checked against EasyList, EasyPrivacy, Fanboy's Social list and Fanboy's Annoyance
list (simplified matcher: generic cosmetic filters, and network filters without `$domain` or
`$third-party`). Nothing matched. Check again before renaming anything: no `ad`, `banner`,
`sponsor`, `promo`, `social-`, `share-`, `follow-`, sizes such as `300x250`, or `house`.

## Re-rendering the still

`nightglass-tours/still.jpg` (600x500, shown at 300x250) is rendered from `still.html` beside it.
Serve a folder holding the site's `site-assets/fonts/` at `/site-assets/fonts/` and this folder
at `/resources/banners/nightglass-tours/`, with `art/butterfly.jpg` a copy of the demo's
`lantern-butterfly.jpg`, then:

```bash
chrome-headless-shell --headless --hide-scrollbars --virtual-time-budget=4000 \
    --window-size=600,500 --screenshot=still.png \
    http://localhost:8765/resources/banners/nightglass-tours/still.html
sips -s format jpeg -s formatOptions 86 still.png --out still.jpg
```

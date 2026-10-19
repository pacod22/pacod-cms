# Template sets

A website's public pages render with a **template set**: Thymeleaf templates that give the pages
their markup, plus a manifest that tells the studio what the set can render. A website chooses its
set when it is created and can switch in the studio (*Design → Template*); its layouts and theme
are stored per website, not per set, so they survive a switch.

Two sets ship: **`editorial`** (a magazine front page, a content column beside an aside) and
**`minimal`** (one centred column), which extends `editorial`. A set is code an operator installs,
built into the site's jar; there is no upload, because a Thymeleaf template can do anything the
site can.

## The two halves

A set is used only when both halves exist; with one of them missing the site logs an ERROR at
startup and leaves the set out, and a website on it renders with the default.

| Half | Where | What |
|---|---|---|
| Templates | `pacod-cms-site/src/main/resources/templates/sets/<id>/` | the page templates, `sections.html`, `fragments/` |
| Manifest | `pacod-cms-core/src/main/resources/design/sets/<id>.yaml` | name, parent, section types, page types with regions and default layouts, theme presets |

The manifest is in core because the studio reads it too: the layout editor offers only the section
types the set renders, in the regions it has, and the template switch reports what a candidate set
would leave out.

```yaml
id: notes
name: Notes
description: A single column with a quiet sidebar.
extends: editorial            # optional: what this set does not provide, the parent does
sections: [primary, article-list, rich-text, call-to-action]
pageTypes:
  HOME:
    regions: [main]
    layout:                   # the default, used until the website publishes its own
      main:
        - {type: article-list, settings: {source: latest, count: 1, style: hero}}
        - {type: article-list, settings: {source: latest, count: 10, offset: 1, style: list}}
  ARTICLE:  {regions: [main], layout: {main: [{type: primary}]}}
  CATEGORY: {regions: [main], layout: {main: [{type: primary}]}}
  LATEST:   {regions: [main], layout: {main: [{type: primary}]}}
  PAGE:     {regions: [main], layout: {main: [{type: primary}]}}
  SEARCH:   {regions: [main], layout: {main: [{type: primary}]}}
themes:
  ink:
    name: Ink
    tokens: {bg: "#ffffff", ink: "#111111", accent: "#1d4ed8", font-text: system-serif}
```

**A default layout names no category**: a new website has none yet.

## Resolution

Each set gets its own template engine, whose resolvers look in the set, then each parent in turn,
then the shared `_base`. A set therefore overrides only what it changes: `minimal` provides its
page templates, `sections.html` and `fragments/stylesheets.html`, and takes the article view, the
navbar, the footer, the social page, the print views, the issue pages' content and the error page
from `editorial`.

The page templates, by view name:

| Template | Page | Model |
|---|---|---|
| `landing` | `/`, the home page (`HOME`) | `regions` |
| `article` | `/article/{id}` (`ARTICLE`) | `article`, `regions` |
| `index` | `/latest`, the newest article (`LATEST`) | `article`, `regions` |
| `list` | `/list/{category or tag}` (`CATEGORY`) | `list`, `regions` |
| `page` | `/p/{slug}`, a standing page (`PAGE`) | `page`, `languages`, `regions` |
| `search`, `search-google` | `/search`, `/search-google` (`SEARCH`) | `q`, `results`, `searched`, `regions` |
| `social` | `/social`, the link page | its own head; no regions |
| `article-print` | `/article-print/{id}`, what PDFs are made of | the article, `printMeta` (abstract, keywords); always light |
| `article-print-scientific` | `/article-print/{id}?format=scientific`: a full-width head, the body in two columns | as `article-print` |
| `issue-print-front` | `/issue-print/{id}/front`, a journal issue's cover, editorial and contents; behind a print token | `view`, `entries` (with pages) |
| `issues`, `issue` | `/issues`, `/issues/{id}`, the published journal issues | `views`/`issues`; `view`, `entries`, `pdfs` |
| `error` | every error page of a known host | `site` and the request's locale only |

The print views link `/site-assets/print.css` after the theme: unconditional rules (Gotenberg
renders with the print media type), font stacks that exist in Gotenberg's image (web fonts never
load there), the scientific format's two columns with guards for legacy bodies, and the issue's
cover and contents. They load no `fragments/head`, so they always print light. The issue pages'
content is `fragments/issues :: list` and `:: detail`, shared by every set's `issues.html` and
`issue.html`.

Every page also has `site` (the website's identity, menus and validated third-party ids), `lang`
(the content language in force) and the advertising lists `topBanners` and `sidebarBanners`.

## Sections

A page's content comes from its layout: regions holding ordered sections, each of a **section
type** from a closed vocabulary that every set implements or skips:

| Type | Fragment | Settings |
|---|---|---|
| `primary` | — (the page's own content) | none |
| `article-list` | `articleList` | `source` (latest, category, most-read), `category`, `count`, `offset`, `style` (cards, hero, list, ranked, video), `heading` |
| `rich-text` | `richText` | `heading`, `html` (sanitised) |
| `call-to-action` | `callToAction` | `heading`, `text`, `label`, `url` |
| `search-box` | `searchBox` | `heading` |
| `social-links` | `socialLinks` | `heading` |

`sections.html` holds one fragment per type the set renders, each taking `(s, lang)`, where `s` is
a ready `RenderedSection`: its settings (`s.setting('style')`), and for an article list its data
(`s.data`), already looked up. A page template loops each region over them:

```html
<th:block th:each="s : ${regions.get('main')}">
    <th:block th:if="${s.primary}"><!-- the page's own content here --></th:block>
    <th:block th:unless="${s.primary}"
              th:replace="~{sections :: ${s.fragment}(s=${s}, lang=${lang})}"></th:block>
</th:block>
```

The rules that keep this safe:

- **A fragment never queries.** `PageComposer` looks everything up before rendering, each section on
  its own: one whose data fails is left out and logged, and the page still renders.
- **A fragment name comes only from the section type**, never from request data.
- **Nothing is dropped silently.** A section the set cannot render — a region it lacks, a type it
  skips, settings that fail validation — is left out of the page and reported in the studio, and
  stays in the layout for a set that can.
- A page with content of its own always gets its `primary` section, at the end of the first region
  if the layout has none.

## Themes

A theme is token values plus the website's own CSS, served as `/theme.css` after the set's
stylesheets. The tokens are shared by every set, so a theme survives a template switch:

| Kind | Tokens (CSS custom property) |
|---|---|
| Colours, each with a dark value for `.night` | `bg`, `surface`, `line`, `ink`, `muted`, `faint`, `accent`, `highlight`, `tint`, `hover`, `panel` (`--bg` … `--panel`) |
| Fonts, from an allow-list | `font-display`, `font-text`, `font-label`: `lora`, `plex-sans`, `plex-mono`, `georgia`, `system-serif`, `system-sans`, `system-mono` |
| Lengths in pixels, bounded | `shell` (720–1600), `gutter` (8–64) |

A set's stylesheet reads the properties (`var(--accent)`); its `themes` in the manifest are presets
the theme editor offers. Stylesheets are served from the jar under `/site-assets/`; a set names its
own in `fragments/stylesheets.html`.

## Writing templates

Thymeleaf runs in **restricted mode**: no `new`, no static calls, no bean references in
expressions. Compute anything a template would construct in Java. And:

- Every word of chrome is a message key (`#{…}`) from the site's `messages*.properties`, in all six
  bundles; a missing one fails `MessageBundleTest`.
- A backslash does not escape a quote in SpEL; double it (`'The site''s'`).
- Fragment inclusion runs before `th:each`, `th:if` and `th:case` on the same element: wrap the
  `th:replace` in a block of its own.
- `map[k]` with a bare identifier means the key `"k"`; write `map.get(k)`.
- Load no tracking a website did not configure: render the tag manager, Facebook and the
  verification metas only through `site`'s validated values, which are null in a preview.
- Advertising markup keeps the `partner-` class names; generic ad-blocker rules hide `ad-` ones.

## Adding a set

1. Write the manifest in core's `design/sets/` and the templates in the site's `templates/sets/<id>/`,
   extending `editorial` to start from everything it has.
2. Run the render tests (`PublicTemplateRenderTest`, `LandingTemplateRenderTest`) and
   `TemplateSetIsolationIT`, which renders two sets interleaved on one site.
3. Rebuild both images: the manifest is in the studio's jar, the templates in the site's.

The new set then appears in the new-website form and on the template switch.

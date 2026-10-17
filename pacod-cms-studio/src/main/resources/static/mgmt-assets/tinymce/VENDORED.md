# TinyMCE 8.8.2 — vendored

Do not edit anything in this directory. It is an unmodified subset of an upstream release.

## Why it is committed rather than fetched

An editor copied onto a storage volume, outside the repository, is invisible to review: nobody
sees which version it is, which CVEs it carries, or that it has stopped resolving its own skins and
plugins — which, against the `/mgmt/**` catch-all, is a 404 for each of them and an editor that
never appears.

Committed here, the editor version is visible in `git log`, a clean checkout has a working console,
and no deployment depends on someone having copied the right files onto a volume.

## Where it came from

```bash
curl -sSLO https://download.tiny.cloud/tinymce/community/tinymce_8.8.2.zip
curl -sSLO https://download.tiny.cloud/tinymce/community/languagepacks/8/langs.zip
```

Both are anonymous downloads; neither needs an account. The community zip ships minified-only
(1.1 MB) where the npm tarball is 12 MB of mostly source maps and `.d.ts`. The language packs are a
**separate** download — `langs/` ships empty in both the zip and on npm.

## What was kept

`tinymce.min.js`, `themes/silver/`, `models/dom/`, `icons/default/`, the `oxide` and `oxide-dark`
UI skins with their `default`/`dark` content skins (the console is dark-mode aware, so the editor
has to be too), `langs/bg-BG.js`, `langs/de.js`, `langs/ru.js`, and 22 plugins:

    advlist anchor autolink charmap code codesample directionality fullscreen image
    importcss insertdatetime link lists media nonbreaking pagebreak preview
    searchreplace table visualblocks visualchars wordcount

Deliberately dropped: `emoticons` (599 KB of emoji data), `help` and its 52 keynav translations,
`accordion`, `autoresize`, `autosave`, `quickbars`, `save`, the `tinymce-5*` legacy skins, the
other 58 languages, `tinymce.d.ts`, and every unminified file. Total ≈ 1.9 MB.

`paste` and `textcolor` are **not** missing — they became core in TinyMCE 6 and need no plugin.
`powerpaste`, `advtable` and `editimage` are commercial and are not in the open-source
distribution at all.

### Language packs

The console is offered in Bulgarian, English, German and Russian, and the editor's own UI follows
it: `mgmt.js` reads the pack name from `data-tinymce` on the head fragment's `#mgmt-i18n`
(`MgmtText.tinymceLanguage()`), and leaves `language` unset for English, which is built in.

| File | Source | Fetched | sha256 |
|---|---|---|---|
| `langs/bg-BG.js` | `langs.zip` above | with 8.8.2 | (unchanged; byte-identical to the 2026-10-12 download) |
| `langs/de.js` | `langs.zip` above (`langs/de.js`) | 2026-10-12 | `b71ffc5c0c2f71f531276409f2b4bd7657332df9bcbb8d2f1d326f1807854e91` |
| `langs/ru.js` | `langs.zip` above (`langs/ru.js`) | 2026-10-12 | `02f3a1999c5a8f68fb6ec543800846c37c111c804556f938db7ca081052cd2b8` |

Same archive and same licence as `bg-BG.js` (the community language packs, distributed with
TinyMCE under GPL-2.0-or-later). Each was checked before committing to be exactly one
`tinymce.addI18n("<code>", {…})` call over string keys and string values, with the code matching
the file name, and nothing else. A pack's name is the code TinyMCE loads it by, so `de.js` must
register `"de"` -- a mismatch fails silently and shows English.

## Licence

GPL-2.0-or-later. `license.md` and `notices.txt` are kept alongside the code because we serve it.
The rest of the project is Apache-2.0; `THIRD-PARTY-NOTICES.md` at the repository root says how
the two combine.

**Corresponding source.** What is committed here is the minified build, which is not the form the
GPL means by source. The source for exactly this release is the upstream tag
[`8.8.2`](https://github.com/tinymce/tinymce/tree/8.8.2) (commit
`52ebfa8299b68ecc144118f2989baad361c924a7`). The language packs are plain, unminified source
already. **An upgrade updates this pointer too**, together with `THIRD-PARTY-NOTICES.md`.

## Updating

Re-download both archives at the new version and repeat the file selection above. Then open stored
article bodies in the editor and read the fidelity warning (`#fidelity-warning`) — a minor TinyMCE
release can change a serializer default, and the configuration in `mgmt-assets/mgmt.js` exists
precisely to pin those defaults against the bodies already stored.

TinyMCE resolves `themes/`, `models/`, `plugins/`, `skins/`, `icons/` and `langs/` relative to the
directory holding `tinymce.min.js`, so the single `<script>` tag in `templates/mgmt/article-edit.html`
is the whole integration. Do not add a `base_url`.

# Third-party components

The project's own code is licensed under the [Apache License 2.0](LICENSE). The components below
are committed to this repository under their **own** licences, and those licences govern them,
not the Apache License.

Maven dependencies are not listed: they are not in the repository, Maven fetches them, and each
carries its own licence in its jar. Nearly all that reach a runtime classpath are Apache-2.0 (among
them Guava, which the studio reads the Public Suffix List through, snakeyaml-engine, which reads
the role manifest, and PDFBox, which the studio assembles journal issues with); the rest are MIT (SLF4J; jsoup, which the studio parses AI answers with),
EPL-2.0 (AspectJ Weaver), EPL-2.0 or GPL-2.0 with the Classpath Exception (Jakarta Annotations) and
EPL-2.0 or LGPL-2.1 (Logback). The Public Suffix List data inside Guava is MPL-2.0. None of them
restricts the licence of this project. Regenerate the list with:

```bash
./mvnw -Dmaven.test.skip org.codehaus.mojo:license-maven-plugin:2.4.0:aggregate-add-third-party \
    -Dlicense.excludedScopes=test,provided
```

| Component | Version | Where | Licence |
|---|---|---|---|
| TinyMCE | 8.8.2 | `pacod-cms-studio/src/main/resources/static/mgmt-assets/tinymce/` | GPL-2.0-or-later |
| TinyMCE language packs (`bg-BG`, `de`, `ru`) | for 8.x | `…/mgmt-assets/tinymce/langs/` | GPL-2.0-or-later, distributed with TinyMCE |
| DOMPurify | 3.3.2 | inside `tinymce.min.js` | MPL-2.0 OR Apache-2.0 |
| Prism, prism-themes | 1.25.0, 1.9.0 | inside TinyMCE's `codesample` plugin (the studio's editor) | MIT |
| Prism (core and eleven grammars), the public site's highlighter | 1.30.0 | `pacod-cms-site/src/main/resources/static/site-assets/vendor/prism/`; what is in it and how it was built in `VENDORED.md` there | MIT (`LICENSE.txt` beside it) |
| Simple Icons | 15.0.0 | brand glyph paths in `pacod-cms-site/src/main/resources/templates/sets/editorial/social.html` | CC0-1.0 |
| Maven Wrapper | 3.3.4 | `mvnw`, `mvnw.cmd`, `.mvn/wrapper/` | Apache-2.0 |
| Fraunces (variable, weight axis; Latin, Latin Extended) | 5.3.0 of `@fontsource-variable/fraunces` | `pacod-cms-site/src/main/resources/static/site-assets/fonts/` | SIL OFL 1.1 (`OFL-Fraunces.txt` beside it) |
| Inter (variable, weight axis; Latin, Latin Extended, Cyrillic) | 5.3.0 of `@fontsource-variable/inter` | `pacod-cms-site/src/main/resources/static/site-assets/fonts/` | SIL OFL 1.1 (`OFL-Inter.txt` beside it) |
| NASA photographs and artist's impressions (37) | as listed | `pacod-cms-core/src/main/resources/demo/space/photos/` | NASA and JPL releases, each credited; see that folder's `VENDORED.md` for the source of every picture and the rule they were chosen by |

TinyMCE's own licence and its list of bundled libraries are kept beside it, in
[`license.md`](pacod-cms-studio/src/main/resources/static/mgmt-assets/tinymce/license.md) and
[`notices.txt`](pacod-cms-studio/src/main/resources/static/mgmt-assets/tinymce/notices.txt).
How and why it is vendored is in that directory's
[`VENDORED.md`](pacod-cms-studio/src/main/resources/static/mgmt-assets/tinymce/VENDORED.md).

## TinyMCE and the GPL

- **Only the studio contains it.** The public site (`pacod-cms-site`) and the core library
  (`pacod-cms-core`) do not ship TinyMCE, and nothing about them is affected by its licence.
- **The studio as built is a combined work under the GPL.** TinyMCE's licence is "version 2 or
  later", and the Apache License 2.0 is compatible with GPL version 3. So the
  `pacod-cms-studio` jar and its container image are redistributed under GPL-3.0 terms as a
  whole, while every file of this project's own inside them stays Apache-2.0 on its own. That is
  also why this project cannot be relicensed GPL-2.0-only: the Apache-2.0 dependencies it is built
  on are incompatible with that version.
- **Corresponding source.** The repository holds only the minified build. The source for exactly
  this release is the upstream tag
  [`8.8.2`](https://github.com/tinymce/tinymce/tree/8.8.2) of `github.com/tinymce/tinymce`
  (commit `52ebfa8299b68ecc144118f2989baad361c924a7`). The committed files came from
  `https://download.tiny.cloud/tinymce/community/tinymce_8.8.2.zip`. The language packs came from
  `https://download.tiny.cloud/tinymce/community/languagepacks/8/langs.zip`, and are plain,
  unminified source already. If you redistribute the studio, carry this pointer with it.

## Not licensed

The Apache License covers code and does not grant trademark rights (section 6). In particular:

- The name "pacod-cms" and the PACOD marks are not licensed.
- The brand glyphs in the social page's icon sprite are trademarks of their owners. Simple Icons'
  CC0 dedication covers the drawings, not the marks.

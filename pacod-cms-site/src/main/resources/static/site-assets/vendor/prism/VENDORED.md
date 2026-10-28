# Prism 1.30.0 — vendored

Do not edit `prism.min.js`. It is upstream's own minified files, concatenated, under a one-line
banner; nothing in them is changed.

## What it is for

The public site colours the code samples of an article body or a standing page
(`<pre class="language-java"><code>…</code></pre>`, as the studio's editor stores them). The
templates load this file only on a page whose body holds a `<pre>` (`fragments/scripts ::
codeHighlight` in the editorial set), with `defer` and `data-manual`, and the fragment then asks
Prism to highlight the `.article-body` alone. The colours are the site's own, in `site.css`
(`.article-body .token…`), drawn from the theme's tokens; Prism's themes are not used. A print view
loads no script, and prints the code plain.

This copy is the public site's alone. The studio's editor has its own, older Prism inside TinyMCE's
`codesample` plugin, which is TinyMCE's to update.

## Where it came from

The npm package `prismjs@1.30.0`, as published:

| | |
|---|---|
| Tarball | `https://registry.npmjs.org/prismjs/-/prismjs-1.30.0.tgz` |
| sha256 | `ac16a9106a28c53b6a6313993c816a3a02525fdfefd7614fc1703df915a9fc11` |
| npm integrity | `sha512-DEvV2ZF2r2/63V+tK8hQvrR2ZGn10srHbXviTlcv7Kpzw8jWiNTqbVgjO3IY8RxrrOUF8VPMQQFysYYYv0YZxw==` |
| npm shasum (sha1) | `d9709969d9d4e16403f6f348c63553b19f0975a9` |
| Upstream commit (`gitHead`) | `93cca40b364215210f23a9e35f085a682a2b8175`, `github.com/PrismJS/prism` |
| Licence | MIT, Copyright (c) 2012 Lea Verou; the package's `LICENSE` is `LICENSE.txt` here |
| Fetched | 2026-10-28 |

1.30.0 is the release that fixes CVE-2024-53382, DOM clobbering through `document.currentScript`
(an element named `currentScript` in the page could stand in for Prism's own script tag). The
package's CHANGELOG stops at 1.29.0, so the fix was checked in the code itself:
`Prism.util.currentScript` returns `document.currentScript` only when its `tagName` is `SCRIPT`.

## What is in it

`prism-core` and eleven grammars, in this order, each after the ones it needs (`components.json`'s
`require`): `clike`, `javascript`, `typescript`, `java`, `bash`, `yaml`, `json`, `toml`, `rust`,
`python`, `sql`. Their aliases come with them: `sh` and `shell` (bash), `yml`, `ts`, `js`, `py`,
`webmanifest`; and the core's `plain`, `plaintext`, `text` and `txt` show code as it is. A language
not in the list is shown plain, never as an error. `javascript` refers to `markup` and `regex` only
where they are loaded, and they are not.

No plugin is included: no line numbers, no copy button, no autoloader -- the autoloader would fetch
grammars from wherever Prism's own script came from, at the reader's request.

| File | sha256 |
|---|---|
| `prism.min.js` (34,388 bytes) | `701de2fd51b18c62352bb820aa955c9f3e78ec6efc3b30498dd158818546aa25` |
| `LICENSE.txt` | `2b947f0901a7ffcf08a89957da9783c0e9c6e72cb6ce8e959f501ab5409e4d2b` |

## How to rebuild it

Nothing from the package is run -- no `npm install`, no build script; the files are only read.

```bash
mkdir prism && cd prism
curl -sSLO https://registry.npmjs.org/prismjs/-/prismjs-1.30.0.tgz
shasum -a 256 prismjs-1.30.0.tgz   # ac16a9106a28c53b6a6313993c816a3a02525fdfefd7614fc1703df915a9fc11
tar -xzf prismjs-1.30.0.tgz
cd package/components
{ printf '/*! PrismJS 1.30.0 (MIT, LICENSE.txt beside this file) -- core, clike, javascript, typescript, java, bash, yaml, json, toml, rust, python, sql -- https://prismjs.com */\n'
  for c in core clike javascript typescript java bash yaml json toml rust python sql; do
    cat prism-$c.min.js; printf '\n'
  done; } > prism.min.js
cp ../LICENSE LICENSE.txt
shasum -a 256 prism.min.js LICENSE.txt   # the table above
```

To add a language, add its `prism-<name>.min.js` after what it requires (see the package's
`components.json`), add it to the studio's `codesample_languages` in `mgmt.js` if editors should
pick it, and update this file and the row in `THIRD-PARTY-NOTICES.md`. To upgrade, change the
version everywhere above, check the release notes for changed token names (`site.css` styles them),
and record the new hashes.

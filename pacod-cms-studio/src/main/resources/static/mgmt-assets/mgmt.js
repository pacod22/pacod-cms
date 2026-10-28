/*
 * Management console behaviour.
 *
 * Plain ES, no jQuery, no build step -- the repository has no npm toolchain and this is not
 * the change that should introduce one.
 *
 * Everything here is progressive enhancement: with JavaScript disabled the console still works,
 * because every action is a real form POST and every control is a real form control. What
 * degrades, and only degrades, is the rich-text editor (falls back to its textarea), the
 * searchable pickers (fall back to the native <select multiple> they wrap), row-click
 * navigation (the title is still a link) and the image upload, which falls back from uploading in
 * place to the plain multipart POST the form declares -- drag-and-drop with it, though the file
 * input is still there.
 */
(function () {
    'use strict';

    /* --------------------------------------------------------------- i18n */

    /*
     * Every string this file shows comes from the `js.*` keys of the console's common bundle,
     * rendered by the head fragment into <script type="application/json" id="mgmt-i18n"> in the
     * operator's language, with the `js.` prefix stripped. Parsed once, lazily. A missing element or
     * unparseable JSON leaves an empty object, and a missing key renders as the key itself -- visible
     * and greppable, rather than a blank that looks like a deliberate empty label.
     */
    /**
     * The console's base path: /mgmt, or /sites/<id>/mgmt inside a website. Rendered by the head
     * fragment, so every fetch below goes to the website the page itself belongs to.
     */
    var MGMT = (function () {
        var meta = document.querySelector('meta[name="mgmt-base"]');
        var base = meta && meta.getAttribute('content');
        return base ? base.replace(/\/$/, '') : '/mgmt';
    }());

    var i18nBundle = null;

    function i18nElement() {
        return document.getElementById('mgmt-i18n');
    }

    function t(key) {
        if (i18nBundle === null) {
            i18nBundle = {};
            var element = i18nElement();
            if (element) {
                try {
                    var parsed = JSON.parse(element.textContent || '{}');
                    if (parsed && typeof parsed === 'object') {
                        i18nBundle = parsed;
                    }
                } catch (e) {
                    /* a broken bundle shows keys, never a dead console */
                }
            }
        }
        var text = Object.prototype.hasOwnProperty.call(i18nBundle, key) ? String(i18nBundle[key]) : key;
        var args = Array.prototype.slice.call(arguments, 1);
        return text.replace(/\{(\d+)\}/g, function (match, index) {
            var i = parseInt(index, 10);
            return i < args.length ? String(args[i]) : match;
        });
    }

    /** t() for innerHTML: the message text is escaped, the arguments are inserted as given. */
    function tHtml(key) {
        var args = Array.prototype.slice.call(arguments, 1);
        var placeholder = [];
        var raw = t.apply(null, [key].concat(args.map(function (arg, i) {
            placeholder.push(arg);
            return '\u0000' + i + '\u0000';
        })));
        var escaped = raw.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
        return escaped.replace(/\u0000(\d+)\u0000/g, function (match, index) {
            return String(placeholder[parseInt(index, 10)]);
        });
    }

    /* --------------------------------------------------------------- CSRF */

    /**
     * The mgmt filter chain has CSRF enabled, so any request that is not a plain form POST has
     * to carry the token itself. th:action writes the hidden field for the forms; this is for
     * anything that ever posts via fetch.
     */
    window.mgmtCsrf = function () {
        var token = document.querySelector('meta[name="_csrf"]');
        var header = document.querySelector('meta[name="_csrf_header"]');
        if (!token || !header) {
            return {};
        }
        var headers = {};
        headers[header.getAttribute('content')] = token.getAttribute('content');
        return headers;
    };

    /* ---------------------------------------------------------------- theme */

    /*
     * Light/dark is a stored preference with "follow the system" as the unset state. The
     * <head> fragment applies the stored value synchronously before first paint; all this does
     * is flip it. Both live under the same key.
     */
    var THEME_KEY = 'pacod-mgmt-theme';

    function storedTheme() {
        try {
            return localStorage.getItem(THEME_KEY);
        } catch (e) {
            return null;
        }
    }

    function currentTheme() {
        var stored = storedTheme();
        if (stored === 'light' || stored === 'dark') {
            return stored;
        }
        return systemPrefersDark() ? 'dark' : 'light';
    }

    function systemPrefersDark() {
        return !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
    }

    /**
     * What TinyMCE's skin is chosen from. The skin is fixed at init and cannot be swapped
     * afterwards, so toggling the theme with the editor open leaves the editor chrome on the
     * old palette until the page is reloaded. That is the whole cost of the toggle.
     */
    function darkMode() {
        return currentTheme() === 'dark';
    }

    function applyTheme(theme) {
        document.documentElement.setAttribute('data-theme', theme);
        try {
            localStorage.setItem(THEME_KEY, theme);
        } catch (e) {
            /* private mode: the choice simply does not survive the navigation */
        }
        paintThemeButton();
    }

    function paintThemeButton() {
        var button = document.getElementById('theme-toggle');
        var icon = document.getElementById('theme-icon');
        if (!button || !icon) {
            return;
        }
        var dark = darkMode();
        // The icon shows what the click will switch TO, which is the convention operators expect.
        icon.firstElementChild.setAttribute('href', dark ? '#i-sun' : '#i-moon');
        button.setAttribute('title', dark ? t('theme.light') : t('theme.dark'));
    }

    (function initTheme() {
        var button = document.getElementById('theme-toggle');
        if (!button) {
            return;
        }
        // Rendered hidden so a scriptless page never shows a dead control.
        button.hidden = false;
        paintThemeButton();
        button.addEventListener('click', function () {
            applyTheme(darkMode() ? 'light' : 'dark');
        });
    }());

    /* ------------------------------------------------------------- sidebar */

    /*
     * The sidebar collapses to a 68px icon rail, and the choice is remembered. Same shape as the
     * theme above and for the same reasons: the <head> fragment applies the stored value before
     * first paint -- deferring it here would slide the whole page sideways on every navigation as
     * the 250px margin collapsed -- and this only flips it.
     *
     * All of the geometry is one CSS custom property; see the collapsed block in mgmt.css. Nothing
     * here measures or moves anything.
     *
     * The mobile drawer is a different mechanism entirely (a CSS-only checkbox), which is why
     * mgmt.css hides this button below 1001px rather than letting it write a value that moves
     * nothing.
     */
    var NAV_KEY = 'pacod-mgmt-nav';

    function navCollapsed() {
        return document.documentElement.getAttribute('data-nav') === 'collapsed';
    }

    function applyNav(collapsed) {
        var root = document.documentElement;
        if (collapsed) {
            root.setAttribute('data-nav', 'collapsed');
        } else {
            root.removeAttribute('data-nav');
        }
        try {
            localStorage.setItem(NAV_KEY, collapsed ? 'collapsed' : 'expanded');
        } catch (e) {
            /* private mode: the choice simply does not survive the navigation */
        }
        paintNavButton();
    }

    function paintNavButton() {
        var button = document.getElementById('nav-collapse');
        var icon = document.getElementById('nav-collapse-icon');
        if (!button || !icon) {
            return;
        }
        var collapsed = navCollapsed();
        // Like the theme icon, this one shows what the click will do.
        icon.firstElementChild.setAttribute('href', collapsed ? '#i-panel-expand' : '#i-panel-collapse');
        button.setAttribute('title', collapsed ? t('nav.expand') : t('nav.collapse'));
        button.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
        var label = button.querySelector('.sr-only');
        if (label) {
            label.textContent = collapsed ? t('nav.expand') : t('nav.collapse');
        }
    }

    (function initNav() {
        var button = document.getElementById('nav-collapse');
        if (!button) {
            return;
        }
        button.hidden = false;
        paintNavButton();
        button.addEventListener('click', function () {
            applyNav(!navCollapsed());
        });
    }());

    /* ------------------------------------------------------ confirmations */

    document.addEventListener('submit', function (event) {
        var form = event.target;

        /*
         * Every form on the article editor except the upload's own navigates away, and because
         * «Качи» uploads without reloading there can be an upload running behind them: removing an
         * image, restoring the text and deleting the article are all load-modify-save over the same
         * document as MgmtArticleService.addImage, so one committing between the other's read and
         * write drops the new image from `images` and leaves its file on the volume owned by
         * nothing. The article form has its own copy of this check, because it additionally has to
         * re-arm the dirty tracker TinyMCE clears on the way through.
         *
         * The banner layout builder's slide forms are the same shape, and its own upload form is
         * exempt for the reason the article's is: its listener has already run by the time the
         * event bubbles here, and has just set the flag itself.
         */
        if (imageUploadInFlight && !form.hasAttribute('data-async')
                && !form.hasAttribute('data-slide-upload')) {
            event.preventDefault();
            note(document.getElementById('upload-status'),
                    t('upload.waitImage'), true);
            return;
        }

        var message = form.getAttribute('data-confirm');
        if (message && !window.confirm(message)) {
            event.preventDefault();
        }
    });

    /* ------------------------------------------------------------ flashes */

    document.addEventListener('click', function (event) {
        var close = event.target.closest && event.target.closest('.flash-close');
        if (close) {
            close.parentNode.remove();
        }
    });

    /* ------------------------------------------------------- list search */

    var search = document.getElementById('search-input');
    if (search) {
        var searchForm = search.form;
        var timer = null;

        // Not one request per keystroke: this submits the form once the operator stops typing,
        // and only when the term actually changed.
        var initial = search.value;
        search.addEventListener('input', function () {
            window.clearTimeout(timer);
            timer = window.setTimeout(function () {
                if (search.value !== initial) {
                    searchForm.requestSubmit ? searchForm.requestSubmit() : searchForm.submit();
                }
            }, 450);
        });

        // "/" jumps to the search box from anywhere on the page -- the single most-used control
        // on all three list screens. Escape gives the keyboard back.
        document.addEventListener('keydown', function (event) {
            if (event.key === '/' && !isTyping(event.target)) {
                event.preventDefault();
                search.focus();
                search.select();
            } else if (event.key === 'Escape' && document.activeElement === search) {
                search.blur();
            }
        });
    }

    function isTyping(el) {
        if (!el) {
            return false;
        }
        var tag = el.tagName;
        return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable;
    }

    /* ------------------------------------------------- auto-submit filters */

    Array.prototype.forEach.call(document.querySelectorAll('[data-autosubmit]'), function (el) {
        el.addEventListener('change', function () {
            el.form.requestSubmit ? el.form.requestSubmit() : el.form.submit();
        });
    });

    /* --------------------------------------------------------- row links */

    /*
     * The whole table row opens the record, not just the title. The title stays a real <a>, so
     * this adds a target rather than replacing one: middle-click, Ctrl-click and "open in new
     * tab" all keep working, and nothing here runs if scripting is off.
     */
    var rows = document.querySelectorAll('tr[data-href]');
    Array.prototype.forEach.call(rows, function (row) {
        row.classList.add('row-link');
    });

    if (rows.length) {
        document.addEventListener('click', function (event) {
            var row = event.target.closest && event.target.closest('tr[data-href]');
            if (!row) {
                return;
            }
            // Anything that is itself interactive wins: the row must not swallow the "view on
            // site" link or a delete button.
            //
            // .path-row is in the list by name rather than by being a control. The image library
            // is the first clickable row to carry one, and the gap between the readonly field and
            // the copy button -- plus whatever the cell is wider than the field's 320px cap --
            // belongs to neither. A click that lands there is an operator aiming at the path, and
            // navigating away is the one thing they did not ask for.
            if (event.target.closest('a, button, input, label, form, .path-row')) {
                return;
            }
            // Selecting text in a cell is not a click-through.
            if (window.getSelection && String(window.getSelection()).length > 0) {
                return;
            }
            var href = row.getAttribute('data-href');
            if (event.metaKey || event.ctrlKey || event.button === 1) {
                window.open(href, '_blank', 'noopener');
            } else {
                window.location.href = href;
            }
        });
    }

    /* ------------------------------------------------------------ pickers */

    /*
     * Turns a <select multiple> into a searchable checkbox list.
     *
     * The native control is the source of truth throughout: it keeps its id, its name and its
     * th:field binding, and the checkboxes below merely drive `option.selected`. Nothing here
     * introduces a new form parameter, which is deliberate -- MgmtArticleService.save writes
     * the fields the form carries, so a picker that posted under a different name would strip
     * an article's authors and categories on the next save rather than fail loudly.
     *
     * The checkboxes carry no `name` for the same reason: they must never reach the server.
     */
    // Declared above the call, not beside the scrim helpers below: makeOverlay pushes into
    // `overlays`, and a `var` initialised further down would still be undefined when it runs.
    var overlays = [];
    var scrim = null;

    Array.prototype.forEach.call(document.querySelectorAll('select[multiple][data-picker]'), buildPicker);

    function buildPicker(select) {
        var options = Array.prototype.slice.call(select.options);
        if (!options.length) {
            return;
        }

        var picker = document.createElement('div');
        picker.className = 'picker';

        var searchWrap = document.createElement('div');
        searchWrap.className = 'picker-search';
        searchWrap.innerHTML = '<svg class="i"><use href="#i-search"></use></svg>';

        var filter = document.createElement('input');
        filter.type = 'search';
        filter.placeholder = select.getAttribute('data-picker') || t('picker.search');
        filter.setAttribute('aria-label', filter.placeholder);
        searchWrap.appendChild(filter);

        var list = document.createElement('div');
        list.className = 'picker-list';

        var foot = document.createElement('div');
        foot.className = 'picker-foot';
        var count = document.createElement('span');
        count.className = 'count';
        var clear = document.createElement('button');
        clear.type = 'button';
        clear.className = 'btn btn-sm btn-ghost';
        clear.textContent = t('picker.clear');

        // The «Уголеми» button is built by makeOverlay below and inserted between these two.
        foot.appendChild(count);
        foot.appendChild(clear);

        // Every checkbox, so the whole list can be repainted when something else changes the
        // selection -- see the `change` listener below.
        var boxes = [];

        /*
         * By id, ascending. Done once, at build time, on a COPY: re-sorting on every toggle would
         * move the row out from under the pointer, and sorting `select.options` themselves would be
         * the data loss MgmtArticleService.authorsForPicker exists to prevent -- a <select multiple>
         * submits in tree order, so the option order of #authorIds IS the byline. Nothing here ever
         * touches it; the rows are a second, purely visual list over the same option objects.
         *
         * Not selected-first: that puts a row in a different place depending on what is ticked --
         * so an operator who knows an author's number cannot use it to find the row. What
         * selected-first would be for is covered better elsewhere: the foot counts the selection,
         * ticked rows are highlighted, and the ordering card below lists exactly the authors the
         * article carries.
         */
        options.slice().sort(byId).forEach(function (option) {
            var row = document.createElement('label');
            var box = document.createElement('input');
            box.type = 'checkbox';
            box.checked = option.selected;

            var text = document.createElement('span');
            text.textContent = option.textContent.trim();

            row.appendChild(box);
            row.appendChild(text);
            row.className = option.selected ? 'on' : '';
            // Lower-cased once here rather than on every keystroke of the filter.
            row.setAttribute('data-term', option.textContent.toLowerCase());

            box.addEventListener('change', function () {
                option.selected = box.checked;
                row.className = box.checked ? 'on' : '';
                paintCount();
                // Fires the form's own change listeners -- the dirty tracker among them.
                select.dispatchEvent(new Event('change', {bubbles: true}));
            });

            boxes.push({option: option, box: box, row: row});
            list.appendChild(row);
        });

        var none = document.createElement('div');
        none.className = 'none';
        none.textContent = t('picker.none');
        none.hidden = true;
        list.appendChild(none);

        filter.addEventListener('input', function () {
            var term = filter.value.trim().toLowerCase();
            var shown = 0;
            Array.prototype.forEach.call(list.querySelectorAll('label'), function (row) {
                var match = !term || row.getAttribute('data-term').indexOf(term) !== -1;
                row.hidden = !match;
                if (match) {
                    shown++;
                }
            });
            none.hidden = shown > 0;
        });

        filter.addEventListener('keydown', function (event) {
            // Enter in the filter would submit the article form, which is never what is meant.
            if (event.key === 'Enter') {
                event.preventDefault();
            } else if (event.key === 'Escape' && filter.value) {
                event.preventDefault();
                filter.value = '';
                filter.dispatchEvent(new Event('input'));
            }
        });

        clear.addEventListener('click', function () {
            Array.prototype.forEach.call(list.querySelectorAll('input[type=checkbox]'), function (box) {
                if (box.checked) {
                    box.checked = false;
                    box.dispatchEvent(new Event('change'));
                }
            });
        });

        /*
         * The <select> is the single source of truth, so anything else that changes it has to be
         * able to move these checkboxes -- the author ordering card below removes an author by
         * clearing `option.selected`, and without this the box beside that name would stay ticked
         * and its row highlighted, with the two widgets quietly disagreeing about the same fact.
         *
         * Assigns `.checked` and never dispatches: a dispatch here would re-enter through each
         * box's own handler, which dispatches on the <select> again.
         */
        select.addEventListener('change', function () {
            boxes.forEach(function (entry) {
                entry.box.checked = entry.option.selected;
                entry.row.className = entry.option.selected ? 'on' : '';
            });
            paintCount();
        });

        function paintCount() {
            var selected = options.filter(function (o) { return o.selected; }).length;
            count.textContent = selected ? t('picker.selected', selected) : t('picker.nothing');
            clear.disabled = !selected;
        }

        /*
         * The overlay's own heading. Two identical-looking panels cover the window, so the one on
         * screen has to name itself; the search placeholder alone does not, and the <label> that
         * names the control inline is left behind in the card. Its text is read off that label
         * rather than repeated here, so the two cannot drift -- firstChild, because the authors'
         * label carries a trailing <span class="help"> that is about the ordering card below.
         *
         * Rendered always and shown by CSS only in the expanded state. It is toggled by a class
         * and never by `hidden`, which mgmt.css's own guard block at the foot explains: an author
         * `display` declaration beats [hidden] at any specificity, and .picker-head declares one.
         */
        var head = document.createElement('div');
        head.className = 'picker-head';
        var heading = document.createElement('h3');
        heading.textContent = labelTextOf(select) || (select.getAttribute('data-picker') || '');
        var close = document.createElement('button');
        close.type = 'button';
        close.className = 'btn btn-sm btn-ghost';
        close.textContent = t('common.close');
        head.appendChild(heading);
        head.appendChild(close);

        paintCount();
        picker.appendChild(head);
        picker.appendChild(searchWrap);
        picker.appendChild(list);
        picker.appendChild(foot);

        // Hidden, not removed: a hidden control is still submitted, and it is what carries the
        // binding. The <label for> in the template keeps pointing at it.
        select.hidden = true;
        select.parentNode.insertBefore(picker, select.nextSibling);

        /*
         * Moving the panel to <body> is safe here for the reason the checkboxes carry no `name`:
         * nothing inside the picker is ever submitted. The <select> -- the one element that is, and
         * the one carrying the th:field binding -- never moves, so the form posts the same
         * parameter either way and the `change` the checkboxes dispatch on it still bubbles to
         * #article-form's dirty tracker from exactly where it always did.
         */
        var overlay = makeOverlay(picker, {
            label: heading.textContent,
            expandTitle: t('picker.expand'),
            collapseTitle: t('picker.collapse'),
            onOpen: function () {
                filter.focus();
                filter.select();
            }
        });

        // count · expand · clear, which is the order the foot reads left to right.
        foot.insertBefore(overlay.button, clear);

        close.addEventListener('click', function () {
            overlay.setFull(false);
        });
    }

    /*
     * Lays a panel over the whole window, and hands back the button that does it.
     *
     * Shared by the author and category pickers and by the editor's image library, which is a
     * `.card` rather than a `.picker` -- the mechanism is about where the element is in the
     * document, not about what it looks like.
     *
     * The panel goes inside a slot that stays behind while the panel itself is lifted to <body>
     * for as long as the overlay is open. Two separate reasons, and the first is the one that is
     * invisible in a diff:
     *
     *  - z-index cannot climb out of a stacking context, and `.editor-rail` -- the column the
     *    pickers and the library all live in -- is `position: sticky`, which creates one whatever
     *    its z-index says. Left where it is, the panel's z-index only ranks it against its siblings
     *    inside that context, and the scrim (a child of <body>, at 40) paints straight over it: the
     *    overlay opens, and it opens BLURRED AND UNCLICKABLE UNDER ITS OWN BACKDROP. Verified in
     *    the browser, not reasoned about. Raising the number changes nothing, and neither does the
     *    sidebar's 30 -- the whole rail paints before either of them. Moving it to <body> is what
     *    makes "over the whole window" true, and it stays true if a later ancestor picks up a
     *    transform, a filter or another sticky.
     *  - the panel leaves the flow, so without the slot the rail would collapse by its height and
     *    the page would jump under the operator on every open and close. The slot's measured height
     *    is pinned as an inline min-height while the overlay is up.
     *
     * The one rule a caller has to keep: nothing inside the panel may carry a `name`. Both callers
     * sit inside <form id="article-form"> while collapsed and outside it while expanded, so a named
     * control would be posted in one state and not in the other, with nothing to show for it --
     * MgmtArticleService.save writes the fields the form carries.
     */
    function makeOverlay(panel, options) {
        var opts = options || {};

        var slot = document.createElement('div');
        slot.className = 'picker-slot';
        panel.parentNode.insertBefore(slot, panel);
        slot.appendChild(panel);

        /*
         * Built here rather than written in the template for the reason the article gallery's
         * arrows are built by site.js: a control in the markup that needs a script outlives its
         * code. Not a submit either -- type="button", or it would save the article.
         */
        var button = document.createElement('button');
        button.type = 'button';
        button.className = 'btn btn-sm btn-ghost';
        var icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        icon.setAttribute('class', 'i');
        var use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
        icon.appendChild(use);
        var label = document.createElement('span');
        button.appendChild(icon);
        button.appendChild(label);

        button.addEventListener('click', function () {
            setFull(!isFull());
        });

        var entry = {setFull: setFull, isFull: isFull, button: button};
        overlays.push(entry);
        paint();
        return entry;

        function isFull() {
            return panel.classList.contains('is-full');
        }

        function setFull(on) {
            if (on === isFull()) {
                return;
            }
            if (on) {
                // One layer at a time: two fixed panels would stack with one unreachable behind.
                closeAllOverlays();
                // Measured before the panel leaves, or the slot is already empty and reads 0.
                slot.style.minHeight = slot.offsetHeight + 'px';
                // The height alone is not the space the panel occupied. A block box's bottom
                // margin collapses out through the wrapper, so it is not in offsetHeight -- and
                // `.card` carries 18px of it, which the rail loses the moment the library leaves.
                // Measured, not assumed: with the height pinned and the margin not, the rail still
                // moves 17.86px on open. The picker does not show it because `.picker` has none.
                slot.style.marginBottom = window.getComputedStyle(panel).marginBottom;
                panel.classList.add('is-full');
                panel.setAttribute('role', 'dialog');
                // role and a label, deliberately no aria-modal: Tab still walks out of the panel
                // into the controls under the scrim, and there is no focus trap here. Claiming
                // modality would read as a guarantee and resolve to nothing -- the same reason the
                // console declares no @CacheEvict it has no cache to act on.
                panel.setAttribute('aria-label', opts.label || '');
                openScrim();
                document.body.appendChild(panel);
                if (opts.onOpen) {
                    opts.onOpen();
                }
            } else {
                panel.classList.remove('is-full');
                panel.removeAttribute('role');
                panel.removeAttribute('aria-label');
                slot.appendChild(panel);
                slot.style.minHeight = '';
                slot.style.marginBottom = '';
                closeScrim();
                button.focus();
            }
            paint();
        }

        /* An icon and a label that say what the click will do, the applyTheme/paintThemeButton
           shape every other stored-state control on this console uses. */
        function paint() {
            var full = isFull();
            use.setAttribute('href', full ? '#i-minimize' : '#i-maximize');
            label.textContent = full ? t('overlay.collapse') : t('overlay.expand');
            button.title = full ? (opts.collapseTitle || t('overlay.collapse')) : (opts.expandTitle || t('overlay.expand'));
            button.setAttribute('aria-expanded', full ? 'true' : 'false');
        }
    }

    /*
     * Ascending by the option's value, which for both pickers is the row's id -- the number the
     * console shows beside every author and category, and the one a category is cited by from the
     * landing-page layout.
     *
     * parseFloat, not a string comparison: "10" sorts before "2" lexicographically. A value that is
     * not a number sinks to the bottom rather than landing anywhere in particular, and the text is
     * the tie-break, so the comparator is total and the order is the same on every render. Compared
     * by code point, deliberately not through localeCompare -- the console runs in four languages,
     * and an ordering that depends on the browser's collation is the trap MgmtSort refuses a
     * Collator for.
     */
    function byId(a, b) {
        var x = parseFloat(a.value);
        var y = parseFloat(b.value);
        var xBad = isNaN(x);
        var yBad = isNaN(y);
        if (xBad !== yBad) {
            return xBad ? 1 : -1;
        }
        if (!xBad && x !== y) {
            return x - y;
        }
        var at = a.textContent;
        var bt = b.textContent;
        return at < bt ? -1 : (at > bt ? 1 : 0);
    }

    /** The text of the <label for> that names a control, without any trailing <span class="help">. */
    function labelTextOf(control) {
        var label = (control.labels && control.labels[0])
                || (control.id ? document.querySelector('label[for="' + control.id + '"]') : null);
        if (!label) {
            return '';
        }
        var first = label.firstChild;
        return (first && first.nodeType === 3 ? first.textContent : label.textContent).trim();
    }

    /*
     * The scrim behind an expanded panel.
     *
     * Created and removed rather than rendered and toggled, so there is no [hidden] element with a
     * `display` of its own to trip over -- the failure the guard block at the foot of mgmt.css
     * documents. Escape closes, as it does everywhere else here; the filter's own keydown handler
     * only swallows Escape while it has a term to clear, so the second press reaches this one.
     *
     * The page behind is deliberately NOT scroll-locked. `overflow: hidden` on <body> removes the
     * vertical scrollbar, and wherever that scrollbar takes up layout width -- classic scrollbars
     * rather than the overlay ones a Mac and a headless Chrome both default to -- the viewport
     * widens by ~15px on open and narrows again on close, shifting the whole page sideways and
     * carrying the fixed sidebar and save bar with it. That is the "page jumps under the operator"
     * failure the slot's min-height exists to prevent, arriving through a different door and
     * invisible in exactly the configuration one would test in. The panel is `position: fixed`, so
     * a page that scrolls behind the scrim does not move it; a little blurred movement behind is
     * the cheaper of the two prices.
     */
    function closeAllOverlays() {
        overlays.forEach(function (entry) {
            entry.setFull(false);
        });
    }

    function openScrim() {
        if (!scrim) {
            scrim = document.createElement('div');
            scrim.className = 'picker-scrim';
            scrim.addEventListener('click', closeAllOverlays);
            document.body.appendChild(scrim);
        }
    }

    function closeScrim() {
        if (scrim) {
            scrim.parentNode.removeChild(scrim);
            scrim = null;
        }
    }

    document.addEventListener('keydown', function (event) {
        // defaultPrevented, because the filter's own handler runs first on the way up and claims
        // Escape while it still has a term to clear. Without this, one press would clear the search
        // AND shut the panel the operator had just opened to use it.
        if (event.key === 'Escape' && scrim && !event.defaultPrevented) {
            closeAllOverlays();
        }
    });

    /* ------------------------------------------------- the author ordering */

    /*
     * Shows the authors an article carries, in the order the byline prints them, and lets the
     * operator move them.
     *
     * Order is the whole point, exactly as it is for the article's images: the public site's
     * ArticleModel.getAuthorsAsText() walks the stored list, so the first row here is the name
     * that leads the byline under the title. The picker above answers "which authors"; it cannot
     * answer "in what order", because a checkbox list has no order to show.
     *
     * The mechanism is the <option> elements themselves. A <select multiple> submits its selected
     * options in TREE order, so moving a row moves the option inside #authorIds and the order
     * reaches the server with no new parameter -- which is the invariant the picker is built on
     * and which MgmtArticleService.save makes load-bearing: it writes the fields the form carries,
     * so anything posted under a second name would be silently ignored today and silently
     * authoritative tomorrow.
     *
     * That option order is also why the server sorts the list: options rendered alphabetically
     * would not merely fail to SHOW the byline's order, they would destroy it -- every save would
     * post the authors alphabetically and the document would store exactly that. See
     * MgmtArticleService.authorsForPicker.
     */
    var orderCard = document.getElementById('author-order');
    var orderSelect = document.getElementById('authorIds');
    if (orderCard && orderSelect && orderSelect.multiple) {
        buildOrderList(orderSelect, orderCard);
    }

    function buildOrderList(select, card) {
        var list = card.querySelector('#author-order-list');
        var empty = card.querySelector('#author-order-empty');
        if (!list || !empty) {
            return;
        }

        /*
         * The operator's order, held as option elements rather than derived from the rendered rows.
         * An author ticked in the picker is selected before it has a row here, so reading the DOM
         * back would lose it -- and the rows are rebuilt on every change anyway.
         */
        var order = [];

        // Set while this card is the one making the change, so the `change` it fires for the dirty
        // tracker and the picker does not come straight back in and rebuild the rows underneath the
        // button the operator just clicked.
        var announcing = false;

        sync();
        render();
        card.hidden = false;

        select.addEventListener('change', function () {
            if (announcing) {
                return;
            }
            sync();
            render();
        });

        /* Brings `order` in line with the selection: what was unticked drops out, what was newly
           ticked goes to the end -- which is where a name the operator has just added belongs. */
        function sync() {
            order = order.filter(function (option) {
                return option.selected;
            });

            Array.prototype.forEach.call(select.options, function (option) {
                if (option.selected && order.indexOf(option) === -1) {
                    order.push(option);
                }
            });

            reorderOptions();
        }

        /*
         * Moves the selected options to the front of the <select>, in the operator's order.
         *
         * This is the whole mechanism; everything above it is presentation. Backwards, so each
         * insertBefore lands in front of the one placed before it.
         */
        function reorderOptions() {
            for (var i = order.length - 1; i >= 0; i--) {
                select.insertBefore(order[i], select.firstChild);
            }
        }

        function render() {
            list.textContent = '';
            order.forEach(function (option, index) {
                list.appendChild(buildRow(option, index));
            });
            empty.hidden = order.length > 0;
        }

        function buildRow(option, index) {
            var row = document.createElement('li');
            row.className = 'order-row';

            var position = document.createElement('span');
            position.className = 'order-pos';
            // The lead author is the byline's first name, so the row says so rather than showing
            // a 1 that has to be interpreted.
            position.textContent = index === 0 ? t('order.first') : String(index + 1);

            var name = document.createElement('span');
            name.className = 'order-name';
            name.textContent = option.textContent.trim();
            name.title = name.textContent;

            var actions = document.createElement('div');
            actions.className = 'order-actions';
            actions.appendChild(moveButton('up', index, index === 0));
            actions.appendChild(moveButton('down', index, index === order.length - 1));
            actions.appendChild(removeButton(option));

            row.appendChild(position);
            row.appendChild(name);
            row.appendChild(actions);
            return row;
        }

        function moveButton(direction, index, disabled) {
            var up = direction === 'up';
            var button = control(up ? '#i-arrow-up' : '#i-arrow-down',
                    up ? t('common.up') : t('common.down'));
            button.setAttribute('data-move', direction);
            button.disabled = !!disabled;
            button.addEventListener('click', function () {
                move(index, up ? -1 : 1, direction);
            });
            return button;
        }

        function removeButton(option) {
            var button = control(null, t('order.remove'));
            button.className += ' order-remove';
            button.addEventListener('click', function () {
                option.selected = false;
                sync();
                render();
                announce();
            });
            return button;
        }

        /* type="button" explicitly: createElement('button') defaults to submit, and although this
           card sits outside <form id="article-form"> today, the save bar's form= attribute is the
           standing proof that "outside the form" is not the same as "cannot submit it". */
        function control(icon, label) {
            var button = document.createElement('button');
            button.type = 'button';
            button.className = 'btn btn-icon btn-sm';
            button.title = label;

            if (icon) {
                button.innerHTML = '<svg class="i"><use href="' + icon + '"></use></svg>';
            } else {
                var glyph = document.createElement('span');
                glyph.setAttribute('aria-hidden', 'true');
                glyph.textContent = '×';
                button.appendChild(glyph);
            }

            var sr = document.createElement('span');
            sr.className = 'sr-only';
            sr.textContent = label;
            button.appendChild(sr);

            return button;
        }

        function move(index, delta, direction) {
            var target = index + delta;
            if (target < 0 || target >= order.length) {
                return;
            }

            order.splice(target, 0, order.splice(index, 1)[0]);
            reorderOptions();
            render();
            refocus(target, direction);
            announce();
        }

        /* The row the operator clicked has just been replaced, so the focus went with it. Put it
           back on the same control at the name's new position -- otherwise moving an author three
           places from the keyboard takes three trips through the tab order. */
        function refocus(index, direction) {
            var row = list.children[index];
            if (!row) {
                return;
            }
            var button = row.querySelector('[data-move="' + direction + '"]');
            // At either end that button is disabled and cannot take focus; the opposite one can.
            if (!button || button.disabled) {
                button = row.querySelector('[data-move]:not([disabled])');
            }
            if (button) {
                button.focus();
            }
        }

        /* One `change` on the <select>, which is what the dirty tracker listens for and what
           repaints the picker's checkboxes. Guarded, because this card is also listening. */
        function announce() {
            announcing = true;
            select.dispatchEvent(new Event('change', {bubbles: true}));
            announcing = false;
        }
    }

    /* ------------------------------------------------------ rich-text body */

    var textarea = document.getElementById('text');
    if (textarea && window.tinymce) {
        initEditor(textarea);
    }

    /**
     * TinyMCE 8, vendored under /mgmt-assets/tinymce/ (see VENDORED.md there).
     *
     * Almost every option below exists to STOP TinyMCE doing something by default. It parses HTML
     * into a schema and re-serializes it, so an unconfigured editor rewrites the article the
     * moment it opens it -- and stored bodies, imported HTML above all, are exactly what its
     * defaults rewrite: inline styles, Word `Mso*` classes and `<o:p>` tags, `<style>` blocks, IE
     * conditional comments, `<font>`, bare text.
     *
     * Each setting is load-bearing. Removing one does not degrade the editor; it silently
     * rewrites every stored body it touches. The cost of reverting a default is noted beside it.
     */
    function initEditor(el) {
        var original = el.value;
        // The server renders data-ai-assist="true" only where AI is available to this person.
        var aiAssist = el.getAttribute('data-ai-assist') === 'true';

        var config = {
            target: el,

            // Mandatory since TinyMCE 7. Without it the editor force-disables itself with a
            // console error and an in-editor warning. Verified against the 8.8.2 source: GPL mode
            // makes no network call, shows no banner and logs nothing.
            license_key: 'gpl',

            /* ---------------------------------------------------------- UI */
            plugins: 'code table lists advlist link image media charmap searchreplace ' +
                     'visualblocks visualchars wordcount fullscreen preview anchor autolink ' +
                     'directionality nonbreaking pagebreak insertdatetime codesample importcss',
            toolbar: 'undo redo | blocks | bold italic underline strikethrough | ' +
                     'forecolor backcolor | alignleft aligncenter alignright alignjustify | ' +
                     'bullist numlist outdent indent | link image media table codesample | ' +
                     'charmap searchreplace | visualblocks | code preview fullscreen',
            menubar: 'edit view insert format table',
            // A code sample is stored as <pre class="language-java"><code>…</code></pre>: the class on
            // the <pre> alone, the code as escaped text. The plugin takes over only a <pre> whose
            // own class names a language: it highlights it inside the editor and, on save, empties
            // it and puts the bare text back in a fresh <code>, so a class on that <code> is dropped
            // (and the fidelity check reports it). A <pre><code class="language-…"> -- Markdown's
            // shape -- is left as it is: kept and coloured on the site, but a plain <pre> here,
            // which the code sample dialog does not open. '*[*]' below keeps the classes.
            // The site highlights these names with its own Prism (site-assets/vendor/prism/), and
            // HtmlSafety.body keeps the class in AI answers (core.design.CodeBlocks). The values
            // are Prism's names; the editor's bundled Prism colours only java and python of them,
            // and shows the rest as plain text.
            codesample_languages: [
                {text: 'Shell', value: 'bash'},
                {text: 'YAML', value: 'yaml'},
                {text: 'JSON', value: 'json'},
                {text: 'TOML', value: 'toml'},
                {text: 'Rust', value: 'rust'},
                {text: 'Java', value: 'java'},
                {text: 'Python', value: 'python'},
                {text: 'TypeScript', value: 'typescript'},
                {text: 'SQL', value: 'sql'},
                {text: t('editor.codePlainText'), value: 'plaintext'}
            ],
            height: 620,
            // Attribution is "appreciated" rather than required under the GPL terms; the console
            // is an internal tool with no room for an upsell button.
            branding: false,
            promotion: false,
            skin: darkMode() ? 'oxide-dark' : 'oxide',
            // The body's styled blocks (body-blocks.css, linked by the head fragment) drawn in
            // the editor as on the site. An array: the editor's own sheet stays first.
            content_css: bodyBlocksCss() ? [darkMode() ? 'dark' : 'default', bodyBlocksCss()]
                                         : (darkMode() ? 'dark' : 'default'),
            // importcss would offer every class of every content_css file in the Format menu;
            // the blocks have their own menu (registerBodyBlocks), so it reads what it read before.
            importcss_file_filter: function (href) {
                return href.indexOf('body-blocks.css') === -1;
            },

            /* ------------------------------------------- schema: allow it all */
            // '*[*]' is every element with every attribute. Identical in effect to
            // verify_html:false; spelled this way because it says what it means.
            valid_elements: '*[*]',
            // <style> is not a legal <body> child under the html5 schema, and older bodies carry
            // one inline. This is also the precondition for TinyMCE's own <style>-preservation
            // workaround, which only runs when the schema treats the element as valid.
            valid_children: '+body[style],+body[link],+body[meta]',
            // Word and LibreOffice namespaced tags. '~' means "treat as inline", so they are not
            // wrapped in block elements.
            custom_elements: '~o:p,~o:smarttagtype,~w:lsdexception,~w:worddocument,' +
                             '~w:latentstyles,~v:shapetype,~v:shape,~v:imagedata,~st1:place',
            schema: 'html5',
            element_format: 'html',

            /* ----------------------------------------- Word conditional comments */
            // Default false does not strip `<!--[if ...`, it PADS it with a leading space, which
            // is still a diff. Needed in both sanitizer modes.
            allow_conditional_comments: true,
            // New in v8: DOMPurify 3.2.6 runs with SAFE_FOR_XML, which DELETES any comment whose
            // body contains markup -- i.e. exactly `<!--[if gte mso 10]><style>...<![endif]-->`.
            // This encodes the comment before the sanitizer sees it.
            allow_html_in_comments: true,

            /* ------------------------------------------------------------ URLs */
            // Default true would rewrite absolute image sources in stored bodies
            // (https://www.example.com/...) to relative ones.
            convert_urls: false,

            /* ------------------------------------------------------- serializer */
            // THE important one. Default true re-indents around every block element, which is a
            // diff on every body rather than an edge case.
            indent: false,
            remove_trailing_brs: false,
            // Together these stop <font> -> <span style> and <strike> -> <s>.
            // Both are flagged deprecated upstream but emit no warning in 8.x.
            inline_styles: false,
            convert_fonts_to_spans: false,
            // 'named' keeps the named entities (&nbsp;, &ndash;) a stored body already uses, so it
            // is the choice that rewrites the least; 'raw' rewrites every one of them. 'numeric'
            // would turn every non-ASCII letter, Cyrillic included, into &#1040; -- never use it.
            entity_encoding: 'named',
            // Cannot be disabled in v8; false/'' are rejected by the option processor. A body that
            // begins with bare text or an inline element gains a <p> wrapper on open. That residue
            // is what the fidelity check below exists to surface.
            forced_root_block: 'p',

            /* ----------------------------------------------------------- embeds */
            // Leave the YouTube iframes stored bodies carry exactly as they are.
            convert_unsafe_embeds: false,
            sandbox_iframes: false,

            // No upload adapter, deliberately: body images are URL-referenced, and
            // article images go through POST /mgmt/articles/{id}/images with its 600x400 rule.
            // A pasted screenshot would otherwise be inlined as base64 into a 6 KB document.
            paste_data_images: false,

            setup: function (editor) {
                editor.on('init', function () {
                    reportFidelity(editor, original);
                });

                if (aiAssist) {
                    registerAssist(editor);
                }
                registerBodyBlocks(editor);

                /*
                 * Dropping a thumbnail from the images card or the library into the body.
                 *
                 * The editor is an iframe, so a listener on the outer document never sees this;
                 * it has to be registered here. Only drags that carry our own type are claimed --
                 * anything else (a file, a selection, a link) falls through to TinyMCE's default
                 * handling untouched.
                 *
                 * What gets inserted is a URL, never data. paste_data_images is false and there
                 * is still no upload adapter: body images have always been URL-referenced, and
                 * convert_urls: false above is what keeps the path verbatim.
                 *
                 * The drag also carries text/html, so if TinyMCE's own drop handling ever runs
                 * ahead of this one the editor inserts the same <img> itself -- the schema is
                 * permissive enough to leave it alone. Neither route is the mechanism, though:
                 * the button on every thumbnail is, because dragging is unreachable from a
                 * keyboard and unusable on a touch screen.
                 */
                editor.on('drop', function (event) {
                    var transfer = event.dataTransfer;
                    var path = transfer && transfer.getData(IMAGE_DRAG_TYPE);

                    if (!path) {
                        return;
                    }

                    event.preventDefault();

                    // Insert where it was dropped, not wherever the caret happened to be.
                    // Two spellings of the same thing; neither browser implements both.
                    var doc = editor.getDoc();
                    var range = null;
                    if (doc.caretRangeFromPoint) {
                        range = doc.caretRangeFromPoint(event.clientX, event.clientY);
                    } else if (doc.caretPositionFromPoint) {
                        var position = doc.caretPositionFromPoint(event.clientX, event.clientY);
                        if (position) {
                            range = doc.createRange();
                            range.setStart(position.offsetNode, position.offset);
                            range.collapse(true);
                        }
                    }
                    if (range) {
                        editor.selection.setRng(range);
                    }

                    editor.insertContent(imageTag(path));
                });

                // Ctrl/Cmd+S from inside the editor. The document-level binding below cannot see
                // keystrokes that happen in the editor's iframe, and that is where the operator
                // spends the whole session.
                editor.addShortcut('meta+s', t('editor.saveShortcut'), function () {
                    submitArticleForm();
                });

                /*
                 * The fullscreen button beside the Текст label.
                 *
                 * Nothing here implements fullscreen: the vendored `fullscreen` plugin does, it is
                 * already in the toolbar string and in the View menu, and it registers
                 * Ctrl/Cmd+Shift+F itself. What this adds is a labelled control next to the field,
                 * because at the editor's real width TinyMCE keeps one toolbar row and puts the
                 * tail of it behind a `...` overflow button -- and `fullscreen`, being last in that
                 * string, is inside the overflow with no ⛶ visible anywhere on screen.
                 *
                 * The label follows the plugin's own FullscreenStateChanged event rather than a
                 * local flag, so this button and the toolbar toggle cannot disagree about the
                 * state -- including when the operator leaves fullscreen with Escape or the
                 * shortcut. Ctrl+S keeps working in fullscreen through the binding above.
                 */
                editor.on('init', function () {
                    var button = document.getElementById('editor-fullscreen');
                    if (!button) {
                        return;
                    }
                    button.hidden = false;
                    button.addEventListener('click', function () {
                        editor.execCommand('mceFullScreen');
                        editor.focus();
                    });
                });

                editor.on('FullscreenStateChanged', function (event) {
                    paintEditorFullscreenButton(event.state);
                });
            }
        };

        // The editor's own UI follows the console's language: bg-BG, de or ru, vendored under
        // tinymce/langs/. English is built into TinyMCE and has no pack, so the head fragment
        // writes no data-tinymce for it and `language` is then left unset rather than ''.
        var i18n = i18nElement();
        var editorLanguage = i18n ? i18n.getAttribute('data-tinymce') : null;
        if (editorLanguage) {
            config.language = editorLanguage;
        }

        // Beside the block-format list, so it is on screen rather than in the ••• overflow.
        config.toolbar = config.toolbar.replace('| blocks |', '| blocks bodyblocks |');
        if (aiAssist) {
            // First, not last: at the editor's real width TinyMCE folds the end of the toolbar
            // into its ••• overflow, where the button would be invisible until looked for.
            config.toolbar = 'aiassist | ' + config.toolbar;
        }

        tinymce.init(config);
    }

    function bodyBlocksCss() {
        var link = document.querySelector('link[data-body-blocks]');
        return link ? link.href : null;
    }

    /*
     * The body's styled blocks (BodyBlocks in core, styled by the site's site.css) applied by hand:
     * a toolbar menu toggling one TinyMCE format per block. Wrappers (callout, key facts, two
     * columns, pull quote) go round the selected blocks; the lead is a class on the paragraph.
     * Formats are registered at init, never through the `formats` option -- which would also be
     * read as replacing defaults -- and no serialisation option changes for them.
     */
    function bodyBlockFormats() {
        return [
            {name: 'body_lead', text: t('editor.block.lead'), format: {selector: 'p', classes: 'body-lead'}},
            {name: 'body_callout', text: t('editor.block.callout'), format: {block: 'div', classes: 'body-callout', wrapper: true, remove: 'all'}},
            {name: 'body_pullquote', text: t('editor.block.pullquote'), format: {block: 'blockquote', classes: 'body-pullquote', wrapper: true, remove: 'all'}},
            {name: 'body_facts', text: t('editor.block.facts'), format: {block: 'div', classes: 'body-facts', wrapper: true, remove: 'all'}},
            {name: 'body_columns', text: t('editor.block.columns'), format: {block: 'div', classes: 'body-columns', wrapper: true, remove: 'all'}}
        ];
    }

    function registerBodyBlocks(editor) {
        var blocks = bodyBlockFormats();
        editor.on('init', function () {
            blocks.forEach(function (block) {
                editor.formatter.register(block.name, block.format);
            });
        });
        editor.ui.registry.addMenuButton('bodyblocks', {
            text: t('editor.blocks'),
            tooltip: t('editor.blocks.tooltip'),
            fetch: function (callback) {
                callback(blocks.map(function (block) {
                    return {
                        type: 'togglemenuitem',
                        text: block.text,
                        onAction: function () {
                            editor.undoManager.transact(function () {
                                editor.formatter.toggle(block.name);
                            });
                            editor.nodeChanged();
                        },
                        onSetup: function (api) {
                            api.setActive(editor.formatter.match(block.name));
                            return function () {};
                        }
                    };
                }));
            }
        });
    }

    /* ------------------------------------------------------ writing help (AI)
     *
     * A toolbar button that opens a dialog. With a selection: the selection reworked one of six
     * ways (POST /mgmt/ai/assist) or laid out in headings, lists and the body's styled blocks
     * (/mgmt/ai/design-body, every word kept). With none: a passage written from a brief at the
     * cursor (/mgmt/ai/write) or the whole text laid out. The answer -- markup the server has
     * already cleaned to a small allow-list -- shows in the dialog and goes into the editor only on
     * Insert. Nothing is saved: the article is still the operator's to save, and the
     * unsaved-changes guard sees the insert like any edit. A fetch, never a form, because the
     * editor holds unsaved rich text.
     */
    // Literal keys, each one: MgmtJsTextTest finds a key only where t('…') spells it out.
    function assistActions() {
        return [
            {value: 'improve', text: t('ai.assist.action.improve')},
            {value: 'shorten', text: t('ai.assist.action.shorten')},
            {value: 'expand', text: t('ai.assist.action.expand')},
            {value: 'fix', text: t('ai.assist.action.fix')},
            {value: 'formal', text: t('ai.assist.action.formal')},
            {value: 'friendly', text: t('ai.assist.action.friendly')},
            {value: 'design', text: t('ai.assist.action.design')}
        ];
    }

    function assistEmptyActions() {
        return [
            {value: 'write', text: t('ai.assist.action.write')},
            {value: 'designAll', text: t('ai.assist.action.designAll')}
        ];
    }

    function registerAssist(editor) {
        editor.ui.registry.addButton('aiassist', {
            text: t('ai.assist'),
            tooltip: t('ai.assist.tooltip'),
            onAction: function () {
                openAssist(editor);
            }
        });
    }

    function escapeHtml(text) {
        return String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    function hasText(html) {
        return !!html && !!html.replace(/<[^>]*>/g, '').replace(/&nbsp;/g, ' ').trim();
    }

    /* Where an action is sent and with what: the endpoint under MGMT and the JSON body. */
    function assistRequest(editor, action, passage, brief) {
        if (action === 'design') {
            return {path: '/ai/design-body', body: {html: passage}};
        }
        if (action === 'designAll') {
            return {path: '/ai/design-body', body: {html: editor.getContent()}};
        }
        if (action === 'write') {
            return {path: '/ai/write', body: {brief: brief, html: editor.getContent()}};
        }
        return {path: '/ai/assist', body: {action: action, html: passage}};
    }

    function assistResultNote(action) {
        if (action === 'write') {
            return t('ai.assist.result.cursor');
        }
        if (action === 'designAll') {
            return t('ai.assist.result.all');
        }
        return t('ai.assist.result');
    }

    function openAssist(editor) {
        var passage = editor.selection.getContent();
        var selected = hasText(passage);
        if (!selected) {
            // Nothing with text is selected, but an image may be: a passage written for the
            // cursor must go beside it, never in its place.
            editor.selection.collapse(false);
        }
        var bookmark = editor.selection.getBookmark(2, true);
        var state = {action: selected ? 'improve' : 'write', brief: '', result: null};

        function items(previewHtml) {
            var list = [{
                type: 'selectbox', name: 'action', label: t('ai.assist.action'),
                items: selected ? assistActions() : assistEmptyActions()
            }];
            if (!selected) {
                list.push({type: 'textarea', name: 'brief', label: t('ai.assist.brief'),
                    placeholder: t('ai.assist.brief.example')});
            }
            list.push({type: 'htmlpanel', html: previewHtml});
            return list;
        }

        function spec(previewHtml) {
            return {
                title: t('ai.assist.title'),
                size: 'medium',
                body: {type: 'panel', items: items(previewHtml)},
                initialData: selected ? {action: state.action} : {action: state.action, brief: state.brief},
                buttons: [
                    {type: 'custom', name: 'ask', text: t('ai.assist.ask'), primary: state.result === null},
                    // TinyMCE translates its own 'Cancel' through the editor's language pack.
                    {type: 'cancel', text: 'Cancel'},
                    {type: 'submit', name: 'insert', text: t('ai.assist.insert'), primary: state.result !== null,
                        enabled: state.result !== null}
                ],
                onChange: function (api, details) {
                    // A changed choice or brief makes the answer shown stale: Insert waits for Ask.
                    if (state.result !== null && (details.name === 'action' || details.name === 'brief')) {
                        state.action = api.getData().action;
                        state.brief = api.getData().brief || '';
                        state.result = null;
                        api.redial(spec('<p>' + escapeHtml(intro()) + '</p>'));
                    }
                },
                onAction: function (api, details) {
                    if (details.name !== 'ask') {
                        return;
                    }
                    var data = api.getData();
                    state.action = data.action;
                    state.brief = data.brief || '';
                    if (state.action === 'write' && !state.brief.trim()) {
                        editor.notificationManager.open({text: t('ai.assist.brief.missing'), type: 'info', timeout: 4000});
                        return;
                    }
                    if (state.action === 'designAll' && !hasText(editor.getContent())) {
                        editor.notificationManager.open({text: t('ai.assist.empty'), type: 'info', timeout: 4000});
                        return;
                    }
                    var request = assistRequest(editor, state.action, passage, state.brief);
                    api.block(t('ai.assist.working'));
                    var headers = window.mgmtCsrf();
                    headers['Content-Type'] = 'application/json';
                    headers['Accept'] = 'application/json';
                    fetch(MGMT + request.path, {
                        method: 'POST', credentials: 'same-origin', headers: headers,
                        body: JSON.stringify(request.body)
                    })
                        .then(readJson)
                        .then(function (answer) {
                            state.result = answer.html || '';
                            // Already cleaned by the server (HtmlSafety); shown as it will be
                            // inserted, the body's blocks styled by body-blocks.css. Above it, the
                            // numbers and names the material did not have (Grounding): a warning,
                            // never a refusal -- Insert stays the operator's.
                            var unverified = answer.unverified || [];
                            api.redial(spec('<p>' + escapeHtml(assistResultNote(state.action)) + '</p>'
                                    + (unverified.length ? '<p class="ai-unverified">'
                                        + escapeHtml(t('ai.unverified', unverified.join(', '))) + '</p>' : '')
                                    + '<div class="ai-preview">' + state.result + '</div>'));
                        })
                        .catch(function (error) {
                            api.unblock();
                            editor.notificationManager.open({text: error.message, type: 'error', timeout: 6000});
                        });
                },
                onSubmit: function (api) {
                    if (state.result !== null) {
                        var result = state.result;
                        var action = state.action;
                        editor.undoManager.transact(function () {
                            if (action === 'designAll') {
                                editor.setContent(result);
                            } else {
                                editor.selection.moveToBookmark(bookmark);
                                if (action === 'write') {
                                    editor.insertContent(result);
                                } else {
                                    editor.selection.setContent(result);
                                }
                            }
                        });
                        editor.setDirty(true);
                        editor.nodeChanged();
                    }
                    api.close();
                }
            };
        }

        function intro() {
            return selected ? t('ai.assist.intro') : t('ai.assist.intro.empty');
        }

        editor.windowManager.open(spec('<p>' + escapeHtml(intro()) + '</p>'));
    }

    /*
     * "Suggest titles" under the title: POST /mgmt/ai/titles with the title and body as they
     * stand in the editor, unsaved; the candidates as buttons, one click putting one in the field
     * (and an input event, for the unsaved-changes guard). The buttons have no name and are
     * type="button": they sit inside #article-form and must post nothing.
     */
    Array.prototype.forEach.call(document.querySelectorAll('[data-ai-titles]'), function (button) {
        var field = document.getElementById('title');
        var note = button.parentNode.querySelector('[data-ai-note]');
        var list = button.parentNode.parentNode.querySelector('[data-ai-title-list]');
        if (!field || !list || !window.fetch) {
            return;
        }
        button.hidden = false;
        button.addEventListener('click', function () {
            var headers = window.mgmtCsrf();
            headers['Content-Type'] = 'application/json';
            headers['Accept'] = 'application/json';
            var editor = window.tinymce && tinymce.activeEditor;
            var body = editor && !editor.removed ? editor.getContent() : (document.getElementById('text') || {}).value;
            button.disabled = true;
            note.textContent = t('ai.suggest.working');
            list.textContent = '';
            list.hidden = true;
            fetch(MGMT + '/ai/titles', {
                method: 'POST', credentials: 'same-origin', headers: headers,
                body: JSON.stringify({title: field.value, html: body || ''})
            })
                .then(readJson)
                .then(function (data) {
                    var titles = data.titles || [];
                    if (!titles.length) {
                        note.textContent = t('ai.titles.none');
                        return;
                    }
                    note.textContent = t('ai.titles.pick');
                    titles.forEach(function (title) {
                        var choice = document.createElement('button');
                        choice.type = 'button';
                        choice.className = 'btn btn-sm btn-ghost ai-title-choice';
                        choice.textContent = title;
                        choice.addEventListener('click', function () {
                            field.value = title;
                            field.dispatchEvent(new Event('input', {bubbles: true}));
                            note.textContent = t('ai.suggest.done');
                            list.textContent = '';
                            list.hidden = true;
                        });
                        list.appendChild(choice);
                    });
                    list.hidden = false;
                })
                .catch(function (error) {
                    note.textContent = error.message;
                })
                .then(function () {
                    button.disabled = false;
                });
        });
    });

    /*
     * "Draft with AI": a card of its own, outside #article-form (nothing in it may be bound by
     * save()), rendered hidden. POST /mgmt/ai/draft with the brief and a length; the answer fills
     * the title, the description and the body -- after a confirm when any of them holds text --
     * and an input event arms the unsaved-changes guard. Saving is the operator's.
     */
    Array.prototype.forEach.call(document.querySelectorAll('[data-ai-draft]'), function (card) {
        var brief = card.querySelector('[data-ai-draft-brief]');
        var length = card.querySelector('[data-ai-draft-length]');
        var button = card.querySelector('[data-ai-draft-make]');
        var note = card.querySelector('[data-ai-note]');
        var title = document.getElementById('title');
        var description = document.getElementById('description');
        if (!brief || !length || !button || !title || !description || !window.fetch) {
            return;
        }
        card.hidden = false;
        button.addEventListener('click', function () {
            if (!brief.value.trim()) {
                note.textContent = t('ai.assist.brief.missing');
                brief.focus();
                return;
            }
            var editor = window.tinymce && tinymce.activeEditor;
            var live = editor && !editor.removed;
            var body = live ? editor.getContent() : (document.getElementById('text') || {}).value;
            if ((title.value.trim() || description.value.trim() || hasText(body)) && !window.confirm(t('ai.draft.replace'))) {
                return;
            }
            var headers = window.mgmtCsrf();
            headers['Content-Type'] = 'application/json';
            headers['Accept'] = 'application/json';
            button.disabled = true;
            note.textContent = t('ai.draft.working');
            fetch(MGMT + '/ai/draft', {
                method: 'POST', credentials: 'same-origin', headers: headers,
                body: JSON.stringify({brief: brief.value, length: length.value})
            })
                .then(readJson)
                .then(function (data) {
                    title.value = data.title || '';
                    description.value = data.description || '';
                    if (live) {
                        editor.undoManager.transact(function () {
                            editor.setContent(data.html || '');
                        });
                        editor.setDirty(true);
                    } else if (document.getElementById('text')) {
                        document.getElementById('text').value = data.html || '';
                    }
                    title.dispatchEvent(new Event('input', {bubbles: true}));
                    description.dispatchEvent(new Event('input', {bubbles: true}));
                    // What the brief did not give (Grounding), named for the author to check.
                    var unverified = data.unverified || [];
                    note.textContent = unverified.length
                        ? t('ai.draft.done') + ' ' + t('ai.unverified', unverified.join(', '))
                        : t('ai.draft.done');
                })
                .catch(function (error) {
                    note.textContent = error.message;
                })
                .then(function () {
                    button.disabled = false;
                });
        });
    });

    /*
     * Restoring a website or an organisation (site-restore.html, organisation-restore.html): the
     * archive goes as the request body itself, application/zip, to the form's action with the
     * form's other fields in the query string -- never multipart, whose 4 MB ceiling is every other
     * upload's. The answer is JSON: where to go (the server filed the flash for that address), or
     * why not. The file input has no name, so a submit that slipped past this posts no file.
     */
    Array.prototype.forEach.call(document.querySelectorAll('form[data-archive-restore]'), function (form) {
        var input = form.querySelector('[data-archive-file]');
        var note = form.querySelector('[data-archive-note]');
        var button = form.querySelector('button[type="submit"]');
        if (!input || !window.fetch) {
            return;
        }
        form.addEventListener('submit', function (event) {
            event.preventDefault();
            var file = input.files && input.files[0];
            if (!file) {
                note.textContent = t('archive.choose');
                return;
            }
            var query = [];
            Array.prototype.forEach.call(form.elements, function (field) {
                if (field.name && field !== input) {
                    query.push(encodeURIComponent(field.name) + '=' + encodeURIComponent(field.value));
                }
            });
            var headers = window.mgmtCsrf();
            headers['Content-Type'] = 'application/zip';
            headers['Accept'] = 'application/json';
            button.disabled = true;
            note.textContent = t('archive.working');
            fetch(form.action + (query.length ? '?' + query.join('&') : ''), {
                method: 'POST', credentials: 'same-origin', headers: headers, body: file
            })
                .then(readJson)
                .then(function (data) {
                    window.location.assign(data.location);
                })
                .catch(function (error) {
                    note.textContent = error.message;
                    button.disabled = false;
                });
        });
    });

    /*
     * The suggest buttons under the description and the tags: a fetch to /mgmt/ai/describe or
     * /mgmt/ai/tags, the answer put into the field -- the description replaced, tags added where
     * absent -- and an input event, so the unsaved-changes guard knows. Rendered hidden: with
     * scripting off they would be dead.
     */
    /* The image screen's alt text, per language: the same shape, one button per field. */
    Array.prototype.forEach.call(document.querySelectorAll('[data-ai-alt]'), function (button) {
        var lang = button.getAttribute('data-lang');
        var field = document.getElementById('alt-' + lang);
        var note = button.parentNode.querySelector('[data-ai-note]');
        if (!field || !window.fetch) {
            return;
        }
        button.hidden = false;
        button.addEventListener('click', function () {
            var headers = window.mgmtCsrf();
            headers['Content-Type'] = 'application/json';
            headers['Accept'] = 'application/json';
            button.disabled = true;
            note.textContent = t('ai.suggest.working');
            fetch(MGMT + '/ai/alt', {
                method: 'POST', credentials: 'same-origin', headers: headers,
                body: JSON.stringify({imageId: button.getAttribute('data-image'), lang: lang})
            })
                .then(readJson)
                .then(function (data) {
                    field.value = data.alt || '';
                    note.textContent = t('ai.suggest.done');
                    field.dispatchEvent(new Event('input', {bubbles: true}));
                })
                .catch(function (error) {
                    note.textContent = error.message;
                })
                .then(function () {
                    button.disabled = false;
                });
        });
    });

    Array.prototype.forEach.call(document.querySelectorAll('[data-ai-describe], [data-ai-tags]'), function (button) {
        var tags = button.hasAttribute('data-ai-tags');
        var field = document.getElementById(tags ? 'tags' : 'description');
        var note = button.parentNode.querySelector('[data-ai-note]');
        if (!field || !window.fetch) {
            return;
        }
        button.hidden = false;
        button.addEventListener('click', function () {
            var headers = window.mgmtCsrf();
            headers['Content-Type'] = 'application/json';
            headers['Accept'] = 'application/json';
            button.disabled = true;
            note.textContent = t('ai.suggest.working');
            fetch(MGMT + (tags ? '/ai/tags' : '/ai/describe'), {
                method: 'POST', credentials: 'same-origin', headers: headers,
                body: JSON.stringify({articleId: button.getAttribute('data-article')})
            })
                .then(readJson)
                .then(function (data) {
                    if (tags) {
                        var have = field.value.split(',').map(function (tag) {
                            return tag.trim().toLowerCase();
                        }).filter(Boolean);
                        var added = (data.tags || []).filter(function (tag) {
                            return have.indexOf(tag.toLowerCase()) === -1;
                        });
                        if (!added.length) {
                            note.textContent = t('ai.tags.none');
                            return;
                        }
                        field.value = (field.value.trim() ? field.value.trim().replace(/,\s*$/, '') + ', ' : '') + added.join(', ');
                        note.textContent = t('ai.tags.added', added.join(', '));
                    } else {
                        field.value = data.description || '';
                        note.textContent = t('ai.suggest.done');
                    }
                    field.dispatchEvent(new Event('input', {bubbles: true}));
                })
                .catch(function (error) {
                    note.textContent = error.message;
                })
                .then(function () {
                    button.disabled = false;
                });
        });
    });

    function paintEditorFullscreenButton(active) {
        var button = document.getElementById('editor-fullscreen');
        var icon = document.getElementById('editor-fullscreen-icon');
        var label = document.getElementById('editor-fullscreen-label');
        if (!button || !icon || !label) {
            return;
        }
        icon.firstElementChild.setAttribute('href', active ? '#i-minimize' : '#i-maximize');
        label.textContent = active ? t('editor.fullscreen.exit') : t('editor.fullscreen');
        button.setAttribute('title', active
                ? t('editor.fullscreen.exitTitle')
                : t('editor.fullscreen.title'));
    }

    /**
     * Reports whether merely OPENING the article changed its HTML.
     *
     * Even fully configured, TinyMCE cannot round-trip everything -- forced_root_block alone is
     * unavoidable. The failure mode we refuse to accept is the silent one, where an editor fixes a
     * typo and unknowingly rewrites markup that has been on the site for a decade. So: compare
     * what the editor would now save against what was loaded, and say so before anything is saved.
     */
    function reportFidelity(editor, original) {
        var current;
        try {
            current = editor.getContent();
        } catch (e) {
            return;
        }

        if (current === original) {
            return;
        }

        var slot = document.getElementById('fidelity-warning');
        if (!slot) {
            return;
        }

        var delta = current.length - original.length;
        var sign = delta > 0 ? '+' : '';

        slot.className = 'flash err fidelity';
        slot.innerHTML =
            '<strong>' + tHtml('fidelity.title') + '</strong> ' +
            tHtml('fidelity.delta', '<strong>' + sign + delta + '</strong>',
                    original.length, current.length) + ' ' +
            tHtml('fidelity.permanent') +
            '<details><summary>' + tHtml('fidelity.original') + '</summary>' +
            '<textarea class="fidelity-original" readonly rows="12"></textarea></details>';

        // Assigned as a value, never interpolated into innerHTML: the original body is arbitrary
        // markup and this warning must not become the one place it executes.
        slot.querySelector('.fidelity-original').value = original;
    }

    /* --------------------------------------------------- wide writing mode */

    /*
     * Folds the right-hand rail below the body so the editor gets the whole content width. Stored
     * like the theme and the sidebar, and applied by the same pre-paint snippet in the head -- here
     * that matters more than anywhere else, because TinyMCE measures its container once and would
     * otherwise be built at one width and immediately reflowed to another.
     *
     * Only the attribute is written; the layout is CSS. This runs on every screen and is inert on
     * the ones without an editor, which is why the button is what it looks for.
     */
    var EDITOR_KEY = 'pacod-mgmt-editor';

    function editorWide() {
        return document.documentElement.getAttribute('data-editor') === 'wide';
    }

    function applyEditorWide(wide) {
        var root = document.documentElement;
        if (wide) {
            root.setAttribute('data-editor', 'wide');
        } else {
            root.removeAttribute('data-editor');
        }
        try {
            localStorage.setItem(EDITOR_KEY, wide ? 'wide' : 'normal');
        } catch (e) {
            /* private mode: the choice simply does not survive the navigation */
        }
        paintEditorWideButton();

        // The container is resized by CSS; the iframe inside it is laid out by TinyMCE, and only
        // this tells it to look again.
        if (window.tinymce && tinymce.activeEditor) {
            tinymce.activeEditor.dispatch('ResizeEditor');
        }
    }

    function paintEditorWideButton() {
        var button = document.getElementById('editor-wide');
        var icon = document.getElementById('editor-wide-icon');
        var label = document.getElementById('editor-wide-label');
        if (!button || !icon || !label) {
            return;
        }
        var wide = editorWide();
        icon.firstElementChild.setAttribute('href', wide ? '#i-minimize' : '#i-maximize');
        label.textContent = wide ? t('editor.narrow') : t('editor.wide');
        button.setAttribute('aria-pressed', wide ? 'true' : 'false');
        button.setAttribute('title', wide
                ? t('editor.narrow.title')
                : t('editor.wide.title'));
    }

    (function initEditorWide() {
        var button = document.getElementById('editor-wide');
        if (!button) {
            return;
        }
        button.hidden = false;
        paintEditorWideButton();
        button.addEventListener('click', function () {
            applyEditorWide(!editorWide());
        });
    }());

    /* ---------------------------------------------------- embedded video */

    /*
     * Two views of the #embedded textarea, not a replacement for it.
     *
     * The textarea keeps its id, its name and its th:field binding throughout; the visual view
     * merely hides it and writes its value, exactly as the author/category pickers drive their
     * <select multiple>. The address box carries no name and never reaches the server.
     *
     * The invariant everything here is built around: OPENING AN ARTICLE NEVER REWRITES THE FIELD.
     * Recognition is a pure read, the address box is never prefilled from the stored value, and
     * anything the visual view does not recognise opens the HTML view byte-identical. Stored values
     * are whatever markup was pasted in, and none of it is ours to normalise.
     *
     * The mode is derived from the content on every load and is deliberately NOT a fourth
     * pacod-mgmt-* key: a remembered mode would eventually disagree with the article it describes,
     * and unlike the theme, the sidebar and the editor width it moves no layout, so it needs
     * nothing from the pre-paint snippet in the head.
     */

    var EMBED_HOST = 'https://www.youtube.com';   // the one token to change for youtube-nocookie
    var YT_ID = /^[A-Za-z0-9_-]{11}$/;

    /*
     * Deliberately naive. `[^>]*` fails on an attribute value containing '>', and that failure is
     * free: every parse failure here lands in the HTML view with the value untouched, which is the
     * safe direction. Regexes over the string rather than innerHTML on a detached node -- the
     * stored value is arbitrary operator HTML, and this must not become the one place in the
     * console that parses, runs or fetches it.
     */
    var LONE_IFRAME = /^\s*<iframe\b[^>]*>\s*<\/iframe>\s*$/i;
    var EMBED_SRC_ATTR = /\bsrc\s*=\s*["']([^"']+)["']/i;
    var EMBED_SRC = /^(?:https?:)?\/\/(?:www\.)?(?:youtube\.com|youtube-nocookie\.com)\/embed\/([A-Za-z0-9_-]{11})(?:[?#].*)?$/i;

    var EMBED_HOSTS = [
        'youtube.com', 'www.youtube.com', 'm.youtube.com', 'music.youtube.com',
        'youtube-nocookie.com', 'www.youtube-nocookie.com',
        'youtu.be', 'www.youtu.be'
    ];

    var embedField = document.getElementById('embedded');
    var embedModes = document.getElementById('embed-modes');
    var embedPane = document.getElementById('embed-visual');
    var embedUrl = document.getElementById('embed-url');
    var embedPreview = document.getElementById('embed-preview');
    var embedStatus = document.getElementById('embed-status');
    var embedNote = document.getElementById('embed-note');
    var embedHtmlHint = document.getElementById('embed-html-hint');
    var embedLabel = document.getElementById('embed-label');
    var embedClear = document.getElementById('embed-clear');
    var embedVisualButton = document.getElementById('embed-mode-visual');
    var embedHtmlButton = document.getElementById('embed-mode-html');

    var embedMode = 'visual';
    var embedPreviewSrc = null;

    /**
     * What the operator pasted -> {id, start}, or null.
     *
     * Accepts a watch URL, a youtu.be link, an /embed/, /shorts/, /live/ or /v/ path, a whole
     * <iframe> copied from YouTube's „Сподели -> Вгради", and a bare eleven-character id. Anything
     * else is null, and null never writes.
     */
    function youtubeRef(raw) {
        var candidate = String(raw || '').trim();
        if (!candidate) {
            return null;
        }

        // A pasted iframe: work from its src and forget the rest of the markup. What the operator
        // wants from that paste is the video, not the attributes YouTube happened to ship.
        if (candidate.indexOf('<') !== -1) {
            var inner = EMBED_SRC_ATTR.exec(candidate);
            if (!inner) {
                return null;
            }
            candidate = inner[1].trim();
        }

        if (YT_ID.test(candidate)) {
            return {id: candidate, start: 0};
        }

        var url;
        try {
            url = new URL(candidate, location.href);
        } catch (e) {
            return null;
        }
        if (EMBED_HOSTS.indexOf(url.hostname.toLowerCase()) === -1) {
            return null;
        }

        var parts = url.pathname.split('/').filter(Boolean);
        var id = null;
        if (url.hostname.toLowerCase().indexOf('youtu.be') !== -1) {
            id = parts[0];
        } else if (parts.length > 1 && ['embed', 'shorts', 'live', 'v'].indexOf(parts[0]) !== -1) {
            id = parts[1];
        } else if (parts[0] === 'watch') {
            id = url.searchParams.get('v');
        }

        return id && YT_ID.test(id) ? {id: id, start: embedSeconds(url)} : null;
    }

    /**
     * The start offset, as integer seconds.
     *
     * `t` comes off a „Сподели в 1:30" link as either 90 or 1m30s; `start` is already seconds.
     * The embed parameter accepts neither suffix form, so anything not reduced to a number here
     * has to be dropped -- which is why the status line says what was understood.
     */
    function embedSeconds(url) {
        var raw = url.searchParams.get('start') || url.searchParams.get('t');
        if (!raw) {
            return 0;
        }
        if (/^\d+$/.test(raw)) {
            return parseInt(raw, 10);
        }
        var parts = /^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$/.exec(raw);
        if (!parts || (!parts[1] && !parts[2] && !parts[3])) {
            return 0;
        }
        return (parseInt(parts[1] || 0, 10) * 3600)
                + (parseInt(parts[2] || 0, 10) * 60)
                + parseInt(parts[3] || 0, 10);
    }

    /**
     * A stored value -> {id, src}, or null when the visual view cannot speak for it.
     *
     * Recognised means: a lone <iframe> whose src is a YouTube /embed/ URL. Not a byte comparison
     * against what embedMarkup() writes -- that would be simpler and would drop nearly every stored
     * value into the HTML view: every one that carries width, height, title or frameborder.
     * The src is what makes a value a YouTube embed; the attributes around it are not ours.
     *
     * Pure. Nothing on the load path may touch embedField.value.
     */
    function recognisedEmbed(value) {
        if (!LONE_IFRAME.test(String(value || ''))) {
            return null;   // a wrapper div, a <script> embed, two elements, plain text
        }
        var found = EMBED_SRC_ATTR.exec(value);
        var match = found && EMBED_SRC.exec(found[1]);
        return match ? {id: match[1], src: found[1]} : null;   // Vimeo, a data: src, anything else
    }

    /*
     * YouTube's own „Сподели -> Вгради" output, attribute for attribute.
     *
     * width/height/frameborder are cosmetic on the public site -- .video-container supplies the
     * 16:9 box and `inset: 0; border: 0` -- but they are kept: they are the intrinsic-ratio
     * fallback anywhere that class is absent, and they match the embeds already stored, so the
     * field does not read as two kinds of markup. `title` is the one attribute here that is not
     * cosmetic.
     *
     * No loading="lazy". It would help the landing page, which renders one .video-container per
     * video widget, but it would reach only newly entered videos and make them differ from the
     * stored ones for a reason invisible from this screen. That belongs in a change that migrates
     * both.
     */
    function embedMarkup(ref) {
        return '<iframe width="560" height="315" src="' + EMBED_HOST + '/embed/' + ref.id
                + (ref.start ? '?start=' + ref.start : '')
                // Stored in the article and served by the public site, so it is YouTube's own
                // embed title, never the studio's UI language.
                + '" title="YouTube video player" frameborder="0"'
                + ' allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope;'
                + ' picture-in-picture; web-share"'
                + ' referrerpolicy="strict-origin-when-cross-origin" allowfullscreen></iframe>';
    }

    /** The only writer of #embedded. Nothing else in this section assigns to it. */
    function writeEmbed(value) {
        if (embedField.value === value) {
            return;   // an unchanged write must not arm the unsaved-changes guard
        }
        embedField.value = value;
        // What tells the dirty tracker an edit happened. The event bubbles out of #embed-visual's
        // data-no-dirty subtree because the textarea is a sibling of the pane, not a child of it.
        embedField.dispatchEvent(new Event('input', {bubbles: true}));
        paintEmbed();
    }

    /** mm:ss, or h:mm:ss past the hour. */
    function embedClock(total) {
        var seconds = total % 60;
        var minutes = Math.floor(total / 60) % 60;
        var hours = Math.floor(total / 3600);
        var mmss = (hours ? String(minutes).padStart(2, '0') : String(minutes))
                + ':' + String(seconds).padStart(2, '0');
        return hours ? hours + ':' + mmss : mmss;
    }

    /** Repaints every part of the field from one place, so the two views cannot disagree. */
    function paintEmbed() {
        var value = embedField.value;
        var ref = recognisedEmbed(value);
        var visual = embedMode === 'visual';

        embedPane.hidden = !visual;
        embedField.hidden = visual;
        embedClear.hidden = !visual || value.trim() === '';

        embedVisualButton.setAttribute('aria-pressed', visual ? 'true' : 'false');
        embedHtmlButton.setAttribute('aria-pressed', visual ? 'false' : 'true');

        embedHtmlHint.hidden = visual;

        // The label has to point at whatever the operator can see: in the visual view #embedded is
        // hidden, and clicking a <label for> aimed at a hidden control does nothing.
        embedLabel.setAttribute('for', visual ? 'embed-url' : 'embedded');

        embedNote.hidden = !visual || value.trim() === '' || !!ref;
        paintEmbedPreview(visual ? ref : null);
    }

    /*
     * The preview reads the src the article actually carries, never a URL rebuilt from the id.
     *
     * A stored .../embed/ID?start=90&end=120 passes recognition, and a preview rebuilt from the id
     * alone would quietly show a different video than the site renders. Same argument as
     * paintEditorFullscreenButton following the plugin's own event rather than a local flag.
     *
     * Keyed on the src, which is also what keeps an unrelated repaint from tearing down a preview
     * the operator has already pressed play on.
     */
    function paintEmbedPreview(ref) {
        var src = ref ? ref.src : null;
        if (src === embedPreviewSrc) {
            return;
        }
        embedPreviewSrc = src;
        embedPreview.textContent = '';
        if (!ref) {
            return;
        }

        // A poster image, not the player: one ~12 KB request, no player JavaScript and no YouTube
        // cookie while the operator is merely editing. hqdefault rather than maxresdefault, which
        // 404s for a large share of videos.
        var img = document.createElement('img');
        img.alt = '';
        img.addEventListener('error', function () {
            paintEmbedUnknown(ref);
        });
        img.addEventListener('load', function () {
            // YouTube answers an unknown id with a 120x90 grey placeholder rather than a 404.
            if (img.naturalWidth <= 120) {
                paintEmbedUnknown(ref);
            }
        });
        img.src = 'https://i.ytimg.com/vi/' + ref.id + '/hqdefault.jpg';
        embedPreview.appendChild(img);

        var play = document.createElement('button');
        play.type = 'button';
        play.className = 'embed-play';
        play.title = t('video.play');
        play.appendChild(icon('#i-play'));
        var label = document.createElement('span');
        label.className = 'sr-only';
        label.textContent = t('video.play');
        play.appendChild(label);
        play.addEventListener('click', function () {
            playEmbedPreview(ref);
        });
        embedPreview.appendChild(play);
    }

    function playEmbedPreview(ref) {
        embedPreview.textContent = '';
        var frame = document.createElement('iframe');
        frame.setAttribute('allow', 'autoplay; encrypted-media; picture-in-picture');
        frame.setAttribute('referrerpolicy', 'strict-origin-when-cross-origin');
        frame.setAttribute('allowfullscreen', '');
        frame.src = playableEmbedSrc(ref.src);
        embedPreview.appendChild(frame);
    }

    /*
     * For the preview only -- the stored value is not touched by any of this.
     *
     * Stored srcs are sometimes http:// or protocol-relative, and an http:// frame is refused as
     * mixed content on an https console, which would leave the preview silently blank.
     *
     * `&amp;` is decoded because the src arrives as raw attribute *text*: a stored
     * `?start=90&amp;end=120` would otherwise parse as a parameter literally named "amp;end", and
     * the preview would ignore the end time the article actually carries. One string replace, not
     * an HTML decode -- nothing here may parse the operator's markup, and `&` is the only entity a
     * URL legitimately needs.
     */
    function playableEmbedSrc(src) {
        var url;
        try {
            url = new URL(src.replace(/&amp;/g, '&'), location.href);
        } catch (e) {
            return src;
        }
        url.protocol = 'https:';
        url.searchParams.set('autoplay', '1');
        return url.href;
    }

    /*
     * Three facts, not two -- the MgmtImageService.fileOf idiom.
     *
     * An unreachable thumbnail means the console is offline, or the video is private, or the id is
     * wrong, and from here those are indistinguishable. Saying "this video does not exist" would put
     * a red lie on every article opened during an offline session.
     */
    function paintEmbedUnknown(ref) {
        embedPreview.textContent = '';

        var box = document.createElement('div');
        box.className = 'embed-unknown';

        var line = document.createElement('p');
        line.textContent = t('video.unknown');
        box.appendChild(line);

        var identity = document.createElement('p');
        identity.textContent = t('video.id') + ' ';
        var code = document.createElement('code');
        code.textContent = ref.id;
        identity.appendChild(code);
        box.appendChild(identity);

        var link = document.createElement('a');
        link.href = 'https://www.youtube.com/watch?v=' + ref.id;
        link.target = '_blank';
        link.rel = 'noopener';
        link.textContent = t('video.open');
        box.appendChild(link);

        embedPreview.appendChild(box);
    }

    /*
     * No debounce and no apply button.
     *
     * The parse is a regex over a short string, so there is nothing to amortise -- and a deferred
     * write is exactly the „I pasted it, I pressed Ctrl+S, there is no video" failure. A control
     * that needs a second gesture to take effect is a control operators forget to press.
     */
    function readEmbedAddress() {
        var raw = embedUrl.value;
        if (raw.trim() === '') {
            note(embedStatus, '', false);
            return;
        }

        var ref = youtubeRef(raw);
        if (!ref) {
            // #embedded is deliberately untouched. A half-typed address is a typo in progress,
            // not an instruction to blank the article's video.
            note(embedStatus, t('video.unrecognised'), true);
            return;
        }

        writeEmbed(embedMarkup(ref));
        note(embedStatus, ref.start
                ? t('video.recognisedFrom', ref.id, embedClock(ref.start))
                : t('video.recognised', ref.id), false);
    }

    function applyEmbedMode(mode) {
        embedMode = mode;
        paintEmbed();
        (mode === 'visual' ? embedUrl : embedField).focus();
    }

    (function initEmbeddedVideo() {
        if (!embedField || !embedModes || !embedPane || !embedUrl || !embedPreview) {
            return;
        }

        // Content-derived, every time. Empty is the friendly default for a new video; a value we
        // recognise opens where it can be edited; everything else opens where it is safe.
        var stored = embedField.value;
        embedMode = (stored.trim() === '' || recognisedEmbed(stored)) ? 'visual' : 'html';

        embedModes.hidden = false;
        embedVisualButton.addEventListener('click', function () {
            applyEmbedMode('visual');
        });
        embedHtmlButton.addEventListener('click', function () {
            applyEmbedMode('html');
        });

        embedUrl.addEventListener('input', readEmbedAddress);
        embedUrl.addEventListener('keydown', function (event) {
            if (event.key === 'Enter') {
                // This box is inside <form id="article-form">; Enter here would save the article.
                event.preventDefault();
                readEmbedAddress();
            }
        });

        embedClear.addEventListener('click', function () {
            writeEmbed('');
            embedUrl.value = '';
            note(embedStatus, t('video.removed'), false);
        });

        // The address box is never prefilled from the stored value. It means „замени с това", so
        // no keystroke in it can rewrite an embed the operator has not decided to replace.
        paintEmbed();
    }());

    /* ---------------------------------------------------------- save bar */

    /*
     * Set while an image upload is in flight -- see the image upload module below, which owns it.
     * Read here and in the document-level submit handler at the top of this file, which is what
     * covers the screen's four other navigating POSTs.
     *
     * Saving during an upload is a window only an in-page upload opens: MgmtArticleService.save
     * loads the article, applies the form's fields and writes the document, so a save that read
     * the document before the upload committed would write it back without the new image, leaving
     * the file on disk owned by nothing. The upload is a second or two; refusing to save for that
     * long and saying so is the whole fix.
     */
    var imageUploadInFlight = false;

    /*
     * The save bar's button reaches the form through the `form` attribute, so saving already
     * works without any of this. What is added here is the keyboard shortcut, the dirty
     * indicator, and the guard against navigating away from unsaved work.
     */
    var articleForm = document.getElementById('article-form');

    function submitArticleForm() {
        if (!articleForm) {
            return;
        }
        // requestSubmit, never submit(): submit() skips constraint validation AND skips the
        // submit event, which is what TinyMCE hooks to write the editor's content back into the
        // textarea and what the data-confirm handler above listens for.
        if (articleForm.requestSubmit) {
            articleForm.requestSubmit();
        } else {
            articleForm.submit();
        }
    }

    if (articleForm) {
        var dirty = false;
        var saving = false;
        var status = document.getElementById('save-status');

        function markDirty() {
            if (dirty) {
                return;
            }
            dirty = true;
            if (status) {
                status.textContent = t('save.dirty');
                status.className = 'status dirty';
            }
        }

        /*
         * Not everything inside the form edits the article.
         *
         * The pickers' filter boxes and the whole image library are in there because that is
         * where they have to be on screen, and typing in either changes nothing that a save
         * would write. Arming the beforeunload prompt for them is exactly how operators are
         * taught to click through such prompts.
         *
         * The library also carries readonly path fields, which fire nothing, and its own
         * buttons, which persist through fetch the moment they are pressed -- so there is never
         * anything unsaved in that card to warn about.
         */
        function editsTheArticle(target) {
            return !target.closest('.picker-search') && !target.closest('[data-no-dirty]');
        }

        articleForm.addEventListener('input', function (event) {
            if (!editsTheArticle(event.target)) {
                return;
            }
            markDirty();
        });
        articleForm.addEventListener('change', function (event) {
            // Unlike 'input' this reaches here only from a real control -- including the event
            // each picker re-dispatches on its <select> -- but the library's controls are real
            // controls too.
            if (!editsTheArticle(event.target)) {
                return;
            }
            markDirty();
        });
        articleForm.addEventListener('submit', function (event) {
            if (imageUploadInFlight) {
                event.preventDefault();
                // TinyMCE hooks this same event and has ALREADY written the editor's content back
                // into the textarea and cleared its dirty flag by the time this runs -- its
                // handler was registered at init, so it goes first. Preventing the submit after
                // that would leave isDirty() false over a body that was never saved, and the
                // beforeunload guard would let the operator walk away from it in silence. So the
                // refusal re-arms the tracker: there really are unsaved changes.
                markDirty();
                // Said on the upload card's own status line rather than on the save bar: that one
                // now carries the dirty indicator, and overwriting it would leave a stale sentence
                // behind -- markDirty() only ever writes once.
                note(document.getElementById('upload-status'),
                        t('upload.waitBeforeSave'), true);
                return;
            }
            saving = true;
        });

        document.addEventListener('keydown', function (event) {
            if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 's') {
                event.preventDefault();
                submitArticleForm();
            }
        });

        /*
         * Deliberately NOT "the editor's content differs from what was loaded": that is what the
         * fidelity check measures, and it is true for every stored body TinyMCE rewrites
         * simply by opening it. Warning on those every time the operator navigates away would
         * train them to click through the one warning that matters. isDirty() is false until an
         * actual edit is made.
         */
        window.addEventListener('beforeunload', function (event) {
            var editorDirty = false;
            try {
                editorDirty = !!(window.tinymce && tinymce.activeEditor && tinymce.activeEditor.isDirty());
            } catch (e) {
                editorDirty = false;
            }

            if (saving || (!dirty && !editorDirty)) {
                return;
            }
            event.preventDefault();
            event.returnValue = '';
        });
    }

    /* --------------------------------------------------------- image upload */

    var upload = document.getElementById('image-file');
    var dropzone = document.getElementById('dropzone');
    var imageForm = document.getElementById('image-form');

    if (upload) {
        upload.addEventListener('change', function () {
            showUpload(upload.files);
        });
    }

    if (upload && dropzone) {
        ['dragenter', 'dragover'].forEach(function (name) {
            dropzone.addEventListener(name, function (event) {
                event.preventDefault();
                dropzone.classList.add('over');
            });
        });

        ['dragleave', 'dragend', 'drop'].forEach(function (name) {
            dropzone.addEventListener(name, function () {
                dropzone.classList.remove('over');
            });
        });

        dropzone.addEventListener('drop', function (event) {
            event.preventDefault();
            var files = event.dataTransfer && event.dataTransfer.files;
            if (!files || !files.length) {
                return;
            }
            try {
                // Assigning a FileList to a file input is what makes the dropped files part of
                // the normal selection -- no separate upload path, so the 600x400 rule and the
                // CSRF token apply exactly as they do to picked files. The article's input is
                // `multiple` for this: without it the browser keeps every dropped file and only
                // the first is ever posted, with nothing to say the rest were dropped.
                upload.files = files;
            } catch (e) {
                return;
            }
            showUpload(files);
        });
    }

    function showUpload(files) {
        var hint = document.getElementById('upload-hint');
        if (!hint) {
            return;
        }
        hint.textContent = '';
        if (!files || !files.length) {
            return;
        }

        var file = files[0];

        if (/^image\//.test(file.type) && window.URL && URL.createObjectURL) {
            var preview = document.createElement('img');
            preview.src = URL.createObjectURL(file);
            preview.alt = '';
            preview.addEventListener('load', function () {
                URL.revokeObjectURL(preview.src);
            });
            hint.appendChild(preview);
        }

        var bytes = 0;
        Array.prototype.forEach.call(files, function (each) {
            bytes += each.size;
        });

        // One thumbnail, but every file counted: the selection is what gets posted, and a hint
        // naming only the first would hide two of three chosen files.
        var name = document.createElement('span');
        name.textContent = (files.length > 1 ? t('upload.fileCount', files.length) + ' · ' : file.name + ' · ')
                + Math.round(bytes / 1024) + ' KB';
        hint.appendChild(name);
    }

    /*
     * Uploading without leaving the page.
     *
     * The fourth thing in this console that fetches, and it is here for the reason the other
     * three are: it fires while the operator is mid-article. A plain submit of «Качи» gets a
     * redirect back to the editor, so uploading an image would throw away every unsaved word in
     * the body -- the loss the beforeunload guard exists to warn about, except here the operator
     * asked for an upload and would lose the article instead, with no prompt because a form
     * submit is not a navigation the guard fires on.
     *
     * A wrapper over the form, never a replacement: the action stays declared and stays correct,
     * and with scripting off «Качи» posts it and gets the redirect.
     *
     * Scoped by `data-async`, which only article-edit.html carries. banner-edit.html reuses this
     * same #image-form / #dropzone / #image-file trio and wants the redirect. So does the no-script
     * half of banner-layout-edit.html, whose scripted half is the `data-slide-upload` module below
     * -- one file, one row, and an answer shaped differently from an article's image card.
     */
    if (imageForm && imageForm.hasAttribute('data-async') && upload
            && window.fetch && window.FormData) {

        var uploadStatus = document.getElementById('upload-status');
        var uploadButton = document.getElementById('upload-submit');
        // Derived from the form's own action, so the two paths cannot come to name different URLs.
        var uploadUrl = imageForm.getAttribute('action') + '/upload';

        imageForm.addEventListener('submit', function (event) {
            if (!upload.files || !upload.files.length) {
                // `required` normally stops this from ever being reached; if it is, the plain
                // POST is a better answer than a fetch with an empty body.
                return;
            }
            event.preventDefault();
            uploadAll(Array.prototype.slice.call(upload.files));
        });

        function uploadAll(files) {
            imageUploadInFlight = true;
            setUploading(true);

            var added = 0;
            var painted = true;

            /*
             * Strictly one at a time, never Promise.all: ImageService.getNextId() reads the
             * current maximum id and writes max+1, so two uploads in flight can be handed the
             * same id -- and the loser fails on the unique filename index at best.
             */
            function next(index) {
                if (index >= files.length) {
                    finish(added, files.length, painted, null);
                    return;
                }

                note(uploadStatus, files.length > 1
                        ? t('upload.progress', index + 1, files.length)
                        : t('upload.uploading'), false);

                var body = new FormData();
                body.append('file', files[index]);

                fetch(uploadUrl, {
                    method: 'POST',
                    // No Content-Type: the browser writes it together with the multipart
                    // boundary, which nothing here could compute. mgmtCsrf() returns the token
                    // header alone, which is the whole reason it can be passed as-is.
                    headers: window.mgmtCsrf(),
                    body: body,
                    credentials: 'same-origin'
                }).then(readJson).then(function (row) {
                    added++;
                    // Installed by the arrange module further down, which owns the grid; it is
                    // what paints the card and moves the «главна» badge.
                    if (window.mgmtImages) {
                        window.mgmtImages.add(row);
                    } else {
                        painted = false;
                    }
                    next(index + 1);
                }).catch(function (error) {
                    // Stops rather than carrying on. The ordinary refusal is the per-article cap,
                    // which every remaining file would hit as well, and a run of identical
                    // failures says less than one naming the file it happened on. A file past the
                    // container's multipart limit lands here too, as Tomcat's HTML 413 -- readJson
                    // refuses a non-JSON body rather than choking on it.
                    // Named, but never named twice: most refusals come back as
                    // "garden.png is 601x400; …" and already say which file they are about, while
                    // the per-article cap and the container's 413 do not.
                    var reason = error.message;
                    var named = reason.indexOf(files[index].name) === -1
                            ? files[index].name + ': ' + reason
                            : reason;
                    finish(added, files.length, painted, named);
                });
            }

            next(0);
        }

        function finish(added, total, painted, failure) {
            imageUploadInFlight = false;
            setUploading(false);

            if (!failure) {
                // Cleared only on a clean run: pressing «Качи» again with the same selection
                // re-posts files that are already stored, and ImageService.save refuses them on
                // the unique filename index with a message about a duplicate.
                imageForm.reset();
                showUpload(null);
            }

            if (failure) {
                note(uploadStatus, (added ? t('upload.partial', added, total) + ' ' : '') + failure, true);
            } else if (total > 1) {
                note(uploadStatus, t('upload.addedMany', added), false);
            } else {
                note(uploadStatus, t('upload.addedOne'), false);
            }

            if (!painted) {
                note(uploadStatus, uploadStatus.textContent
                        + ' ' + t('upload.reloadToSee'), !!failure);
            }
        }

        function setUploading(active) {
            if (uploadButton) {
                uploadButton.disabled = active;
            }
            upload.disabled = active;
        }
    }

    /* ------------------------------------------------ banner layout: slides
     *
     * The builder's two uploads without leaving the page: adding a frame, and swapping the
     * picture on one. A redirect would throw away every link, caption and dimension typed since
     * the last «Запази» -- exactly the fields the operator is usually in the middle of when the
     * advertiser's next creative arrives.
     *
     * Wrappers, never replacements. #image-form keeps its action and each row keeps its
     * .slide-swap input and button reaching `slide-img-N` through form=, so with scripting off both
     * post and redirect. The fetch URLs are those actions + '/upload', so the two
     * paths cannot come to name different routes.
     *
     * An added frame arrives as server-rendered markup -- the row AND its four out-of-line action
     * forms, from the same fragments the page renders -- rather than being built here. The row
     * carries `slides[N].href/title/alt`, and a save posting fewer slides than the document holds
     * is refused by applySlides; a client-side twin of the row is how that count would drift.
     *
     * imageUploadInFlight is held for both: MgmtBannerLayoutService.save loads the document and
     * applies the form over it, so a save racing an add would be refused over the count, and the
     * move/delete forms are load-modify-save over the same document. The document-level submit
     * guard above already refuses every other form while it is set.
     */
    var slideForm = document.querySelector('#image-form[data-slide-upload]');
    var slidesBody = document.getElementById('slides-body');

    if (slidesBody && window.fetch && window.FormData && window.mgmtCsrf) {

        var slideStatus = document.getElementById('upload-status');
        var slideFormsBox = document.getElementById('slide-forms');

        /**
         * readJson's twin for an HTML answer.
         *
         * A refusal is the `slide-refused` fragment at 409 and the reason is read out of it;
         * anything else not ok -- Tomcat's 413, a 404 for a layout deleted in another tab -- is
         * reported by status rather than dumped on screen as markup.
         */
        function readHtml(response) {
            if (response.redirected) {
                throw new Error(t('request.sessionExpired'));
            }
            return response.text().then(function (text) {
                if (!response.ok) {
                    var reason = parseMarkup(text).querySelector('.upload-refusal');
                    throw new Error(reason && reason.textContent.trim()
                            ? reason.textContent.trim()
                            : t('request.failed', response.status));
                }
                return text;
            });
        }

        // A <template>, so a bare <tr> survives parsing -- innerHTML on a <div> drops it.
        function parseMarkup(html) {
            var template = document.createElement('template');
            template.innerHTML = html;
            return template.content;
        }

        function postFile(url, file) {
            var body = new FormData();
            body.append('file', file);
            return fetch(url, {
                method: 'POST',
                // No Content-Type: the browser writes it with the multipart boundary.
                headers: window.mgmtCsrf(),
                body: body,
                credentials: 'same-origin'
            });
        }

        /*
         * The published folder has just been rewritten, so a preview already open is showing the
         * old one. Put it back to its unloaded state rather than reloading it: every load of the
         * frame is a Google Tag Manager pageview, which is why it is click-to-load at all.
         */
        function resetPreview() {
            var box = document.getElementById('layout-preview');
            var load = document.getElementById('layout-preview-load');
            if (box && box.querySelector('iframe')) {
                box.textContent = '';
                if (load) {
                    load.disabled = false;
                }
            }
        }

        /* -- swapping one frame's picture */

        function wireRow(row) {
            var thumb = row.querySelector('.slide-thumb');
            var swap = row.querySelector('.slide-swap');
            var input = swap && swap.querySelector('input[type=file]');
            var form = input && document.getElementById(input.getAttribute('form'));
            if (!thumb || !input || !form) {
                return;
            }

            var url = form.getAttribute('action') + '/upload';

            thumb.classList.add('is-swappable');
            thumb.setAttribute('role', 'button');
            thumb.setAttribute('tabindex', '0');
            thumb.title = t('slide.swapTitle');
            thumb.setAttribute('aria-label', input.getAttribute('aria-label') || thumb.title);
            // The thumbnail is the control now; the input stays in the DOM and is what it opens.
            swap.hidden = true;

            thumb.addEventListener('click', function () {
                if (!imageUploadInFlight) {
                    input.click();
                }
            });
            thumb.addEventListener('keydown', function (event) {
                if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault();
                    thumb.click();
                }
            });

            ['dragenter', 'dragover'].forEach(function (name) {
                thumb.addEventListener(name, function (event) {
                    event.preventDefault();
                    thumb.classList.add('over');
                });
            });
            ['dragleave', 'dragend', 'drop'].forEach(function (name) {
                thumb.addEventListener(name, function () {
                    thumb.classList.remove('over');
                });
            });
            thumb.addEventListener('drop', function (event) {
                event.preventDefault();
                var files = event.dataTransfer && event.dataTransfer.files;
                if (files && files.length) {
                    replace(files[0]);
                }
            });

            input.addEventListener('change', function () {
                if (input.files && input.files.length) {
                    replace(input.files[0]);
                }
            });

            /*
             * The outcome is said in the row, under the name it concerns. #upload-status sits in
             * the card below the whole form, usually off-screen from the row being swapped, and a
             * refusal said only there looks like the previous picture simply coming back.
             */
            function rowNote(message, failed) {
                var line = row.querySelector('.slide-status');
                if (!line) {
                    line = document.createElement('p');
                    line.className = 'muted arrange-status slide-status';
                    line.setAttribute('role', 'status');
                    var cell = thumb.parentNode;
                    cell.appendChild(line);
                }
                note(line, message, failed);
            }

            function replace(file) {
                if (imageUploadInFlight) {
                    rowNote(t('upload.waitCurrent'), true);
                    return;
                }
                imageUploadInFlight = true;
                thumb.classList.add('busy');
                rowNote(t('upload.uploading'), false);

                postFile(url, file).then(readJson).then(function (data) {
                    var img = thumb.querySelector('img');
                    if (img) {
                        img.src = data.src;
                        img.alt = data.image;
                    }
                    var name = row.querySelector('.slide-image-name');
                    if (name) {
                        name.textContent = data.image;
                    }
                    resetPreview();
                    rowNote(t('slide.replaced'), false);
                }).catch(function (error) {
                    var reason = error.message;
                    rowNote(reason.indexOf(file.name) === -1
                            ? file.name + ': ' + reason
                            : reason, true);
                }).then(function () {
                    imageUploadInFlight = false;
                    thumb.classList.remove('busy');
                    // Cleared either way: picking the same file again must fire `change` again.
                    input.value = '';
                });
            }
        }

        Array.prototype.forEach.call(slidesBody.querySelectorAll('tr'), wireRow);

        /* -- adding a frame */

        if (slideForm && upload && slideFormsBox) {
            var slideButton = document.getElementById('upload-submit');
            var addUrl = slideForm.getAttribute('action') + '/upload';

            slideForm.addEventListener('submit', function (event) {
                if (!upload.files || !upload.files.length) {
                    // `required` normally stops this; the plain POST is the better answer if not.
                    return;
                }
                event.preventDefault();
                if (imageUploadInFlight) {
                    note(slideStatus, t('upload.waitCurrent'), true);
                    return;
                }

                addAll(Array.prototype.slice.call(upload.files));
            });

            /*
             * Every file, strictly one at a time. The input is not `multiple`, but the dropzone's
             * shared drop handler assigns the whole dropped FileList and the hint then counts them
             * all -- posting only the first would report success over the rest in silence. One at
             * a time because addSlide is load-modify-save over the document, and each row has to
             * be in place before the next index is handed out.
             */
            function addAll(files) {
                imageUploadInFlight = true;
                if (slideButton) {
                    slideButton.disabled = true;
                }
                var added = 0;

                function next(index) {
                    if (index >= files.length) {
                        done(null);
                        return;
                    }
                    var file = files[index];
                    note(slideStatus, files.length > 1
                            ? t('upload.progress', index + 1, files.length)
                            : t('upload.uploading'), false);

                    postFile(addUrl, file).then(readHtml).then(function (html) {
                        paint(html);
                        added++;
                        next(index + 1);
                    }).catch(function (error) {
                        var reason = error.message;
                        // Stops rather than carrying on: a run of identical refusals says less
                        // than one naming the file it happened on.
                        done(reason.indexOf(file.name) === -1 ? file.name + ': ' + reason : reason);
                    });
                }

                function done(failure) {
                    imageUploadInFlight = false;
                    if (slideButton) {
                        slideButton.disabled = false;
                    }
                    if (added) {
                        resetPreview();
                    }
                    if (failure) {
                        note(slideStatus, (added ? t('upload.partial', added, files.length) + ' ' : '')
                                + failure, true);
                        return;
                    }
                    // Cleared only on a clean run, so a refused file can be looked at and retried.
                    slideForm.reset();
                    showUpload(null);
                    note(slideStatus, files.length > 1
                            ? t('slide.addedMany', added)
                            : t('slide.addedOne'), false);
                }

                next(0);
            }

            function paint(html) {
                var fragment = parseMarkup(html);
                var row = fragment.querySelector('tr');
                if (!row) {
                    throw new Error(t('slide.notShown'));
                }

                // The previous last row can move down now.
                var rows = slidesBody.querySelectorAll('tr');
                var previous = rows.length ? rows[rows.length - 1].querySelector('.slide-down') : null;
                if (previous) {
                    previous.disabled = false;
                }

                Array.prototype.forEach.call(fragment.querySelectorAll('form'), function (form) {
                    slideFormsBox.appendChild(form);
                });
                slidesBody.appendChild(row);
                wireRow(row);

                var table = document.getElementById('slides-table');
                var empty = document.getElementById('slides-empty');
                if (table) {
                    table.hidden = false;
                }
                if (empty) {
                    empty.hidden = true;
                }
            }
        }
    }

    /* ------------------------------------------------- image paths and body */

    /*
     * Our own drag payload. A private type, so the editor's drop handler can tell one of our
     * thumbnails from a file, a link or a selection and leave everything else to TinyMCE.
     */
    var IMAGE_DRAG_TYPE = 'application/x-mgmt-image';

    function imageTag(path) {
        return '<img src="' + path + '" alt="">';
    }

    function insertIntoBody(path) {
        var editor = window.tinymce && tinymce.activeEditor;

        if (!editor || editor.removed) {
            // The editor failed to load and the textarea is what the operator sees. Putting the
            // markup at the caret there is still the right answer -- silently doing nothing is
            // not.
            var textarea = document.getElementById('text');
            if (!textarea) {
                return false;
            }
            var at = typeof textarea.selectionStart === 'number' ? textarea.selectionStart : textarea.value.length;
            textarea.value = textarea.value.slice(0, at) + imageTag(path) + textarea.value.slice(at);
            textarea.dispatchEvent(new Event('input', {bubbles: true}));
            return true;
        }

        editor.insertContent(imageTag(path));
        editor.focus();
        return true;
    }

    /*
     * Copying the path.
     *
     * The readonly input is the mechanism and this is the enhancement, not the other way round:
     * navigator.clipboard exists only in a secure context, and the local stack is reached over
     * plaintext. execCommand is the fallback that still works there; if even that fails the
     * field is selected, which is the honest end state -- Ctrl+C from here does the rest.
     */
    document.addEventListener('click', function (event) {
        var button = event.target.closest('[data-copy]');
        if (!button) {
            return;
        }

        var row = button.closest('.path-row');
        var field = row && row.querySelector('.path');
        if (!field) {
            return;
        }

        field.focus();
        field.select();
        field.setSelectionRange(0, field.value.length);

        var copied = false;
        try {
            copied = document.execCommand('copy');
        } catch (e) {
            copied = false;
        }

        if (!copied && navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(field.value).then(function () {
                flashCopied(button);
            }, function () { /* selection stands; Ctrl+C works */ });
            return;
        }

        if (copied) {
            flashCopied(button);
        }
    });

    // Selecting the whole path on focus, so a click into the field is already a copy away.
    document.addEventListener('focusin', function (event) {
        if (event.target.classList && event.target.classList.contains('path')) {
            event.target.select();
        }
    });

    function flashCopied(button) {
        button.classList.add('copied');
        window.setTimeout(function () {
            button.classList.remove('copied');
        }, 1200);
    }

    /* --------------------------------------------- attached images: arrange */

    var imagesGrid = document.getElementById('article-images');

    if (imagesGrid) {
        var arrangeStatus = document.getElementById('arrange-status');
        var imagesEmpty = document.getElementById('images-empty');
        var arrangeUrl = MGMT + '/articles/' + imagesGrid.getAttribute('data-article') + '/images/arrange';

        imagesGrid.addEventListener('click', function (event) {
            var card = event.target.closest('.image-card');
            if (!card) {
                return;
            }

            var move = event.target.closest('[data-move]');
            if (move) {
                shift(card, move.getAttribute('data-move'));
                return;
            }

            if (event.target.closest('[data-toggle-hidden]')) {
                card.classList.toggle('is-hidden');
                repaint();
                saveArrangement();
                return;
            }

            if (event.target.closest('[data-insert]')) {
                insertIntoBody(card.getAttribute('data-path'));
            }
        });

        function shift(card, direction) {
            if (direction === 'up' && card.previousElementSibling) {
                imagesGrid.insertBefore(card, card.previousElementSibling);
            } else if (direction === 'down' && card.nextElementSibling) {
                imagesGrid.insertBefore(card.nextElementSibling, card);
            } else {
                return;
            }
            repaint();
            saveArrangement();
        }

        /*
         * Drag to reorder, layered over the buttons rather than replacing them: dragging is
         * unreachable from a keyboard and unusable on a touch screen, and this console is used on
         * both. The same drag also carries the image's path, so a card can be dropped straight
         * into the editor -- see the drop handler registered in TinyMCE's setup.
         */
        var dragged = null;

        imagesGrid.addEventListener('dragstart', function (event) {
            var card = event.target.closest('.image-card');
            if (!card) {
                return;
            }
            dragged = card;
            card.classList.add('dragging');
            event.dataTransfer.effectAllowed = 'copyMove';
            event.dataTransfer.setData(IMAGE_DRAG_TYPE, card.getAttribute('data-path'));
            // A plain-text/HTML copy too, so dropping somewhere we do not control still yields
            // something sensible rather than nothing.
            event.dataTransfer.setData('text/html', imageTag(card.getAttribute('data-path')));
            event.dataTransfer.setData('text/plain', card.getAttribute('data-path'));
        });

        imagesGrid.addEventListener('dragend', function () {
            if (dragged) {
                dragged.classList.remove('dragging');
            }
            Array.prototype.forEach.call(imagesGrid.querySelectorAll('.drop-target'), function (card) {
                card.classList.remove('drop-target');
            });
            dragged = null;
        });

        imagesGrid.addEventListener('dragover', function (event) {
            if (!dragged) {
                return;
            }
            var over = event.target.closest('.image-card');
            if (!over || over === dragged) {
                return;
            }
            event.preventDefault();
            event.dataTransfer.dropEffect = 'move';
            over.classList.add('drop-target');
        });

        imagesGrid.addEventListener('dragleave', function (event) {
            var over = event.target.closest('.image-card');
            if (over) {
                over.classList.remove('drop-target');
            }
        });

        imagesGrid.addEventListener('drop', function (event) {
            if (!dragged) {
                return;
            }
            var over = event.target.closest('.image-card');
            if (!over || over === dragged) {
                return;
            }
            event.preventDefault();
            over.classList.remove('drop-target');

            // Before or after, depending on which way the card travelled.
            var cards = Array.prototype.slice.call(imagesGrid.children);
            if (cards.indexOf(dragged) < cards.indexOf(over)) {
                imagesGrid.insertBefore(dragged, over.nextElementSibling);
            } else {
                imagesGrid.insertBefore(dragged, over);
            }

            repaint();
            saveArrangement();
        });

        /*
         * The cover badge follows the first VISIBLE card, which is what the public site reads as
         * the share card and as every list thumbnail. Recomputed rather than moved, so it cannot
         * drift out of step with the hidden toggles.
         */
        function repaint() {
            var cards = Array.prototype.slice.call(imagesGrid.querySelectorAll('.image-card'));
            var cover = null;

            cards.forEach(function (card) {
                if (!cover && !card.classList.contains('is-hidden')) {
                    cover = card;
                }
                var badge = card.querySelector('.image-badge.cover');
                if (badge) {
                    badge.remove();
                }
            });

            if (cover) {
                var badge = document.createElement('span');
                badge.className = 'image-badge cover';
                badge.textContent = t('images.cover');
                cover.insertBefore(badge, cover.firstChild);
            }

            if (imagesEmpty) {
                imagesEmpty.classList.toggle('hidden', cards.length > 0);
            }
        }

        function saveArrangement() {
            var cards = Array.prototype.slice.call(imagesGrid.querySelectorAll('.image-card'));
            var body = new URLSearchParams();

            cards.forEach(function (card) {
                body.append('ids', card.getAttribute('data-image'));
                if (card.classList.contains('is-hidden')) {
                    body.append('hidden', card.getAttribute('data-image'));
                }
            });

            note(arrangeStatus, t('images.saving'), false);

            var headers = window.mgmtCsrf();
            headers['Content-Type'] = 'application/x-www-form-urlencoded; charset=UTF-8';

            fetch(arrangeUrl, {
                method: 'POST',
                headers: headers,
                body: body.toString(),
                credentials: 'same-origin'
            }).then(function (response) {
                if (!response.ok) {
                    throw new Error(response.status);
                }
                note(arrangeStatus, t('images.arranged'), false);
            }).catch(function () {
                // Includes an expired session, which answers with a 302 to the login page rather
                // than a 401 -- see MgmtSecurityConfig. Saying so beats a silent no-op.
                note(arrangeStatus, t('images.arrangeFailed'), true);
            });
        }

        // The server-rendered order is already correct; this only reconciles the empty state.
        repaint();

        window.mgmtImages = {
            add: function (row) {
                imagesGrid.appendChild(buildImageCard(row));
                repaint();
            },
            count: function () {
                return imagesGrid.querySelectorAll('.image-card').length;
            }
        };
    }

    function note(element, message, failed) {
        if (!element) {
            return;
        }
        element.textContent = message;
        element.classList.toggle('failed', !!failed);
    }

    /**
     * The client-side twin of the .image-card block in article-edit.html.
     *
     * Built here rather than re-fetched because attaching must not reload the page: the operator
     * is in the middle of an article, and a reload takes the unsaved body with it. Keep the two
     * in step -- MgmtScreenRenderTest pins the server-rendered half.
     */
    function buildImageCard(row) {
        var card = document.createElement('div');
        card.className = 'image-card';
        card.draggable = true;
        card.setAttribute('data-image', row.id);
        card.setAttribute('data-path', row.path);

        var hiddenBadge = document.createElement('span');
        hiddenBadge.className = 'image-badge hidden-badge';
        hiddenBadge.textContent = t('images.hidden');
        card.appendChild(hiddenBadge);

        var img = document.createElement('img');
        img.loading = 'lazy';
        img.src = row.path;
        img.alt = row.filename || '';
        card.appendChild(img);

        var meta = document.createElement('div');
        meta.className = 'meta';
        var name = document.createElement('span');
        name.className = 'name';
        name.textContent = row.filename || '';
        name.title = row.filename || '';
        meta.appendChild(name);

        // A real form, so removing works exactly as it does for a server-rendered card -- and
        // still works if this page is never reloaded.
        var form = document.createElement('form');
        form.className = 'inline-form';
        form.method = 'post';
        form.action = MGMT + '/articles/' + imagesGrid.getAttribute('data-article')
                + '/images/' + row.id + '/delete';
        form.setAttribute('data-confirm', t('images.removeConfirm'));
        form.appendChild(csrfField());
        form.appendChild(iconButton('#i-trash', t('images.remove'), 'btn btn-danger btn-icon btn-sm', 'submit'));
        meta.appendChild(form);
        card.appendChild(meta);

        var actions = document.createElement('div');
        actions.className = 'image-actions';
        actions.appendChild(dataButton('#i-arrow-up', t('common.up'), 'data-move', 'up'));
        actions.appendChild(dataButton('#i-arrow-down', t('common.down'), 'data-move', 'down'));

        var toggle = iconButton('#i-eye', t('images.toggleHidden'), 'btn btn-icon btn-sm', 'button');
        toggle.setAttribute('data-toggle-hidden', '');
        toggle.querySelector('svg').classList.add('on-shown');
        toggle.insertBefore(icon('#i-eye-off', 'on-hidden'), toggle.firstChild.nextSibling);
        actions.appendChild(toggle);

        var insert = iconButton('#i-insert', t('images.insert'), 'btn btn-icon btn-sm', 'button');
        insert.setAttribute('data-insert', '');
        actions.appendChild(insert);
        card.appendChild(actions);

        card.appendChild(pathRow(row.path));
        return card;
    }

    function pathRow(path) {
        var wrap = document.createElement('div');
        wrap.className = 'path-row';

        var field = document.createElement('input');
        field.className = 'path';
        field.type = 'text';
        field.readOnly = true;
        field.value = path;
        field.title = path;
        field.setAttribute('aria-label', t('images.path'));
        // No name attribute. In the library card this row sits inside <form id="article-form">,
        // and a named field there would post a parameter MgmtArticleService never asked for.
        wrap.appendChild(field);

        wrap.appendChild(iconButton('#i-copy', t('images.copyPath'), 'btn btn-icon btn-sm', 'button', 'data-copy'));
        return wrap;
    }

    function icon(href, className) {
        var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        svg.setAttribute('class', 'i' + (className ? ' ' + className : ''));
        var use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
        use.setAttribute('href', href);
        svg.appendChild(use);
        return svg;
    }

    function iconButton(href, label, className, type, flag) {
        var button = document.createElement('button');
        button.type = type || 'button';
        button.className = className;
        button.title = label;
        if (flag) {
            button.setAttribute(flag, '');
        }
        button.appendChild(icon(href));

        var sr = document.createElement('span');
        sr.className = 'sr-only';
        sr.textContent = label;
        button.appendChild(sr);
        return button;
    }

    function dataButton(href, label, attribute, value) {
        var button = iconButton(href, label, 'btn btn-icon btn-sm', 'button');
        button.setAttribute(attribute, value);
        return button;
    }

    /** A hidden _csrf field, for the forms this file builds rather than Thymeleaf. */
    function csrfField() {
        var token = document.querySelector('meta[name="_csrf"]');
        var field = document.createElement('input');
        field.type = 'hidden';
        field.name = '_csrf';
        field.value = token ? token.getAttribute('content') : '';
        return field;
    }

    /* ------------------------------------------------------- image library */

    var library = document.getElementById('image-library');

    if (library) {
        var libraryList = document.getElementById('library-list');
        var librarySearch = document.getElementById('library-search');
        var libraryMore = document.getElementById('library-more');
        var libraryArticle = library.getAttribute('data-article');
        var libraryNote = document.createElement('p');
        libraryNote.className = 'library-note';
        libraryList.parentNode.insertBefore(libraryNote, libraryList.nextSibling);

        var libraryPage = 0;
        var libraryTimer = null;
        // Rises on every new search; a response from an older one is discarded rather than
        // painted over the newer results.
        var librarySeq = 0;

        /*
         * The ordering.
         *
         * Server-side, and reloading from page one on every change, because the list is paged: a
         * client-side sort would reorder the twelve rows on screen and call it an ordering of the
         * library, which is the shape of wrong answer /mgmt/authors' column links exist to avoid.
         *
         * Three keys, matching MgmtImageService.ImageSort. «Статии» is not a field on the image at
         * all -- the count lives on the article side -- which is why this cannot be a Sort the
         * client assembles and hands over.
         *
         * The state is NEVER what was clicked: it is what the server says it applied, adopted in
         * loadLibrary's response handler. `?sort=` is an allow-list there, so a value it does not
         * know comes back as `id`, and painting the click instead would mark a button active that
         * the list is not ordered by.
         */
        var LIBRARY_SORTS = [
            {key: 'id', text: t('library.sort.id'), title: t('library.sort.id.title')},
            {key: 'name', text: t('library.sort.name'), title: t('library.sort.name.title')},
            {key: 'articles', text: t('library.sort.articles'), title: t('library.sort.articles.title')}
        ];

        var librarySort = 'id';
        var libraryDir = 'asc';

        /*
         * The toolbar sits above the list and stays on screen in both states, collapsed and
         * expanded. Deliberately not shown only when expanded: a control that changes the order of
         * the list and then hides itself leaves the rail sorted by something the operator cannot
         * see, which is the same silence a hidden filter would be. Nothing in it carries a `name`
         * -- see makeOverlay for why that is load-bearing rather than tidy.
         */
        var libraryTools = document.createElement('div');
        libraryTools.className = 'library-tools';

        var sortGroup = document.createElement('div');
        sortGroup.className = 'segmented';
        sortGroup.setAttribute('role', 'group');
        sortGroup.setAttribute('aria-label', t('library.sort'));

        var sortButtons = LIBRARY_SORTS.map(function (entry) {
            var button = document.createElement('button');
            button.type = 'button';
            button.className = 'btn btn-sm btn-ghost';
            button.title = entry.title;

            var text = document.createElement('span');
            text.textContent = entry.text;
            button.appendChild(text);

            // The arrow is appended to the active button and removed from the others rather than
            // rendered on all three and hidden: `hidden` on an element some rule gives a `display`
            // to does nothing, which is the guard block at the foot of mgmt.css, and an element
            // that is never in the document cannot be caught by it.
            var arrow = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
            arrow.setAttribute('class', 'i');
            var arrowUse = document.createElementNS('http://www.w3.org/2000/svg', 'use');
            arrow.appendChild(arrowUse);

            button.addEventListener('click', function () {
                // Clicking the active key reverses it; clicking another starts ascending. The
                // /mgmt/authors and /mgmt/categories rule, so the two behave the same way.
                if (entry.key === librarySort) {
                    libraryDir = libraryDir === 'asc' ? 'desc' : 'asc';
                } else {
                    librarySort = entry.key;
                    libraryDir = 'asc';
                }
                paintSort();
                loadLibrary(0);
            });

            sortGroup.appendChild(button);
            return {key: entry.key, button: button, arrow: arrow, use: arrowUse};
        });

        libraryTools.appendChild(sortGroup);
        libraryList.parentNode.insertBefore(libraryTools, libraryList);

        var libraryOverlay = makeOverlay(library, {
            label: t('library.label'),
            expandTitle: t('library.expand'),
            collapseTitle: t('library.collapse'),
            onOpen: function () {
                librarySearch.focus();
                librarySearch.select();
            }
        });

        libraryTools.appendChild(libraryOverlay.button);

        function paintSort() {
            sortButtons.forEach(function (entry) {
                var active = entry.key === librarySort;
                entry.button.setAttribute('aria-pressed', active ? 'true' : 'false');
                if (active) {
                    entry.use.setAttribute('href',
                            libraryDir === 'desc' ? '#i-arrow-down' : '#i-arrow-up');
                    entry.button.appendChild(entry.arrow);
                } else if (entry.arrow.parentNode) {
                    entry.arrow.parentNode.removeChild(entry.arrow);
                }
            });
        }

        paintSort();

        librarySearch.addEventListener('input', function () {
            window.clearTimeout(libraryTimer);
            libraryTimer = window.setTimeout(function () {
                loadLibrary(0);
            }, 250);
        });

        librarySearch.addEventListener('keydown', function (event) {
            // Enter here would submit the ARTICLE -- this input lives inside its form. Same
            // guard, same reason, as the author/category picker's filter.
            if (event.key === 'Enter') {
                event.preventDefault();
            } else if (event.key === 'Escape' && librarySearch.value) {
                event.preventDefault();
                librarySearch.value = '';
                loadLibrary(0);
            }
        });

        libraryMore.addEventListener('click', function () {
            loadLibrary(libraryPage + 1);
        });

        libraryList.addEventListener('click', function (event) {
            var item = event.target.closest('.library-item');
            if (!item) {
                return;
            }

            if (event.target.closest('[data-insert]')) {
                // Collapsed first when the library is covering the window: the editor is behind
                // the scrim, so inserting while expanded puts the picture somewhere the operator
                // cannot see and reads as a click that did nothing. Attaching is not the same --
                // it reports itself in the panel's own note.
                libraryOverlay.setFull(false);
                insertIntoBody(item.getAttribute('data-path'));
                return;
            }

            var attach = event.target.closest('[data-attach]');
            if (attach) {
                attachImage(item, attach);
            }
        });

        libraryList.addEventListener('dragstart', function (event) {
            var item = event.target.closest('.library-item');
            if (!item) {
                return;
            }
            item.classList.add('dragging');
            event.dataTransfer.effectAllowed = 'copy';
            event.dataTransfer.setData(IMAGE_DRAG_TYPE, item.getAttribute('data-path'));
            event.dataTransfer.setData('text/html', imageTag(item.getAttribute('data-path')));
            event.dataTransfer.setData('text/plain', item.getAttribute('data-path'));
        });

        libraryList.addEventListener('dragend', function (event) {
            var item = event.target.closest('.library-item');
            if (item) {
                item.classList.remove('dragging');
            }
        });

        function attachImage(item, button) {
            button.disabled = true;

            fetch(MGMT + '/articles/' + libraryArticle + '/images/' + item.getAttribute('data-image') + '/attach', {
                method: 'POST',
                headers: window.mgmtCsrf(),
                credentials: 'same-origin'
            }).then(readJson).then(function (row) {
                if (window.mgmtImages) {
                    window.mgmtImages.add(row);
                }
                note(libraryNote, t('library.attached', row.filename || ''), false);
                markAttached(item);
            }).catch(function (error) {
                button.disabled = false;
                note(libraryNote, error.message, true);
            });
        }

        function markAttached(item) {
            var actions = item.querySelector('.lib-actions');
            var attached = document.createElement('span');
            attached.className = 'lib-use';
            attached.textContent = t('library.attachedBadge');
            var button = actions.querySelector('[data-attach]');
            if (button) {
                actions.replaceChild(attached, button);
            }
        }

        function loadLibrary(page) {
            var mine = ++librarySeq;
            var url = MGMT + '/images/search?page=' + page
                    + '&q=' + encodeURIComponent(librarySearch.value.trim())
                    + '&sort=' + encodeURIComponent(librarySort)
                    + '&dir=' + encodeURIComponent(libraryDir);

            note(libraryNote, t('library.searching'), false);

            fetch(url, {credentials: 'same-origin', headers: {'Accept': 'application/json'}})
                .then(readJson)
                .then(function (data) {
                    if (mine !== librarySeq) {
                        return;
                    }
                    if (page === 0) {
                        libraryList.textContent = '';
                    }
                    libraryPage = data.page;
                    // The ordering that was applied, not the one that was asked for.
                    librarySort = data.sort || librarySort;
                    libraryDir = data.dir || libraryDir;
                    paintSort();
                    data.images.forEach(function (row) {
                        libraryList.appendChild(buildLibraryItem(row));
                    });
                    libraryMore.classList.toggle('hidden', !data.more);

                    if (!libraryList.children.length) {
                        note(libraryNote, t('library.none'), false);
                    } else {
                        note(libraryNote, '', false);
                    }
                })
                .catch(function (error) {
                    if (mine !== librarySeq) {
                        return;
                    }
                    note(libraryNote, error.message, true);
                });
        }

        function buildLibraryItem(row) {
            var item = document.createElement('div');
            item.className = 'library-item';
            item.draggable = true;
            item.setAttribute('data-image', row.id);
            item.setAttribute('data-path', row.path);

            var img = document.createElement('img');
            img.loading = 'lazy';
            img.src = row.path;
            img.alt = row.filename || '';
            img.title = t('library.drag');
            item.appendChild(img);

            var body = document.createElement('div');
            body.className = 'lib-body';

            var name = document.createElement('span');
            name.className = 'lib-name';
            name.textContent = t('library.number', row.id) + ' · ' + (row.filename || '');
            name.title = row.filename || '';
            body.appendChild(name);

            var usage = document.createElement('span');
            usage.className = 'lib-use';
            usage.textContent = row.usages > 0 ? t('library.usedIn', row.usages) : t('library.unused');
            body.appendChild(usage);

            var actions = document.createElement('div');
            actions.className = 'lib-actions';

            var attach = document.createElement('button');
            attach.type = 'button';
            attach.className = 'btn btn-sm';
            attach.textContent = t('library.attach');
            attach.setAttribute('data-attach', '');
            actions.appendChild(attach);

            var insert = document.createElement('button');
            insert.type = 'button';
            insert.className = 'btn btn-sm btn-ghost';
            insert.textContent = t('images.insert');
            insert.setAttribute('data-insert', '');
            actions.appendChild(insert);

            body.appendChild(actions);
            body.appendChild(pathRow(row.path));
            item.appendChild(body);
            return item;
        }

        loadLibrary(0);
    }

    /* ------------------------------------------------------------------------ background job
     *
     * One of the console's polling loops. The monitoring screen's live reader count is another,
     * at the foot of this file, and both are shaped the same way for the reasons below.
     *
     * A job runs minutes to hours -- one Gotenberg render per article per language, or one
     * completion per field and language -- so the job screen has to update itself while it runs.
     * Everything it needs already exists here: readJson(), note(), and the staleness guard the
     * image library uses.
     *
     * setTimeout chaining, NOT setInterval. The next request is scheduled only once the previous
     * one resolves, so two can never be in flight and a slow response cannot stack a queue behind
     * it. Against a three-second response, setInterval(..., 2000) grows one forever.
     *
     * The <pre> is server-rendered and never hidden; this only appends to its text. With scripting
     * off the screen still shows the log as of page load and the "Refresh" link is the poll.
     */

    var jobLog = document.getElementById('job-log');

    if (jobLog && jobLog.getAttribute('data-terminal') !== 'true') {
        var jobId = jobLog.getAttribute('data-job');
        var jobNote = document.getElementById('job-note');
        var jobBadge = document.getElementById('job-badge');
        var jobSince = parseInt(jobLog.getAttribute('data-since'), 10) || 0;
        var jobSeq = 0;

        pollJob();
    }

    function pollJob() {
        var mine = ++jobSeq;

        fetch(MGMT + '/jobs/' + encodeURIComponent(jobId) + '/status?since=' + jobSince,
                {credentials: 'same-origin', headers: {'Accept': 'application/json'}})
            .then(readJson)
            .then(function (data) {
                // Same guard as the image library: an older response never paints over a newer one.
                if (mine !== jobSeq) {
                    return;
                }

                if (data.replace) {
                    // The stored log keeps only its end, and this page's copy fell behind it:
                    // take the whole window rather than append a gap.
                    jobLog.textContent = data.logChunk || '';
                    jobSince = data.logLength;
                    jobLog.scrollTop = jobLog.scrollHeight;
                } else if (data.logChunk) {
                    // textContent, never innerHTML: this is a job's log and it may contain markup.
                    jobLog.textContent += data.logChunk;
                    jobSince = data.logLength;
                    jobLog.scrollTop = jobLog.scrollHeight;
                }

                paintJobStatus(data);

                if (data.terminal) {
                    // Stop. The poll only paints the log and the badge -- the duration, the exit
                    // code and the counters are rendered server-side and want a reload.
                    note(jobNote, t('job.done'), false);
                    return;
                }

                window.setTimeout(pollJob, 2000);
            })
            .catch(function (error) {
                if (mine !== jobSeq) {
                    return;
                }
                // Stop, and deliberately without a retry. This is where an expired session lands:
                // the mgmt chain answers a 302 to the login page rather than a 401, readJson
                // refuses it, and a loop that rescheduled here would poll a login page every two
                // seconds for as long as the tab stayed open. "Refresh" is the recovery, and it
                // works with or without this script.
                note(jobNote, error.message, true);
            });
    }

    /** Badge text and colour only; everything else on the screen is server-rendered. */
    function paintJobStatus(data) {
        if (!jobBadge || !data.statusLabel) {
            return;
        }
        jobBadge.textContent = data.statusLabel;
        jobBadge.className = 'badge ' + (data.badgeClass || '');
    }

    /**
     * Reads a fetch response, refusing anything that is not the JSON it asked for.
     *
     * An expired session does not answer 401 here: the mgmt chain redirects to the login page, so
     * the response is a 302 followed by 200 text/html. Calling .json() on that throws a parse
     * error nobody can act on, and the picker would simply stop responding -- which is precisely
     * why `make smoke` prints what /mgmt/ redirects to rather than only its status code.
     */
    function readJson(response) {
        var type = response.headers.get('content-type') || '';

        // A redirect that landed on HTML is the login page, and there is only one way back.
        if (response.redirected) {
            throw new Error(t('request.sessionExpired'));
        }

        // Anything else non-JSON is an error page -- a 404 or the container's own 500.
        if (type.indexOf('json') === -1) {
            throw new Error(t('request.failed', response.status));
        }

        return response.json().then(function (data) {
            if (!response.ok) {
                throw new Error(data && data.error
                        ? data.error
                        : t('request.failed', response.status));
            }
            return data;
        });
    }

    /* --------------------------------------------------------------------- layout preview
     *
     * The banner layout screen previews the folder by framing the file the site actually serves.
     *
     * Click to load, never on render, and that is not a performance choice: both vendored
     * skeletons carry a Google Tag Manager block, so every render of this screen would send a
     * pageview to the advertiser's container from inside the console -- counted as traffic from
     * the site, on a screen the operator opens after every save. One click, one pageview, and the
     * card says so.
     *
     * It frames the published file rather than a GTM-free copy, because a preview that is not the
     * published file is not a preview.
     *
     * The button is rendered visible rather than hidden-and-revealed: with scripting off it does
     * nothing, but the «Отвори в нов раздел» link beside it is a plain anchor and still works, so
     * nothing here is a control that needs JavaScript to mean anything.
     */

    var previewBox = document.getElementById('layout-preview');
    var previewLoad = document.getElementById('layout-preview-load');

    if (previewBox && previewLoad) {
        previewLoad.addEventListener('click', function () {
            var src = previewBox.getAttribute('data-src');
            if (!src) {
                return;
            }

            var width = parseInt(previewBox.getAttribute('data-width'), 10);
            var height = parseInt(previewBox.getAttribute('data-height'), 10);

            var frame = document.createElement('iframe');
            frame.src = src;
            frame.title = t('layout.preview');
            frame.style.border = '1px solid var(--border)';
            frame.style.borderRadius = 'var(--r-sm)';
            frame.style.background = '#fff';
            // A fluid layout -- the top leaderboard -- declares no size and takes the frame it is
            // given on the site. 728x90 is what that frame is, so it is what the preview shows.
            frame.width = width > 0 ? width : 728;
            frame.height = height > 0 ? height : 90;

            previewBox.textContent = '';
            previewBox.appendChild(frame);
            previewLoad.disabled = true;
        });
    }

    /* ------------------------------------------------------------------------ live readers
     *
     * The one number on the monitoring screen that is not a window aggregate.
     *
     * Everything else there changes once a flush interval at most, and re-running eight queries
     * to refresh one figure would be the wrong trade -- so this polls a single endpoint and
     * repaints a single element. The value is ALSO server-rendered: with scripting off the screen
     * simply shows it as of page load, which is the contract every enhancement in this file keeps.
     *
     * setTimeout chaining, not setInterval, for the reason the job poll gives above.
     *
     * The interval matches the site's flush (60s), because that is how often the
     * underlying number can actually change. Polling faster would be requests that cannot return
     * anything new. It also pauses while the tab is hidden -- this is a screen people leave open.
     *
     * A failure STOPS the loop rather than retrying. readJson refuses a redirect, which is what an
     * expired console session answers with, and a loop that kept going would hammer the login page
     * for as long as the tab stayed open.
     */

    var liveCount = document.getElementById('live-count');

    if (liveCount) {
        window.setTimeout(pollLive, 60000);
    }

    function pollLive() {
        if (document.hidden) {
            window.setTimeout(pollLive, 60000);
            return;
        }

        fetch(MGMT + '/monitoring/live', {credentials: 'same-origin', headers: {'Accept': 'application/json'}})
            .then(readJson)
            .then(function (data) {
                // `available` is false when the query failed. Showing 0 there would read as a
                // quiet moment rather than as a broken screen, which is the whole reason the
                // server sends the flag instead of a number it had to invent.
                liveCount.textContent = data.available ? data.active : '—';
                window.setTimeout(pollLive, 60000);
            })
            .catch(function () {
                // Leave the last known value on screen; a stale number beats a blank one, and the
                // page reload the operator is about to do is the recovery.
            });
    }

    /* ----------------------------------------------------- /mgmt/dashboard live tiles */

    /*
     * The dashboard's two "right now" tiles: readers on the site and operators in the console.
     * The monitoring poll above, cloned rather than shared, because it answers a different URL
     * with a different shape -- one request refreshes both tiles here.
     *
     * Same rules, for the same reasons: setTimeout chaining, paused while the tab is hidden, and a
     * failure stops the loop. The poll itself is NOT counted as console activity on the server
     * (MgmtPresenceInterceptor), so an operator who leaves this tab open drops off the tile after
     * the window like anyone else.
     */

    var dashReaders = document.getElementById('dash-readers');
    var dashAdmins = document.getElementById('dash-admins');
    var dashAdminNames = document.getElementById('dash-admin-names');

    if (dashReaders && dashAdmins && dashAdminNames) {
        window.setTimeout(pollDashboard, 60000);
    }

    function pollDashboard() {
        if (document.hidden) {
            window.setTimeout(pollDashboard, 60000);
            return;
        }

        fetch(MGMT + '/dashboard/live', {credentials: 'same-origin', headers: {'Accept': 'application/json'}})
            .then(readJson)
            .then(function (data) {
                // «—», never 0, for a failed count: see the monitoring poll above.
                dashReaders.textContent = data.readers.available ? data.readers.active : '—';

                var names = data.admins.names || [];
                dashAdmins.textContent = names.length;
                dashAdminNames.textContent = names.length ? names.join(', ') : dashAdminNames.dataset.none;
                window.setTimeout(pollDashboard, 60000);
            })
            .catch(function () {
                // Leave the last known values on screen, as the monitoring poll does.
            });
    }

    /* ----------------------------------------------------- /mgmt/social link cards */

    /*
     * Adding a link, ↑/↓ and on/off on /mgmt/social, done IN PLACE.
     *
     * The page holds the profile form (#article-form) above the link cards, and the dirty tracker
     * arms the leave-page prompt for any unsaved edit there. As navigations -- «Нов линк» a link
     * to a screen of its own, the row buttons a POST answered by a redirect -- every one of these
     * would raise "leave this page?" over an unsaved bio, and declining it would look like a
     * button that did nothing. So the card's forms are posted by fetch with the X-Mgmt-Fragment
     * header, the same URL answers with the card re-rendered by the server, and the card is
     * swapped: the page never unloads, and the typed bio is still there.
     *
     * An enhancement, not a replacement. Every one of these forms keeps its action and works with
     * scripting off as a plain POST answered by a redirect back to its card -- which is also why
     * the answer is server markup rather than a row built here: a card swapped in place is drawn
     * by the same fragment a reload draws.
     *
     * Delegated from the document, because the card it binds to is replaced by every answer. It
     * runs after the data-confirm handler above, and respects its refusal.
     */
    if (document.querySelector('[data-social-card]') && window.fetch && window.FormData) {
        document.addEventListener('submit', function (event) {
            var form = event.target;
            if (event.defaultPrevented || !form.matches || !form.matches('form[data-social-links]')) {
                return;
            }
            var card = form.closest('[data-social-card]');
            if (!card) {
                return;
            }
            event.preventDefault();
            if (card.getAttribute('aria-busy') === 'true') {
                return;
            }

            var headers = window.mgmtCsrf();
            headers['X-Mgmt-Fragment'] = 'social-links';
            card.setAttribute('aria-busy', 'true');

            fetch(form.getAttribute('action'), {
                method: 'POST',
                headers: headers,
                body: new FormData(form),
                credentials: 'same-origin'
            })
                .then(function (response) {
                    // An expired session answers with a redirect to the sign-in page, which fetch
                    // follows and hands back as a 200 of the wrong page -- readJson's rule.
                    if (response.redirected) {
                        throw new Error(t('request.sessionExpired'));
                    }
                    // 422 is a refusal that still carries the card: the add form open, the typed
                    // values in it and the reason above them. Anything else not ok has no card.
                    if (!response.ok && response.status !== 422) {
                        throw new Error(t('request.failed', response.status));
                    }
                    return response.text();
                })
                .then(function (html) {
                    var template = document.createElement('template');
                    template.innerHTML = html.trim();
                    var fresh = template.content.querySelector('[data-social-card]');
                    if (!fresh) {
                        throw new Error(t('social.noList'));
                    }
                    // Same class the row handler adds at load; the click handler itself is
                    // delegated from the document and needs nothing re-bound.
                    Array.prototype.forEach.call(fresh.querySelectorAll('tr[data-href]'), function (row) {
                        row.classList.add('row-link');
                    });
                    card.replaceWith(fresh);
                    var focus = fresh.querySelector('details[open] input[name="url"]')
                            || (form.closest('details') ? fresh.querySelector('summary') : null);
                    if (focus) {
                        focus.focus();
                    }
                })
                .catch(function (error) {
                    card.removeAttribute('aria-busy');
                    var status = card.querySelector('[data-social-status]');
                    if (status) {
                        status.textContent = error.message;
                        status.classList.add('err');
                    }
                });
        });
    }
    /* ---------------------------------------------------------------- layout editor */

    /*
     * Drag and drop over design-layout.html's region lists: a section is dragged by its grip
     * within its region or into another, and the new order -- every region's section ids -- is
     * posted to the list's data-arrange address, then the page reloads (the server may flag what
     * the move changed). The ↑/↓ and "move to" forms remain the path without scripting, and the
     * server refuses an order that does not name every section exactly once (a stale page).
     */
    (function () {
        var lists = Array.prototype.slice.call(document.querySelectorAll('.region-list[data-arrange]'));
        if (!lists.length || !window.fetch || !window.mgmtCsrf) {
            return;
        }
        var dragged = null;

        function cards(list) {
            return Array.prototype.slice.call(list.querySelectorAll(':scope > .section-card'));
        }

        function after(list, y) {
            var candidates = cards(list).filter(function (c) { return c !== dragged; });
            for (var i = 0; i < candidates.length; i++) {
                var box = candidates[i].getBoundingClientRect();
                if (y < box.top + box.height / 2) {
                    return candidates[i];
                }
            }
            return null;
        }

        function save(url) {
            var order = {};
            lists.forEach(function (list) {
                order[list.getAttribute('data-region')] = cards(list).map(function (c) {
                    return c.getAttribute('data-id');
                });
            });
            var headers = window.mgmtCsrf();
            headers['Content-Type'] = 'application/json';
            headers.Accept = 'application/json';
            fetch(url, { method: 'POST', headers: headers, credentials: 'same-origin', body: JSON.stringify(order) })
                .then(function (response) {
                    if (!response.ok || response.redirected) {
                        throw new Error(String(response.status));
                    }
                    window.location.reload();
                })
                .catch(function () {
                    window.alert(t('design.arrangeFailed'));
                    window.location.reload();
                });
        }

        lists.forEach(function (list) {
            cards(list).forEach(function (card) {
                var grip = card.querySelector('.section-card__grip');
                if (!grip) {
                    return;
                }
                grip.setAttribute('draggable', 'true');
                grip.setAttribute('title', t('design.drag'));
                grip.addEventListener('dragstart', function (event) {
                    dragged = card;
                    card.classList.add('is-dragging');
                    event.dataTransfer.effectAllowed = 'move';
                    event.dataTransfer.setData('text/plain', card.getAttribute('data-id'));
                    if (event.dataTransfer.setDragImage) {
                        event.dataTransfer.setDragImage(card, 16, 16);
                    }
                });
                grip.addEventListener('dragend', function () {
                    card.classList.remove('is-dragging');
                    dragged = null;
                });
            });
            list.addEventListener('dragover', function (event) {
                if (!dragged) {
                    return;
                }
                event.preventDefault();
                var next = after(list, event.clientY);
                if (next) {
                    list.insertBefore(dragged, next);
                } else {
                    list.appendChild(dragged);
                }
            });
            list.addEventListener('drop', function (event) {
                if (!dragged) {
                    return;
                }
                event.preventDefault();
                save(list.getAttribute('data-arrange'));
            });
        });
    }());

}());

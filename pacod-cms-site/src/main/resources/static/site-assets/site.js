/* =====================================================================================
   Public-site behaviour. Five things only; everything else in the design is CSS.

   Loaded with `defer`. The theme class is NOT set here -- it is set by the inline snippet
   in fragments/head.html, before first paint. Setting it from a deferred script would show
   the light theme for a frame on every page load, and this is a full page-load site.
   ===================================================================================== */
(function () {
    'use strict';

    var STORAGE_KEY = 'pacod-theme';

    /* Past this many images a row of dots is a smear; the strip gets a "3 / 24" counter. */
    var MAX_DOTS = 10;

    /* ---- night / day ---------------------------------------------------------------- */

    function isNight() {
        return document.documentElement.classList.contains('night');
    }

    function label(button) {
        var night = isNight();
        button.textContent = night ? '☾' : '☀';
        var title = button.getAttribute(night ? 'data-title-night' : 'data-title-day');
        if (title) {
            button.setAttribute('title', title);
            button.setAttribute('aria-label', title);
        }
        button.setAttribute('aria-pressed', night ? 'true' : 'false');
    }

    function initTheme() {
        var button = document.getElementById('theme-toggle');
        if (!button) {
            return;
        }

        label(button);

        button.addEventListener('click', function () {
            var night = !isNight();
            document.documentElement.classList.toggle('night', night);
            try {
                localStorage.setItem(STORAGE_KEY, night ? 'night' : 'day');
            } catch (e) {
                /* private mode: the choice simply does not persist */
            }
            label(button);
        });
    }

    /* ---- navigation ------------------------------------------------------------------ */

    /* The mega-menus open on :hover / :focus-within, so no script is involved on desktop.
       Below the 860px breakpoint the whole bar collapses behind one button. */
    function initNav() {
        var nav = document.getElementById('site-nav');
        var toggle = document.getElementById('nav-toggle');
        if (!nav || !toggle) {
            return;
        }

        toggle.addEventListener('click', function () {
            var open = nav.classList.toggle('is-open');
            toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
        });
    }

    /* ---- article image carousel ------------------------------------------------------ */

    /* The gallery is a scroll-snapped strip that already works with no JavaScript -- see the
       .gallery block in site.css. This upgrades it in place: arrows, dots, keyboard.

       Two rules, and both are load-bearing:

         * The controls are built HERE, never written into the template. Prev/next markup in
           the template shows arrows that do nothing wherever this script does not run --
           controls that exist without the code driving them are the exact bug. Injecting them
           means no JavaScript gets you the strip, unchanged.
         * Nothing is ever hidden. The carousel is the scroll POSITION, not visibility. Every
           image stays in the strip and stays reachable by swipe, drag and keyboard whatever
           the buttons do. A carousel that sets images 2..n to display:none leaves them with no
           way to be reached once its controls fail, and no template test can see that happen.

       The controls' labels are rendered by the template, in the page's language, as
       data-label-* attributes on the strip -- the server owns every word of chrome. */
    function initGallery() {
        var gallery = document.querySelector('.gallery');
        if (!gallery) {
            return;                                  /* an embedded video renders instead */
        }

        var items = Array.prototype.slice.call(gallery.querySelectorAll('.gallery__item'));
        if (items.length < 2) {
            return;                                  /* one image is a picture, not a carousel */
        }

        /* The labels come from the page, in its language -- see fragments/article-view.html.
           The English fallbacks are for markup without them. */
        function label(name, fallback) {
            return gallery.getAttribute('data-label-' + name) || fallback;
        }

        var intent = 0;      /* the slide being shown, or being scrolled to */
        var settling = null;

        function control(className, glyph, title) {
            var element = document.createElement('button');
            element.type = 'button';                 /* not inside a form, but say so anyway */
            element.className = className;
            element.textContent = glyph;
            element.setAttribute('aria-label', title);
            element.setAttribute('title', title);
            return element;
        }

        /* Where scrollLeft has to be for slide i to sit at the left edge -- read off layout,
           never computed as index * width: the strip carries a 10px gap between slides, so the
           two disagree, and a resize changes the column width under both.

           All three terms are needed, and the last two are the ones that look redundant.
           offsetLeft is relative to .carousel__frame, the offsetParent of the gallery and its
           items alike, so subtracting the gallery's own offsetLeft gives the distance from its
           border box; scrollLeft is measured from the padding box, hence clientLeft. What is
           left over is the gallery's left padding -- zero under .is-carousel, which strips it,
           but 10px in the plain strip, and the difference is not visible in a diff. */
        function offsetOf(i) {
            return items[i].offsetLeft - gallery.offsetLeft - gallery.clientLeft;
        }

        function nearest() {
            var best = 0;
            var shortest = Infinity;
            for (var i = 0; i < items.length; i++) {
                var distance = Math.abs(offsetOf(i) - gallery.scrollLeft);
                if (distance < shortest) {
                    shortest = distance;
                    best = i;
                }
            }
            return best;
        }

        /* Moves to slide i, and takes the controls with it immediately.

           A smooth scroll fires scroll events the whole way, and letting those drive `intent`
           would put the *next* click's starting point on whichever slide happened to be
           passing -- so clicking the arrow twice in quick succession would advance one slide,
           not two. While a scroll this function started is in flight the position is therefore
           ignored, and the controls show where it is going rather than where it is.

           The in-flight window is a timer, not "scrollLeft reached the target": mid-flight the
           position tells you nothing about which way it is heading, and a scroll that gets
           clamped -- which is what happens on the last slide if the trailing padding ever
           comes back -- would never look arrived at all. */
        function go(i) {
            intent = Math.max(0, Math.min(items.length - 1, i));

            var target = offsetOf(intent);
            var smooth = !(window.matchMedia
                && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
            if (gallery.scrollTo) {
                gallery.scrollTo({ left: target, behavior: smooth ? 'smooth' : 'auto' });
            } else {
                gallery.scrollLeft = target;
            }

            window.clearTimeout(settling);
            settling = window.setTimeout(function () { settling = null; }, 700);
            render(intent);
        }

        var frame = document.createElement('div');
        frame.className = 'carousel__frame';

        var carousel = document.createElement('div');
        carousel.className = 'carousel';
        carousel.setAttribute('role', 'group');
        carousel.setAttribute('aria-label', label('gallery', 'Article images'));

        gallery.parentNode.insertBefore(carousel, gallery);
        frame.appendChild(gallery);
        carousel.appendChild(frame);

        gallery.classList.add('is-carousel');
        gallery.setAttribute('tabindex', '0');       /* a scroll region should be reachable */

        var previous = control('carousel__nav carousel__nav--prev', '‹',
                label('previous', 'Previous image'));
        var next = control('carousel__nav carousel__nav--next', '›',
                label('next', 'Next image'));

        previous.addEventListener('click', function () { go(intent - 1); });
        next.addEventListener('click', function () { go(intent + 1); });
        frame.appendChild(previous);
        frame.appendChild(next);

        var dots = document.createElement('div');
        dots.className = 'carousel__dots';
        var buttons = [];
        var counter = null;

        if (items.length <= MAX_DOTS) {
            items.forEach(function (item, i) {
                var dot = control('carousel__dot', '',
                        label('image', 'Image {0}').replace('{0}', String(i + 1)));
                dot.addEventListener('click', function () { go(i); });
                dots.appendChild(dot);
                buttons.push(dot);
            });
        } else {
            counter = document.createElement('span');
            counter.className = 'carousel__count';
            dots.appendChild(counter);
        }
        carousel.appendChild(dots);

        function render(active) {
            /* Disabling the button under the pointer would drop focus on the last click of a
               run through the strip, so hand it to the arrow that is still live. */
            var focused = document.activeElement;
            previous.disabled = active === 0;
            next.disabled = active === items.length - 1;
            if (focused === previous && previous.disabled) {
                next.focus();
            } else if (focused === next && next.disabled) {
                previous.focus();
            }

            buttons.forEach(function (dot, i) {
                dot.setAttribute('aria-current', i === active ? 'true' : 'false');
            });
            if (counter) {
                counter.textContent = (active + 1) + ' / ' + items.length;
            }
        }

        /* Swipe, drag, trackpad and the scrollbar all arrive here and nowhere else, which is
           what keeps the controls honest about a strip the reader moved themselves. */
        var pending = null;
        gallery.addEventListener('scroll', function () {
            if (pending !== null) {
                return;
            }
            pending = window.requestAnimationFrame(function () {
                pending = null;
                if (settling) {
                    return;                          /* our own scroll; go() has rendered it */
                }
                intent = nearest();
                render(intent);
            });
        }, { passive: true });

        /* A resize moves every offset, and the snap points with them, so re-reading which
           slide is under the scroll position is all that is needed. */
        window.addEventListener('resize', function () {
            if (!settling) {
                intent = nearest();
                render(intent);
            }
        });

        carousel.addEventListener('keydown', function (event) {
            if (event.key === 'ArrowLeft') {
                event.preventDefault();
                go(intent - 1);
            } else if (event.key === 'ArrowRight') {
                event.preventDefault();
                go(intent + 1);
            }
        });

        render(nearest());
    }

    /* ---- partner measurement --------------------------------------------------------- */

    /* Two numbers the server cannot see for itself, each reported by sendBeacon -- which is
       fire-and-forget and survives the navigation a click starts. Every creative frame carries
       data-partner="<booking id>"; see the header of fragments/ad-sidebar.html.

       Viewable: at least half of the creative on screen, in a visible tab, for one continuous
       second -- and only once the creative has actually LOADED, so a booking whose folder is
       missing collects nothing for its blank frame. Each booking is queued once per
       page view, matching the server's served count, and the queue goes out as one POST /b/v.

       HTML5 clicks: the iframe is same-origin and unsandboxed, so its document is ours to listen
       in. ANY click inside the creative counts -- the usual meaning of an iframe click, and the
       only one that covers a canvas export navigating through window.open(clickTag). Image
       banners are NOT hooked: their link goes through GET /b/{id}, which counts on the server,
       and one click path per type is what keeps a click from counting twice. */

    var VIEW_RATIO = 0.5;
    var VIEW_MS = 1000;
    var BATCH_MS = 2000;
    var CLICK_GAP_MS = 1000;

    function initPartners() {
        var frames = Array.prototype.slice.call(document.querySelectorAll('[data-partner]'));
        // The studio's draft preview counts for nothing, and a beacon carries no token to say so.
        if (!frames.length || !navigator.sendBeacon || document.querySelector('meta[name="pacod-preview"]')) {
            return;
        }

        var queued = {};
        var batch = [];
        var batchTimer = null;

        function send() {
            clearTimeout(batchTimer);
            batchTimer = null;
            if (batch.length) {
                navigator.sendBeacon('/b/v', batch.join(','));
                batch = [];
            }
        }

        function queue(id) {
            if (!id || queued[id]) {
                return;
            }
            queued[id] = true;
            batch.push(id);
            clearTimeout(batchTimer);
            batchTimer = setTimeout(send, BATCH_MS);
        }

        /* A blank frame is not an impression anybody saw. */
        function loaded(frame) {
            if (frame.tagName === 'A') {
                var img = frame.querySelector('img');
                return !!img && img.complete && img.naturalWidth > 0;
            }
            return frame.rzHooked === true;
        }

        var observer = 'IntersectionObserver' in window
            ? new IntersectionObserver(onIntersect, { threshold: [0, VIEW_RATIO] })
            : null;

        function cancel(frame) {
            clearTimeout(frame.rzTimer);
            frame.rzTimer = null;
        }

        function onIntersect(entries) {
            entries.forEach(function (entry) {
                var frame = entry.target;
                if (entry.intersectionRatio < VIEW_RATIO || document.hidden || !loaded(frame)) {
                    cancel(frame);
                    return;
                }
                if (!frame.rzTimer) {
                    frame.rzTimer = setTimeout(function () {
                        frame.rzTimer = null;
                        observer.unobserve(frame);
                        frame.rzCounted = true;
                        queue(frame.getAttribute('data-partner'));
                    }, VIEW_MS);
                }
            });
        }

        /* Re-observing delivers a fresh initial callback, which re-applies every condition above. */
        function recheck(frame) {
            if (observer && !frame.rzCounted) {
                observer.unobserve(frame);
                observer.observe(frame);
            }
        }

        function hook(frame, iframe) {
            var doc;
            try {
                doc = iframe.contentDocument;
            } catch (e) {
                return; /* off-origin: nothing to listen in, and nothing counted */
            }
            if (!doc || !doc.documentElement || doc.URL === 'about:blank' || doc === iframe.rzDoc) {
                return;
            }
            iframe.rzDoc = doc;

            var id = frame.getAttribute('data-partner');
            function hit() {
                var now = Date.now();
                if (now - (frame.rzLastClick || 0) < CLICK_GAP_MS) {
                    return;
                }
                frame.rzLastClick = now;
                navigator.sendBeacon('/b/' + encodeURIComponent(id) + '/c');
            }

            doc.addEventListener('click', hit, true);
            /* auxclick also fires on the right button; a context menu is not an ad click. */
            doc.addEventListener('auxclick', function (event) {
                if (event.button === 1) {
                    hit();
                }
            }, true);

            frame.rzHooked = true;
            recheck(frame);
        }

        frames.forEach(function (frame) {
            var iframe = frame.querySelector('iframe');
            if (iframe) {
                /* Both: a lazy frame loads later, a top one may have loaded before this ran. */
                iframe.addEventListener('load', function () {
                    hook(frame, iframe);
                });
                hook(frame, iframe);
            } else {
                var img = frame.querySelector('img');
                if (img && !loaded(frame)) {
                    img.addEventListener('load', function () {
                        recheck(frame);
                    });
                }
            }
            if (observer) {
                observer.observe(frame);
            }
        });

        document.addEventListener('visibilitychange', function () {
            if (document.hidden) {
                frames.forEach(cancel);
                send();
            } else {
                frames.forEach(recheck);
            }
        });
        window.addEventListener('pagehide', send);
    }

    /* ---- consent and the tag manager -------------------------------------------------- */

    /* A website with a Tag Manager container renders the consent banner (fragments/consent),
       hidden, with the container id as data-gtm. This is the ONLY code that loads the container,
       and it does so only after the reader has agreed to analytics or marketing. Nothing from
       Google is in the page the server sends.

       The choice is one first-party cookie, cookie_consent: URI-encoded JSON,
       {"v":1,"analytics":true,"marketing":false}. Any other value -- another version, a
       cookie of that name another application on the same host left behind, anything that
       does not parse -- counts as no choice, and the banner asks again. Raise CONSENT_VERSION
       when what a category covers changes, and every reader is asked again.

       Google's own consent signals start denied before the container loads, then follow the
       choice: analytics grants analytics_storage, marketing the three ad signals. With neither
       granted the container is never loaded at all.

       A withdrawal cannot unload a tag already running in the page: tags already loaded stay
       until the next page. So a withdrawal clears the cookies of what was withdrawn that the
       page can reach and reloads at once, and the reloaded page loads only what is still
       allowed. Granting needs no reload. */

    var CONSENT_COOKIE = 'cookie_consent';
    var CONSENT_VERSION = 1;
    var CONSENT_MAX_AGE = 15552000;                  /* 180 days, in seconds */
    var PREFERENCES = 'a[href="#cookie-preferences"]';

    /* Google's first-party cookies, by the choice that allowed them. */
    var OPTIONAL_COOKIES = {
        analytics: /^(_ga|_ga_.+|_gid|_gat.*)$/,
        marketing: /^(_gcl_.+|_gac_.+)$/
    };

    function readConsent() {
        var match = document.cookie.match(/(?:^|;\s*)cookie_consent=([^;]*)/);
        if (!match) {
            return null;
        }
        try {
            var stored = JSON.parse(decodeURIComponent(match[1]));
            if (!stored || stored.v !== CONSENT_VERSION
                    || typeof stored.analytics !== 'boolean' || typeof stored.marketing !== 'boolean') {
                return null;
            }
            return { analytics: stored.analytics, marketing: stored.marketing };
        } catch (e) {
            return null;
        }
    }

    function secure() {
        return window.location.protocol === 'https:' ? '; Secure' : '';
    }

    function writeConsent(choice) {
        var value = encodeURIComponent(JSON.stringify(
                { v: CONSENT_VERSION, analytics: choice.analytics, marketing: choice.marketing }));
        document.cookie = CONSENT_COOKIE + '=' + value + '; Path=/; Max-Age=' + CONSENT_MAX_AGE
                + '; SameSite=Lax' + secure();
    }

    /* Deletes the readable cookies of each withdrawn choice, on this host and on every domain
       above it: Google sets them on the registrable domain (.example.com from www.example.com),
       and a browser simply ignores a delete aimed at a public suffix, so no list of suffixes is
       needed. A cookie a provider keeps on its own domain is beyond any page's reach. */
    function clearCookies(withdrawn) {
        var host = window.location.hostname;
        var domains = [''];
        if (!/^[\d.]+$/.test(host) && host.indexOf(':') < 0) {
            var labels = host.split('.');
            for (var i = 0; i < labels.length - 1; i++) {
                domains.push('; Domain=' + labels.slice(i).join('.'));
            }
        }
        document.cookie.split(';').forEach(function (pair) {
            var name = pair.split('=')[0].trim();
            var optional = withdrawn.some(function (category) {
                return OPTIONAL_COOKIES[category].test(name);
            });
            if (optional) {
                domains.forEach(function (domain) {
                    document.cookie = name + '=; Path=/; Max-Age=0; SameSite=Lax' + domain + secure();
                });
            }
        });
    }

    /* Google's own queue. gtag() must push the arguments object itself, not an array made of
       it: the consent commands are read off it as gtag.js writes them. */
    function gtag() {
        window.dataLayer.push(arguments);
    }

    function initConsent() {
        var banner = document.getElementById('consent');
        var container = banner ? banner.getAttribute('data-gtm') : null;
        if (!container) {
            return;                                  /* no container: nothing to ask about */
        }

        var choices = document.getElementById('consent-choices');
        var boxes = {
            analytics: banner.querySelector('[data-consent-category="analytics"]'),
            marketing: banner.querySelector('[data-consent-category="marketing"]')
        };
        var chooseButton = banner.querySelector('[data-consent-action="choose"]');
        var saveButton = banner.querySelector('[data-consent-action="save"]');
        if (!choices || !boxes.analytics || !boxes.marketing || !chooseButton || !saveButton) {
            return;                                  /* a banner without its parts asks nothing */
        }

        var current = readConsent();                 /* the choice this page runs under */
        var defaulted = false;
        var loaded = false;
        var opener = null;

        function apply(choice) {
            var granted = choice.analytics || choice.marketing;
            if (!granted && !loaded) {
                return;                              /* nothing to tell and nothing to load */
            }
            window.dataLayer = window.dataLayer || [];
            if (!defaulted) {
                gtag('consent', 'default', {
                    ad_storage: 'denied',
                    ad_user_data: 'denied',
                    ad_personalization: 'denied',
                    analytics_storage: 'denied',
                    functionality_storage: 'granted',
                    security_storage: 'granted',
                    wait_for_update: 500
                });
                defaulted = true;
            }
            var ads = choice.marketing ? 'granted' : 'denied';
            gtag('consent', 'update', {
                ad_storage: ads,
                ad_user_data: ads,
                ad_personalization: ads,
                analytics_storage: choice.analytics ? 'granted' : 'denied'
            });
            if (granted && !loaded) {
                window.dataLayer.push({ 'gtm.start': new Date().getTime(), event: 'gtm.js' });
                var script = document.createElement('script');
                script.async = true;
                script.src = 'https://www.googletagmanager.com/gtm.js?id=' + encodeURIComponent(container);
                document.head.appendChild(script);
                loaded = true;
            }
        }

        function expand(open) {
            choices.hidden = !open;
            saveButton.hidden = !open;
            chooseButton.setAttribute('aria-expanded', open ? 'true' : 'false');
        }

        /* A reader coming back (the footer's link, a policy page's button) finds the choices
           open, set as they stand, and the focus on the first; a first visit finds the banner
           closed, the boxes unticked, and the focus left where it was. */
        function show(link) {
            var stored = current || { analytics: false, marketing: false };
            boxes.analytics.checked = stored.analytics;
            boxes.marketing.checked = stored.marketing;
            expand(!!link);
            banner.hidden = false;
            if (link) {
                opener = link;
                boxes.analytics.focus();
            }
        }

        function hide() {
            banner.hidden = true;
            if (opener) {
                opener.focus();
                opener = null;
            }
        }

        function decide(choice) {
            var withdrawn = [];
            if (current && current.analytics && !choice.analytics) {
                withdrawn.push('analytics');
            }
            if (current && current.marketing && !choice.marketing) {
                withdrawn.push('marketing');
            }
            writeConsent(choice);
            current = choice;
            apply(choice);                           /* tags running now stop writing at once */
            if (withdrawn.length) {
                clearCookies(withdrawn);
                window.location.reload();
                return;
            }
            hide();
        }

        banner.addEventListener('click', function (event) {
            var button = event.target.closest ? event.target.closest('[data-consent-action]') : null;
            if (!button) {
                return;
            }
            var action = button.getAttribute('data-consent-action');
            if (action === 'all') {
                decide({ analytics: true, marketing: true });
            } else if (action === 'necessary') {
                decide({ analytics: false, marketing: false });
            } else if (action === 'save') {
                decide({ analytics: boxes.analytics.checked, marketing: boxes.marketing.checked });
            } else if (action === 'choose') {
                expand(choices.hidden);
            }
        });

        /* Escape puts the banner away unchanged -- once there is a choice to keep. */
        banner.addEventListener('keydown', function (event) {
            if (event.key === 'Escape' && current) {
                event.preventDefault();
                hide();
            }
        });

        /* The footers' link and any link to #cookie-preferences in a page's body, added later
           or not. The footers' is hidden until here: without this code it would do nothing. */
        Array.prototype.forEach.call(document.querySelectorAll(PREFERENCES), function (link) {
            link.hidden = false;
        });
        document.addEventListener('click', function (event) {
            var link = event.target.closest ? event.target.closest(PREFERENCES) : null;
            if (link) {
                event.preventDefault();
                show(link);
            }
        });

        if (current) {
            apply(current);
        } else {
            show(null);
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function () {
            initConsent();
            initTheme();
            initNav();
            initGallery();
            initPartners();
        });
    } else {
        initConsent();
        initTheme();
        initNav();
        initGallery();
        initPartners();
    }
})();

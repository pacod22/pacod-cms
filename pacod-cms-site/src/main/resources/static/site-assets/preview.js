/* The studio's draft preview (SitePreview). Readers never load this file: the head links it only
   for a page asked for with ?_preview=<token>.

   The studio shows the preview in a frame on another site, where third-party cookies are blocked,
   so the address is the one thing that can carry the preview from page to page. Every same-origin
   link and GET form gets the token -- links the server built (menus, pagers, the language switch)
   and links the page's own scripts add later alike, the latter at the moment they are clicked.
   A page reached without it would be the published one, with nothing to say so. */
(function () {
    'use strict';

    var meta = document.querySelector('meta[name="pacod-preview"]');
    if (!meta) {
        return;
    }
    var PARAM = '_preview';
    var token = meta.getAttribute('content');

    function carried(address) {
        var url;
        try {
            url = new URL(address, location.href);
        } catch (e) {
            return null;
        }
        if (url.origin !== location.origin || (url.protocol !== 'http:' && url.protocol !== 'https:')) {
            return null;
        }
        url.searchParams.set(PARAM, token);
        return url.pathname + url.search + url.hash;
    }

    function carryLink(a) {
        var href = a.getAttribute('href');
        if (!href || href.charAt(0) === '#') {
            return;
        }
        var next = carried(href);
        if (next) {
            a.setAttribute('href', next);
        }
    }

    function carryForm(form) {
        if ((form.getAttribute('method') || 'get').toLowerCase() !== 'get'
                || !carried(form.getAttribute('action') || location.href)
                || form.querySelector('input[name="' + PARAM + '"]')) {
            return;
        }
        var input = document.createElement('input');
        input.type = 'hidden';
        input.name = PARAM;
        input.value = token;
        form.appendChild(input);
    }

    Array.prototype.forEach.call(document.querySelectorAll('a[href]'), carryLink);
    Array.prototype.forEach.call(document.querySelectorAll('form'), carryForm);
    document.addEventListener('click', function (event) {
        var a = event.target && event.target.closest ? event.target.closest('a[href]') : null;
        if (a) {
            carryLink(a);
        }
    }, true);
    document.addEventListener('submit', function (event) {
        if (event.target && event.target.tagName === 'FORM') {
            carryForm(event.target);
        }
    }, true);

    var label = document.createElement('div');
    label.setAttribute('role', 'status');
    label.textContent = meta.getAttribute('data-label') || 'Draft preview';
    label.style.cssText = 'position:fixed;left:12px;bottom:12px;z-index:2147483647;max-width:calc(100% - 24px);'
        + 'padding:6px 10px;border-radius:6px;background:#1d1d1b;color:#fff;opacity:.88;pointer-events:none;'
        + 'font:500 12px/1.4 system-ui,-apple-system,"Segoe UI",sans-serif;box-shadow:0 2px 8px rgba(0,0,0,.25)';
    document.body.appendChild(label);
})();

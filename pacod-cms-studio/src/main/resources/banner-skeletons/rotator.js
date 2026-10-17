/*
 * Written to js/banner.js in every generated layout folder, replacing whatever the skeleton
 * carried. See BannerSkeleton's javadoc for why rotation belongs to the console rather than to a
 * skeleton: the hand-authored script this replaces declares its slide count in three places --
 * `modulo`, the `switch` and the `getElementById` block -- so a folder built for three creatives on
 * a two-slot script shows two of them forever, with a 200 response and nothing in any log.
 *
 * The slide count comes from the DOM and the interval from `data-speed` on the container, so this
 * file is identical in every folder and correct for any number of slides.
 *
 * Kept as a global `banner()` invoked from the page, rather than self-starting, because that is the
 * contract the skeletons' own markup already has -- and an operator reading a generated folder
 * beside a hand-authored one should find the same shape.
 */
function banner() {
    var box = document.querySelector(".banner-container-inline");
    if (!box) {
        return;
    }

    var slides = [];
    for (var i = 0; i < box.children.length; i++) {
        if (box.children[i].nodeName === "DIV") {
            slides.push(box.children[i]);
        }
    }

    /* One slide does not rotate, and zero has nothing to rotate. Either way, leave the DOM alone. */
    if (slides.length < 2) {
        return;
    }

    var speed = parseInt(box.getAttribute("data-speed"), 10);
    if (!speed || speed < 1000) {
        speed = 10000;
    }

    var index = 0;
    window.setInterval(function () {
        index = ++index % slides.length;
        for (var k = 0; k < slides.length; k++) {
            slides[k].style.display = k === index ? "inline-block" : "none";
        }
    }, speed);
}

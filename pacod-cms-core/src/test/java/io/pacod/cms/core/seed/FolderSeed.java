package io.pacod.cms.core.seed;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A small seed in a folder, with one of each thing a folder's seed may hold -- a language of its
 * own, absolute dates, a padded screenshot, a code block, an advertiser with an HTML5 creative,
 * brand files -- valid as written. A test breaks one thing in {@link #JSON} and sees it refused.
 */
final class FolderSeed {

    /** The screenshot: portrait, as a phone's is, so a crop would lose most of it. */
    static final int SHOT_WIDTH = 400;
    static final int SHOT_HEIGHT = 600;

    static final String JSON = """
            {
              "imagePrefix": "pacod-",
              "narrowLanguages": true,
              "languages": [{"code": "bg", "adminName": "Bulgarian", "nativeName": "Български", "sortOrder": 5,
                             "fallbackNotice": "Тази страница още не е преведена и е показана на {0}.",
                             "legalNotice": "Правно обвързващ е текстът на {0}."}],
              "categories": [{"id": 1, "names": {"en": "Gateway", "bg": "Шлюз"}},
                             {"id": 2, "names": {"en": "Platform", "bg": "Платформа"}}],
              "authors": [{"id": 1, "firstname": "The PACOD", "lastname": "team", "descriptions": {"en": "Builds it."}}],
              "tags": {"release": {}},
              "images": [{"key": "screen", "photo": "photos/screen.png", "fit": "pad", "background": "#0e6e57",
                          "captions": {"en": "The console"}, "alts": {"en": "The gateway's console"}},
                         {"key": "mark", "motif": "orbit", "colours": ["#0e6e57", "#fbfbf8"],
                          "captions": {"en": "The mark"}, "alts": {"en": "The PACOD mark"}}],
              "articles": [
                {"id": 1, "date": "2025-01-10T09:00", "categories": [2], "authors": [1], "images": ["mark"],
                 "tags": ["release"], "titles": {"en": "Introducing PACOD"}, "descriptions": {"en": "Six projects."},
                 "bodies": {"en": "<p>Six projects, one platform: <a href=\\"https://github.com/pacod22\\">on GitHub</a>.</p>"}},
                {"id": 2, "date": "2025-02-03T10:30", "modified": "2025-02-04T08:00", "categories": [1], "authors": [1],
                 "images": ["mark", "screen"], "inline": ["screen"], "tags": ["release"],
                 "titles": {"en": "The gateway", "bg": "Шлюзът"}, "descriptions": {"en": "One gateway.", "bg": "Един шлюз."},
                 "bodies": {"en": "<p class=\\"body-lead\\">One gateway.</p><pre class=\\"language-sh\\"><code>make up</code></pre><figure class=\\"body-figure\\"><img src=\\"/assets/img/articles/pacod-screen.jpg\\" alt=\\"\\"></figure>",
                            "bg": "<p>Един шлюз.</p>"}}
              ],
              "pages": [{"slug": "pacod-gw", "sortOrder": 100, "titles": {"en": "pacod-gw"}, "descriptions": {"en": "The gateway"},
                         "bodies": {"en": "<p>The gateway, <a href=\\"https://github.com/pacod22/pacod-gw\\">its repository</a>.</p><table><tr><td>Licence</td><td>Apache-2.0</td></tr></table>"}}],
              "menus": {"main": [{"labels": {"en": "Gateway"}, "kind": "CATEGORY", "target": "1"},
                                 {"labels": {"en": "pacod-gw"}, "kind": "PAGE", "target": "pacod-gw"},
                                 {"labels": {"en": "GitHub"}, "kind": "URL", "target": "https://github.com/pacod22"}]},
              "social": {"bios": {"en": "The PACOD projects."}, "latestHeadings": {"en": "Latest"},
                         "links": [{"titles": {"en": "The gateway"}, "url": "/p/pacod-gw"}]},
              "site": {"taglines": {"en": "Open-source building blocks"}, "baseUrl": "https://ai.pacod.io", "gtmId": "GTM-AB12CD3"},
              "partners": [{"id": "pacod-gw", "name": "pacod-gw", "page": "pacod-gw", "folder": "banners/pacod-gw",
                            "bookings": [{"name": "pacod-gw (tall)", "file": "tall.html", "size": "TALL", "placement": "SIDEBAR",
                                          "pages": ["ARTICLE"], "categories": [1]}]}],
              "brand": {"favicon": "brand/favicon.svg", "shareFile": "photos/screen.png"}
            }
            """;

    private FolderSeed() {
    }

    /** The seed in {@code dir}, with every file it names. */
    static DirectorySeed write(Path dir) throws IOException {
        return write(dir, JSON);
    }

    static DirectorySeed write(Path dir, String json) throws IOException {
        Files.writeString(dir.resolve(DirectorySeed.SEED_FILE), json);
        Files.createDirectories(dir.resolve("photos"));
        ImageIO.write(screenshot(), "png", dir.resolve("photos/screen.png").toFile());
        Path banners = Files.createDirectories(dir.resolve("banners/pacod-gw"));
        Files.writeString(banners.resolve("tall.html"), "<!doctype html><a href=\"/p/pacod-gw\" target=\"_blank\">pacod-gw</a>");
        Files.writeString(banners.resolve("creative.css"), "a { color: #0e6e57; }");
        Files.writeString(banners.resolve(".DS_Store"), "finder");
        Files.createDirectories(dir.resolve("brand"));
        Files.writeString(dir.resolve("brand/favicon.svg"), "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1 1\"/>");
        return new DirectorySeed(dir);
    }

    /** White, framed in red: whatever is cut off a padded picture would take the red with it. */
    static BufferedImage screenshot() {
        BufferedImage image = new BufferedImage(SHOT_WIDTH, SHOT_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.RED);
            g.fillRect(0, 0, SHOT_WIDTH, SHOT_HEIGHT);
            g.setColor(Color.WHITE);
            g.fillRect(8, 8, SHOT_WIDTH - 16, SHOT_HEIGHT - 16);
        } finally {
            g.dispose();
        }
        return image;
    }
}

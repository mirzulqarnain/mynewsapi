import org.openqa.selenium.By;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;

import java.io.File;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;

public class NewsScraper {

    public record Result(String url, String text) {}

    private static final String CHROME_BIN = System.getenv()
            .getOrDefault("CHROME_BIN", "/opt/chrome-headless-shell-linux64/chrome-headless-shell");
    private static final String CHROMEDRIVER = System.getenv()
            .getOrDefault("CHROMEDRIVER_PATH", "/opt/chromedriver-linux64/chromedriver");

    private static final String SITE = "npr.org";
    private static final String SEARCH_URL = "https://www." + SITE + "/search?query=";

    private static final Map<String, Result> CACHE = new ConcurrentHashMap<>();

    // Dated permalinks, e.g. https://www.npr.org/2026/10/20/nx-s1-0000000/some-title
    private static final Pattern DATED_URL =
            Pattern.compile("^https?://(www\\.)?npr\\.org/\\d{4}/\\d{2}/\\d{2}/[^?#]+");
    private static final Pattern SITE_URL =
            Pattern.compile("^https?://(www\\.)?npr\\.org/.+");
    private static final Pattern LONG_SLUG = Pattern.compile("[a-z0-9]+(-[a-z0-9]+){4,}");
    private static final String[] SKIP_PATHS = {
            "/category/", "/tag/", "/author/", "/page/", "/wp-", "/feed", "/about", "/contact",
            "/privacy", "/terms", "/advertise", "/team", "/epaper", "/cdn-cgi/"
    };

    private static final String[] RESULT_SELECTORS = {
            "article h2 a", "article h3 a", "h2.entry-title a", "h3.entry-title a",
            ".td-module-title a", "main h2 a", "main h3 a", "h2 a", "h3 a", "a[href]"
    };
    private static final String[] DDG_SELECTORS = {"a.result__a", ".result__title a", "a[href]"};
    private static final String[] BING_SELECTORS = {"li.b_algo h2 a", "li.b_algo a", "h2 a"};

    private static final String[] BODY_SELECTORS = {
            "#storytext p", ".storytext p", "article .entry-content p", ".entry-content p", "article p", "p"
    };

    private static WebDriver newDriver() {
        ChromeOptions options = new ChromeOptions();
        options.setBinary(CHROME_BIN);
        options.setPageLoadStrategy(PageLoadStrategy.EAGER);
        options.addArguments(
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-gpu",
                "--window-size=1920,1080",
                "--disable-blink-features=AutomationControlled",
                "--lang=en-US",
                "--user-agent=Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36"
        );
        ChromeDriverService service = new ChromeDriverService.Builder()
                .usingDriverExecutable(new File(CHROMEDRIVER))
                .usingAnyFreePort()
                .build();
        ChromeDriver driver = new ChromeDriver(service, options);
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(40));
        return driver;
    }

    public static Result fetchFirstResult(String keyword) {
        String key = keyword.toLowerCase();
        Result cached = CACHE.get(key);
        if (cached != null) return cached;

        WebDriver driver = newDriver();
        List<String> notes = new ArrayList<>();
        try {
            String q = URLEncoder.encode(keyword, StandardCharsets.UTF_8);

            // 1. The news site's own search page
            String articleUrl = trySiteSearch(driver, q, notes);

            // 2. If the site's search is blocked (Cloudflare), search the same site through a search engine
            if (articleUrl == null) {
                String sq = URLEncoder.encode("site:" + SITE + " " + keyword, StandardCharsets.UTF_8);
                articleUrl = tryEngine(driver, "https://html.duckduckgo.com/html/?q=" + sq,
                        DDG_SELECTORS, "duckduckgo", notes);
                if (articleUrl == null) {
                    articleUrl = tryEngine(driver, "https://www.bing.com/search?q=" + sq,
                            BING_SELECTORS, "bing", notes);
                }
            }
            if (articleUrl == null) {
                throw new RuntimeException("No search result found. " + String.join(" | ", notes));
            }

            // 3. Open the first result and extract the article text
            driver.get(articleUrl);
            String text = extractBody(driver);
            if (text.isBlank()) {
                throw new RuntimeException("Could not extract article text from " + articleUrl
                        + ". " + diagnostics(driver));
            }
            Result r = new Result(articleUrl, text);
            CACHE.put(key, r);
            return r;
        } finally {
            driver.quit();
        }
    }

    private static String trySiteSearch(WebDriver driver, String q, List<String> notes) {
        try {
            driver.get(SEARCH_URL + q);
        } catch (Exception e) {
            notes.add("site search load: " + e.getClass().getSimpleName());
        }
        boolean challenge = safeTitle(driver).toLowerCase().contains("just a moment");
        String url = poll(driver, NewsScraper::findFirstArticleUrl, challenge ? 8 : 15);
        if (url == null) notes.add("site search: " + diagnostics(driver));
        return url;
    }

    private static String tryEngine(WebDriver driver, String searchUrl, String[] selectors,
                                    String name, List<String> notes) {
        try {
            driver.get(searchUrl);
        } catch (Exception e) {
            notes.add(name + " load: " + e.getClass().getSimpleName());
        }
        String url = poll(driver, d -> findEngineResult(d, selectors), 12);
        if (url == null) notes.add(name + ": " + diagnostics(driver));
        return url;
    }

    private static String poll(WebDriver d, Function<WebDriver, String> f, int seconds) {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            try {
                String r = f.apply(d);
                if (r != null) return r;
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private static String safeTitle(WebDriver d) {
        try {
            String t = d.getTitle();
            return t == null ? "" : t;
        } catch (Exception e) {
            return "";
        }
    }

    private static String diagnostics(WebDriver d) {
        try {
            String body = d.findElement(By.tagName("body")).getText().replaceAll("\\s+", " ");
            if (body.length() > 200) body = body.substring(0, 200);
            return "[title=" + safeTitle(d) + "] [url=" + d.getCurrentUrl() + "] [body=" + body + "]";
        } catch (Exception e) {
            return "[diagnostics unavailable: " + e.getClass().getSimpleName() + "]";
        }
    }

    private static String findFirstArticleUrl(WebDriver driver) {
        for (String sel : RESULT_SELECTORS) {
            for (WebElement a : driver.findElements(By.cssSelector(sel))) {
                String href = a.getAttribute("href");
                if (href != null && DATED_URL.matcher(href).find()) return href;
            }
        }
        for (String sel : RESULT_SELECTORS) {
            for (WebElement a : driver.findElements(By.cssSelector(sel))) {
                String href = a.getAttribute("href");
                if (href != null && looksLikeArticle(href)) return href;
            }
        }
        return null;
    }

    private static String findEngineResult(WebDriver driver, String[] selectors) {
        for (String sel : selectors) {
            for (WebElement a : driver.findElements(By.cssSelector(sel))) {
                String href = unwrap(a.getAttribute("href"));
                if (href != null && (DATED_URL.matcher(href).find() || looksLikeArticle(href))) {
                    return href;
                }
            }
        }
        return null;
    }

    // Search engines wrap result links in redirects; recover the real target URL.
    private static String unwrap(String href) {
        if (href == null) return null;
        try {
            URI u = URI.create(href);
            String q = u.getRawQuery();
            String host = u.getHost() == null ? "" : u.getHost();
            if (q == null) return href;
            if (host.contains("duckduckgo.com")) {
                String v = param(q, "uddg");
                if (v != null) return v;
            }
            if (host.contains("bing.com")) {
                String v = param(q, "u");
                if (v != null && v.startsWith("a1")) {
                    return new String(Base64.getUrlDecoder().decode(v.substring(2)), StandardCharsets.UTF_8);
                }
            }
        } catch (Exception ignored) {
        }
        return href;
    }

    private static String param(String rawQuery, String name) {
        for (String pair : rawQuery.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0 && pair.substring(0, i).equals(name)) {
                return URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static boolean looksLikeArticle(String href) {
        if (!SITE_URL.matcher(href).find() || href.contains("?s=")) return false;
        String path;
        try {
            path = URI.create(href).getPath();
        } catch (Exception e) {
            return false;
        }
        if (path == null || path.length() < 2) return false;
        String lower = path.toLowerCase();
        for (String skip : SKIP_PATHS) {
            if (lower.contains(skip)) return false;
        }
        String[] segs = lower.split("/");
        return LONG_SLUG.matcher(segs[segs.length - 1]).matches();
    }

    private static String extractBody(WebDriver driver) {
        for (String sel : BODY_SELECTORS) {
            List<String> parts = new ArrayList<>();
            for (WebElement p : driver.findElements(By.cssSelector(sel))) {
                String t = p.getText().trim();
                if (t.length() > 40) parts.add(t);
            }
            if (parts.size() >= 3) return String.join(" ", parts);
        }
        return "";
    }
}

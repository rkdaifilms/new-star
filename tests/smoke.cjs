const { chromium } = require("playwright");

(async () => {
  const base = (process.env.BASE_URL || "").replace(/\/+$/, "");
  if (!base) throw new Error("BASE_URL is required");

  const browser = await chromium.launch({ headless: true });
  const failures = [];
  const checkPage = async (path, verify) => {
    const page = await browser.newPage({
      viewport: { width: 390, height: 844 },
      deviceScaleFactor: 2,
      isMobile: true,
      hasTouch: true
    });
    const pageErrors = [];
    page.on("pageerror", error => pageErrors.push(error.message));
    const response = await page.goto(base + "/" + path, {
      waitUntil: "domcontentloaded",
      timeout: 45000
    });
    if (!response || !response.ok()) {
      throw new Error(path + " returned " + (response ? response.status() : "no response"));
    }
    await page.waitForTimeout(1200);
    if (pageErrors.length) throw new Error(path + " JavaScript errors: " + pageErrors.join(" | "));
    if (verify) await verify(page);
    await page.close();
  };

  try {
    await checkPage("index.html", async page => {
      await page.waitForFunction(() => {
        const video = document.querySelector("#video");
        return video && (video.currentSrc || video.error || getComputedStyle(document.querySelector("#empty")).display !== "none");
      }, null, { timeout: 30000 });

      const feed = await page.evaluate(() => {
        const video = document.querySelector("#video");
        const meta = document.querySelector('meta[name="viewport"]')?.content || "";
        return {
          src: video?.currentSrc || video?.src || "",
          readyState: video?.readyState ?? -1,
          mediaError: video?.error?.code ?? null,
          touchAction: getComputedStyle(video).touchAction,
          controls: video?.controls,
          playsInline: video?.playsInline,
          viewport: meta,
          counter: document.querySelector("#counter")?.textContent || ""
        };
      });
      if (!feed.src) throw new Error("Feed did not assign a video source");
      if (feed.mediaError) throw new Error("Video media error code " + feed.mediaError);
      if (!feed.controls || !feed.playsInline) throw new Error("Native mobile video controls/inline playback missing");
      if (!feed.touchAction.includes("pan-y")) throw new Error("Vertical feed gestures are blocked");
      if (/user-scalable\s*=\s*no|maximum-scale\s*=\s*1(?:\D|$)/i.test(feed.viewport)) {
        throw new Error("Browser zoom is disabled by viewport meta");
      }

      await page.waitForFunction(() => {
        const video = document.querySelector("#video");
        return video && (video.readyState >= 2 || video.error);
      }, null, { timeout: 45000 });
      const media = await page.locator("#video").evaluate(v => ({
        readyState: v.readyState,
        error: v.error?.code ?? null,
        width: v.videoWidth,
        height: v.videoHeight
      }));
      if (media.error || media.readyState < 2 || !media.width || !media.height) {
        throw new Error("Video did not become playable: " + JSON.stringify(media));
      }

      const comment = page.locator("#commentBtn");
      if (!(await comment.isVisible()) || !(await comment.isEnabled())) {
        throw new Error("Comment action is not visible/enabled");
      }
      await comment.click();
      await page.waitForFunction(() => {
        const panel = document.querySelector("#commentModal");
        return panel && getComputedStyle(panel).display !== "none";
      }, null, { timeout: 5000 });
      if (!(await page.locator("#commentInput").isVisible())) {
        throw new Error("Comment composer did not open");
      }
    });

    for (const path of ["auth.html", "camera.html", "live.html", "gaming.html"]) {
      await checkPage(path);
    }
    console.log("PASS: mobile feed, real video decode, comment sheet, auth/camera/live/gaming page smoke tests");
  } catch (error) {
    failures.push(error.stack || error.message || String(error));
    console.error("FAIL:", failures.join("\n"));
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
})();

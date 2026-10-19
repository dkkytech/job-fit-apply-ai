# jobbot-browser: a long-lived, headed Chromium that JobBot fills job applications in, and that
# Richard can watch and take over in his browser (Selkies web viewer, password-protected).
# Pinned by digest; the profile (logins) lives on a volume at /config.
FROM lscr.io/linuxserver/chromium@sha256:2d32e1b2b28aa92973aa0f58c433c0b045db6e1224d7001eeaa9cde1a474ce13

# Headed Chromium binds DevTools to 127.0.0.1 only; socat exposes it on the container's
# network (the private `jobbot-browser` network, reachable from jobbot-mcp alone — never published).
RUN apt-get update \
    && apt-get install -y --no-install-recommends socat \
    && rm -rf /var/lib/apt/lists/*
COPY svc-cdp/ /etc/s6-overlay/s6-rc.d/svc-cdp/
COPY custom-cont-init.d/ /custom-cont-init.d/
# Browser sign-in off. This Chromium has no Google API keys, so every Google web sign-in makes
# DICE try (and fail) to fetch a browser token, and the next startup's reconcile signs the account
# out of the web: the dkkytech@ session died on every restart. With BrowserSignin 0 there is no
# DICE, and a website sign-in is just cookies that persist like any other site's.
COPY policies/jobbot.json /etc/chromium/policies/managed/jobbot.json
RUN chmod +x /etc/s6-overlay/s6-rc.d/svc-cdp/run \
    && touch /etc/s6-overlay/s6-rc.d/user/contents.d/svc-cdp

# A fixed profile dir (Chrome refuses remote debugging on its implicit default dir) and the
# DevTools port. The dir keeps its original name: the signed-in Google session lives in it. CHROME_CLI is appended by the image's wrapped-chromium.
ENV CHROME_CLI="--user-data-dir=/config/apply-profile --remote-debugging-port=9222 --remote-allow-origins=* --no-first-run --no-default-browser-check"
EXPOSE 9223

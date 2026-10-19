#!/usr/bin/env python3
"""Generate src/main/resources/employers.yaml from the researched employer CSV.

Usage: python3 -I tools/gen_employers.py tools/employers.csv > src/main/resources/employers.yaml

The CSV (company, category, seattle_area_location, careers_url, ats, ats_id, api_endpoint,
api_status, qe_relevance, notes, ...) is the output of the Seattle QE employer research. Every
employer is kept so the YAML is the whole watchlist; entries whose platform has no adapter yet are
skipped at run time, and entries whose feed did not verify are written with enabled: false.
"""
import csv
import re
import sys
from urllib.parse import parse_qs, urlencode, urlsplit

RSS_PLATFORMS = {"taleo_tbe", "avature", "successfactors", "neogov"}
DETAIL_SELECTOR = {"avature": ".article__content--rich-text"}
AREA_CITIES = ["Seattle", "Bellevue", "Redmond", "Kirkland", "Bothell", "Everett", "Issaquah", "Renton", "Tacoma",
               "Kent", "Snoqualmie", "Tukwila", "Mukilteo", "Lynnwood", "Woodinville"]
JIBE_KEEP_PARAMS = {"location", "stretch", "stretchUnit"}
HTML_BOARDS = {"icims", "jobvite"}
# Server-rendered sites with no feed, read by the generic html adapter. Configured here (reviewed
# code) rather than in the research CSV; each was checked against the live site with --dry-run.
HTML_SITES = {
    "Apex Systems": {
        "feed": "https://www.apexsystems.com/search-results-usa?catalogcode=USA&address=Seattle%2C%20WA&radius=50&page={page}&rows=25&query={term}&sort=lastposteddesc",
        "link_pattern": r"/job/(\d+)_usa/", "card_selector": "tr", "pages": 2,
    },
    "Ascendion": {
        "feed": "https://ascendion.com/who-we-are/careers/america/",
        "link_pattern": r"/job-inner-page/\?post=(\d+)", "card_selector": ".crs-job", "title_selector": ".crs-job__title", "detail_selector": ".jd-container",
    },
    "Akvelon": {
        "feed": "https://akvelon.com/careers/",
        "link_pattern": r"akvelon\.com/vacancy/([^/]+)/", "card_selector": ".jet-listing-grid__item",
        "title_selector": ".elementor-heading-title", "detail_selector": ".elementor-location-single",
    },
    "Valve": {
        "feed": "https://www.valvesoftware.com/en/jobs",
        "link_pattern": r"/jobs\?job_id=(\d+)", "card_selector": ".job_opening", "title_selector": ".job_title",
        "detail_selector": "section.job_description",
    },
    "Experis (ManpowerGroup)": {
        "feed": "https://www.experis.com/en/all-jobs",
        "link_pattern": r"/en/job/(\d+)/", "location_label": "Location:", "detail_selector": ".details-rich-text",
    },
}
# The parameter each shipped adapter cannot run without (None = needs nothing beyond the ats).
REQUIRED_PARAM = {"greenhouse": "board", "lever": "board", "ashby": "board", "workday": "host", "rss": "feed",
                  "oracle_hcm": "site", "eightfold": "domain", "smartrecruiters": "board", "jibe": "feed", "amazon": None,
                  "workable": "board", "gem": "board", "rippling": "board", "adp": "feed", "phenom": "host", "tiktok": None,
                  "icims": "feed", "jobvite": "board", "apple": None, "capgemini": None, "jobsyn": "host", "epam": None,
                  "successfactors": "host", "ukg": "board", "paylocity": "board", "zoho": "host", "atlassian": None,
                  "html": "link_pattern", "kforce": "location", "roberthalf": "feed",
                  "jobdiva": "board", "insightglobal": "location", "griddynamics": "feed"}


def area_cities(text):
    """Seattle-area city names mentioned in the research's location column, in list order."""
    return [c for c in AREA_CITIES if re.search(rf"\b{c}\b", text or "", re.I)]


def by_url(url, ats, r):
    """Platforms recognised from the API URL itself (the research's ats column can name the
    wrapper, e.g. Liberty Mutual is filed as icims but serves an Eightfold v2 API)."""
    u = urlsplit(url)
    q = parse_qs(u.query)
    if "/api/pcsx/" in u.path or "/api/apply/v2/jobs" in u.path:
        e = {"ats": "eightfold", "api": "pcsx" if "/api/pcsx/" in u.path else "v2", "host": u.netloc,
             "domain": q.get("domain", [""])[0]}
        if q.get("location", [""])[0]:
            e["location"] = q["location"][0]
        return e
    if "oraclecloud.com" in u.netloc and "/hcmRestApi/" in u.path and (m := re.search(r"siteNumber=([A-Za-z0-9_]+)", url)):
        e = {"ats": "oracle_hcm", "host": u.netloc, "site": m.group(1)}
        if cities := area_cities(r.get("seattle_area_location", "")):
            e["search"] = cities
        return e
    if u.netloc == "api.smartrecruiters.com" and (m := re.search(r"/v1/companies/([^/]+)/postings", u.path)):
        return {"ats": "smartrecruiters", "board": m.group(1)}
    if u.path.rstrip("/") == "/api/jobs":
        kept = urlencode([(k, v) for k, vs in q.items() if k in JIBE_KEEP_PARAMS for v in vs])
        return {"ats": "jibe", "feed": f"{u.scheme}://{u.netloc}/api/jobs" + (f"?{kept}" if kept else "")}
    if u.netloc.endswith("amazon.jobs") and u.path.endswith("/search.json"):
        return {"ats": "amazon"}
    if u.netloc == "apply.workable.com" and (m := re.search(r"/accounts/([^/?]+)", u.path)):
        return {"ats": "workable", "board": m.group(1)}
    if u.netloc == "api.gem.com" and (m := re.search(r"/job_board/v0/([^/]+)/job_posts", u.path)):
        return {"ats": "gem", "board": m.group(1)}
    if u.netloc == "api.rippling.com" and (m := re.search(r"/board/([^/]+)/jobs", u.path)):
        return {"ats": "rippling", "board": m.group(1)}
    if u.netloc == "workforcenow.adp.com" and u.path.endswith("/job-requisitions") and q.get("cid"):
        keep = urlencode([("cid", q["cid"][0]), ("ccId", q.get("ccId", [""])[0]), ("lang", "en_US"), ("locale", "en_US")])
        return {"ats": "adp", "feed": f"https://{u.netloc}{u.path}?{keep}"}
    if u.path.rstrip("/") == "/widgets" and ats == "phenom":
        return {"ats": "phenom", "host": u.netloc, "location": "Washington"}
    if u.netloc == "api.lifeattiktok.com":
        return {"ats": "tiktok", "location": "CT_157"}
    if u.netloc.endswith(".icims.com") and u.path.rstrip("/") == "/jobs/search":
        kept = urlencode([(k, v) for k, vs in q.items() if k in ("ss", "searchKeyword", "searchLocation") for v in vs])
        return {"ats": "icims", "feed": f"https://{u.netloc}/jobs/search" + (f"?{kept}" if kept else "")}
    if u.netloc == "jobs.jobvite.com" and (m := re.match(r"/([^/]+)/jobs", u.path)):
        return {"ats": "jobvite", "board": m.group(1)}
    if u.netloc == "jobs.apple.com":
        return {"ats": "apple", "location": q.get("location", ["seattle-SEA"])[0]}
    if u.netloc == "cg-jobstream-api.azurewebsites.net":
        return {"ats": "capgemini", "location": q.get("country_code", ["us-en"])[0]}
    if u.netloc == "prod-search-api.jobsyn.org" and (site := urlsplit(r.get("careers_url", "")).netloc):
        return {"ats": "jobsyn", "host": site}
    if u.netloc == "careers.epam.com" and u.path.startswith("/api/jobs/"):
        return {"ats": "epam"}
    if u.path.rstrip("/") == "/services/recruiting/v1/jobs":
        return {"ats": "successfactors", "host": u.netloc, "location": "Washington"}
    if u.netloc == "recruiting.ultipro.com" and (m := re.match(r"/([^/]+)/JobBoard/([0-9a-f-]{36})", u.path)):
        return {"ats": "ukg", "tenant": m.group(1), "board": m.group(2)}
    if u.netloc == "recruiting.paylocity.com" and (m := re.search(r"/recruiting/jobs/All/([0-9a-f-]{36})", u.path, re.I)):
        return {"ats": "paylocity", "board": m.group(1)}
    if u.netloc.endswith(".zohorecruit.com") and (m := re.match(r"/jobs/([^/]+)", u.path)):
        return {"ats": "zoho", "host": u.netloc, "site": m.group(1)}
    if u.netloc == "www.atlassian.com" and u.path.rstrip("/") == "/endpoint/careers/listings":
        return {"ats": "atlassian"}
    if u.netloc == "kforcewebeast.search.windows.net":
        return {"ats": "kforce", "location": "WA"}
    if u.netloc == "www.roberthalf.com" and u.path.startswith("/us/en/jobs/all/"):
        return {"ats": "roberthalf", "feed": url}
    if u.netloc == "insightglobal.com" and u.path == "/all/jobs":
        return {"ats": "insightglobal", "location": q.get("filter.locations", ["seattle-wa"])[0]}
    if u.netloc == "www.griddynamics.com" and u.path.startswith("/careers/discover-openings"):
        return {"ats": "griddynamics", "feed": url}
    # JobDiva candidate portal: the `a` token names the portal and its RSS feed.
    if u.netloc.endswith(".jobdiva.com") and u.path.startswith("/portal") and q.get("a"):
        return {"ats": "jobdiva", "board": q["a"][0]}
    return None


def q(s):
    """YAML double-quoted scalar."""
    return '"' + str(s).replace("\\", "\\\\").replace('"', '\\"') + '"'


def entry(r):
    # 429 = rate-limited during research (Microsoft): the API is alive, just bursty. "needs POST/headers"
    # = the research agent verified a POST/header API the GET-only verifier could not replay.
    status = r["api_status"].strip()
    ats, ep, ok = r["ats"].strip().lower(), r["api_endpoint"].strip(), status in ("200", "429") or status.startswith("needs")
    url = (re.search(r"https?://\S+", ep) or [None])[0] if ep else None
    e = {"company": r["company"], "ats": ats}
    # Server-rendered boards have no API endpoint in the research: their careers page is the feed,
    # and it was reachable when the research loaded it.
    if not url and ats in HTML_BOARDS and r.get("careers_url", "").startswith("http"):
        url, ok = r["careers_url"], r.get("url_status", "").strip() in ("200", "")
    if r["company"] in HTML_SITES:
        e.update({"ats": "html", **HTML_SITES[r["company"]]})
        ok = True
    elif url and (mapped := by_url(url, ats, r)):
        e.update(mapped)
    elif ats == "greenhouse" and url and (m := re.search(r"/boards/([^/]+)/jobs", url)):
        e["board"] = m.group(1)
    elif ats == "lever" and url and (m := re.search(r"/postings/([^/?]+)", url)):
        e["board"] = m.group(1)
    elif ats == "ashby" and url and (m := re.search(r"/job-board/([^/?]+)", url)):
        e["board"] = m.group(1)
    elif ats == "workday" and url and (m := re.search(r"https://([^/]+)/wday/cxs/([^/]+)/([^/]+)/jobs", url)):
        e["host"], e["tenant"], e["site"] = m.group(1), m.group(2), m.group(3)
    elif ats in RSS_PLATFORMS and url and re.search(r"rss|feed", url, re.I):
        e["ats"], e["platform"], e["feed"] = "rss", ats, url
        if ats in DETAIL_SELECTOR:
            e["detail_selector"] = DETAIL_SELECTOR[ats]
    if e["ats"] in REQUIRED_PARAM:
        param = REQUIRED_PARAM[e["ats"]]
        if (param and not e.get(param)) or not ok:
            e["enabled"] = False
    if r.get("may_be_empty", "").strip().lower() in ("yes", "true", "1"):
        e["may_be_empty"] = True
    e["careers_url"] = r["careers_url"]
    e["qe_fit"] = r["qe_relevance"] or "low"
    e["category"] = r["category"]
    return e


def main(path):
    rows = sorted(csv.DictReader(open(path, encoding="utf-8")), key=lambda r: r["company"].lower())
    out = [
        "# Watchlist employers — generated by tools/gen_employers.py from the Seattle QE employer research.",
        "# Regenerate: python3 -I tools/gen_employers.py tools/employers.csv > src/main/resources/employers.yaml",
        "# ats picks the adapter (greenhouse, lever, ashby, workday, rss, oracle_hcm, eightfold, smartrecruiters, jibe,",
        "# amazon, workable, gem, rippling, adp, phenom, tiktok, icims, jobvite, apple, capgemini, jobsyn, epam,",
        "# successfactors, ukg, paylocity, zoho, atlassian, html, kforce, roberthalf);",
        "# other values are platforms without an adapter yet",
        "# and are skipped at run time.",
        "# enabled: false = feed did not verify; re-check before enabling.",
        "",
    ]
    for r in rows:
        e = entry(r)
        out.append(f"- company: {q(e.pop('company'))}")
        for k, v in e.items():
            if isinstance(v, list):
                out.append(f"  {k}: [{', '.join(q(x) for x in v)}]")
            else:
                out.append(f"  {k}: {str(v).lower() if isinstance(v, bool) else v if isinstance(v, int) else q(v)}")
    sys.stdout.write("\n".join(out) + "\n")


if __name__ == "__main__":
    main(sys.argv[1])

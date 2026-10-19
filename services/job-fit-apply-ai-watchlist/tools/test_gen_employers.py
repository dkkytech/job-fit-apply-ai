"""Unit tests for gen_employers.py — run: python3 -m unittest discover -s tools -p 'test_*.py'"""
import csv
import io
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stdout

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_employers  # noqa: E402


def row(**kw):
    base = {"company": "Acme", "category": "Seattle Software / Internet", "careers_url": "https://acme.example/careers",
            "ats": "", "ats_id": "", "api_endpoint": "", "api_status": "200", "qe_relevance": "high"}
    base.update(kw)
    return base


class EntryTest(unittest.TestCase):
    def test_board_platforms_take_the_token_from_the_api_endpoint(self):
        cases = {
            "greenhouse": "https://boards-api.greenhouse.io/v1/boards/impinjexternal/jobs",
            "lever": "https://api.lever.co/v0/postings/palantir?mode=json",
            "ashby": "https://api.ashbyhq.com/posting-api/job-board/MotherDuck",
        }
        expected = {"greenhouse": "impinjexternal", "lever": "palantir", "ashby": "MotherDuck"}
        for ats, url in cases.items():
            e = gen_employers.entry(row(ats=ats, api_endpoint=url))
            self.assertEqual(expected[ats], e["board"], ats)
            self.assertNotIn("enabled", e, ats)

    def test_workday_parses_host_tenant_and_site_from_the_post_endpoint(self):
        e = gen_employers.entry(row(ats="workday", api_endpoint="POST https://boeing.wd1.myworkdayjobs.com/wday/cxs/boeing/EXTERNAL_CAREERS/jobs"))
        self.assertEqual(("boeing.wd1.myworkdayjobs.com", "boeing", "EXTERNAL_CAREERS"), (e["host"], e["tenant"], e["site"]))

    def test_rss_platforms_become_the_rss_adapter_and_avature_gets_a_detail_selector(self):
        e = gen_employers.entry(row(ats="avature", api_endpoint="https://careers.lululemon.com/en_US/careers/SearchCareer/feed/"))
        self.assertEqual(("rss", "avature"), (e["ats"], e["platform"]))
        self.assertEqual(".article__content--rich-text", e["detail_selector"])
        e = gen_employers.entry(row(ats="taleo_tbe", api_endpoint="https://phf.tbe.taleo.net/phf02/ats/servlet/Rss?org=COSTCO&cws=41"))
        self.assertEqual("rss", e["ats"])
        self.assertNotIn("detail_selector", e)

    def test_eightfold_is_recognised_by_url_whatever_the_ats_column_says(self):
        e = gen_employers.entry(row(ats="icims", api_endpoint="https://searchjobs.libertymutualgroup.com/api/apply/v2/jobs?domain=libertymutual.com&location=Seattle%2C%20Washington&start=0&num=50"))
        self.assertEqual({"ats": "eightfold", "api": "v2", "host": "searchjobs.libertymutualgroup.com", "domain": "libertymutual.com", "location": "Seattle, Washington"},
                         {k: e[k] for k in ("ats", "api", "host", "domain", "location")})
        e = gen_employers.entry(row(ats="oracle_hcm", api_endpoint="https://fortive.eightfold.ai/api/pcsx/search?domain=fortive.com&query=test&location=Everett%2C%20WA&start=0"))
        self.assertEqual(("eightfold", "pcsx", "Everett, WA"), (e["ats"], e["api"], e["location"]))
        self.assertNotIn("query", str(e), "search terms come from config, not the research URL")

    def test_oracle_takes_host_site_and_area_cities_from_the_location_column(self):
        e = gen_employers.entry(row(ats="oracle_hcm", seattle_area_location="Renton (HQ) / Redmond / Seattle",
                                    api_endpoint="https://evac.fa.us2.oraclecloud.com/hcmRestApi/resources/latest/recruitingCEJobRequisitions?onlyData=true&finder=findReqs;siteNumber=CX_1,keyword=%22test%22"))
        self.assertEqual(("oracle_hcm", "evac.fa.us2.oraclecloud.com", "CX_1", ["Seattle", "Redmond", "Renton"]), (e["ats"], e["host"], e["site"], e["search"]))
        e = gen_employers.entry(row(ats="oracle_hcm", seattle_area_location="IT remote",
                                    api_endpoint="https://x.fa.us2.oraclecloud.com/hcmRestApi/resources/latest/recruitingCEJobRequisitions?finder=findReqs;siteNumber=CX_1"))
        self.assertNotIn("search", e, "no area city named → adapter default list")

    def test_smartrecruiters_jibe_and_amazon(self):
        e = gen_employers.entry(row(ats="smartrecruiters", api_endpoint="https://api.smartrecruiters.com/v1/companies/ServiceNow/postings?q=Kirkland"))
        self.assertEqual(("smartrecruiters", "ServiceNow"), (e["ats"], e["board"]))
        e = gen_employers.entry(row(ats="icims", api_endpoint="https://careers.amd.com/api/jobs?location=Bellevue,%20WA&stretch=30&stretchUnit=MILES&page=1&keywords=x&limit=5"))
        self.assertEqual("jibe", e["ats"])
        self.assertEqual("https://careers.amd.com/api/jobs?location=Bellevue%2C+WA&stretch=30&stretchUnit=MILES", e["feed"], "only location filters survive")
        e = gen_employers.entry(row(ats="custom", api_endpoint="https://www.amazon.jobs/en/search.json?base_query=test&normalized_state_name[]=Washington"))
        self.assertEqual("amazon", e["ats"])
        self.assertNotIn("enabled", e)

    def test_small_boards_staffing_and_tiktok(self):
        cases = [
            (row(ats="workable", api_status="needs POST/headers (not auto-checked)", api_endpoint="POST https://apply.workable.com/api/v3/accounts/seeq/jobs"),
             {"ats": "workable", "board": "seeq"}),
            (row(ats="other", api_endpoint="https://api.gem.com/job_board/v0/wyzecam-com/job_posts"), {"ats": "gem", "board": "wyzecam-com"}),
            (row(ats="rippling", api_endpoint="https://api.rippling.com/platform/api/ats/v1/board/zap-energy-careers/jobs"),
             {"ats": "rippling", "board": "zap-energy-careers"}),
            (row(ats="phenom", api_status="needs POST/headers (not auto-checked)", api_endpoint="POST https://careers.teksystems.com/widgets (ddoKey=refineSearch)"),
             {"ats": "phenom", "host": "careers.teksystems.com", "location": "Washington"}),
            (row(ats="custom", api_status="needs POST/headers (not auto-checked)", api_endpoint="POST https://api.lifeattiktok.com/api/v1/public/supplier/search/job/posts {keyword}"),
             {"ats": "tiktok", "location": "CT_157"}),
        ]
        for r, expected in cases:
            e = gen_employers.entry(r)
            self.assertEqual(expected, {k: e[k] for k in expected}, r["api_endpoint"])
            self.assertNotIn("enabled", e, "research-verified POST APIs are enabled")

    def test_adp_keeps_only_the_tenant_parameters(self):
        e = gen_employers.entry(row(ats="adp", api_endpoint="https://workforcenow.adp.com/mascsr/default/careercenter/public/events/staffing/v1/job-requisitions?cid=abc&ccId=123_4&$top=20&timeStamp=1"))
        self.assertEqual("https://workforcenow.adp.com/mascsr/default/careercenter/public/events/staffing/v1/job-requisitions?cid=abc&ccId=123_4&lang=en_US&locale=en_US", e["feed"])

    def test_bespoke_employer_apis(self):
        e = gen_employers.entry(row(ats="custom", api_endpoint="https://jobs.apple.com/en-us/search?location=seattle-SEA&search=test (SSR JSON in HTML)"))
        self.assertEqual(("apple", "seattle-SEA"), (e["ats"], e["location"]))
        e = gen_employers.entry(row(ats="successfactors", api_endpoint="https://cg-jobstream-api.azurewebsites.net/api/job-search?country_code=us-en&search=test"))
        self.assertEqual(("capgemini", "us-en"), (e["ats"], e["location"]))
        e = gen_employers.entry(row(ats="icims", careers_url="https://careers.alaskaair.com/jobs/?location=Seatac",
                                    api_endpoint="https://prod-search-api.jobsyn.org/api/v1/solr/search?q=quality&page=1"))
        self.assertEqual(("jobsyn", "careers.alaskaair.com"), (e["ats"], e["host"]))
        e = gen_employers.entry(row(ats="custom", api_endpoint="https://careers.epam.com/api/jobs/v2/search/careers?lang=en&q=Seattle"))
        self.assertEqual("epam", e["ats"])

    def test_rate_limited_during_research_still_counts_as_reachable(self):
        e = gen_employers.entry(row(ats="eightfold", api_status="429", api_endpoint="https://apply.careers.microsoft.com/api/pcsx/search?domain=microsoft.com&location=Redmond%2C%20WA"))
        self.assertNotIn("enabled", e)

    def test_unverified_or_unparseable_feeds_are_disabled(self):
        self.assertFalse(gen_employers.entry(row(ats="greenhouse", api_endpoint="https://boards-api.greenhouse.io/v1/boards/x/jobs", api_status="403"))["enabled"])
        self.assertFalse(gen_employers.entry(row(ats="workday", api_endpoint=""))["enabled"])

    def test_platforms_without_an_adapter_stay_listed_and_enabled(self):
        e = gen_employers.entry(row(ats="taleo", api_endpoint="", careers_url="https://x.taleo.net/careersection/ex/jobsearch.ftl"))
        self.assertEqual("taleo", e["ats"])
        self.assertNotIn("enabled", e)

    def test_html_boards_use_the_careers_page_when_there_is_no_api(self):
        e = gen_employers.entry(row(ats="icims", api_endpoint="", careers_url="https://careers-fhcrc.icims.com/jobs/search?ss=1&searchKeyword=software&in_iframe=1&pr=3", url_status="200"))
        self.assertEqual(("icims", "https://careers-fhcrc.icims.com/jobs/search?ss=1&searchKeyword=software"), (e["ats"], e["feed"]),
                         "paging/iframe params are the adapter's business")
        self.assertNotIn("enabled", e)
        e = gen_employers.entry(row(ats="jobvite", api_endpoint="", careers_url="https://jobs.jobvite.com/kymetacorp/jobs", url_status="200"))
        self.assertEqual(("jobvite", "kymetacorp"), (e["ats"], e["board"]))
        e = gen_employers.entry(row(ats="jobvite", api_endpoint="", careers_url="https://jobs.jobvite.com/x/jobs", url_status="403"))
        self.assertFalse(e["enabled"], "an unreachable careers page is disabled like an unverified API")


class NewPlatformTest(unittest.TestCase):
    def test_successfactors_unify_search_api_searches_washington(self):
        e = gen_employers.entry(row(ats="successfactors", api_endpoint="POST https://careers.wipro.com/services/recruiting/v1/jobs", api_status="200"))
        self.assertEqual({"ats": "successfactors", "host": "careers.wipro.com", "location": "Washington"}, {k: e[k] for k in ("ats", "host", "location")})
        self.assertNotIn("enabled", e)

    def test_successfactors_without_the_search_api_is_disabled_not_misconfigured(self):
        e = gen_employers.entry(row(ats="successfactors", api_endpoint='POST https://career.globant.com/api/sap/job-requisition-v1 {"page":1}', api_status="needs POST/headers"))
        self.assertEqual(("successfactors", False), (e["ats"], e.get("enabled")))

    def test_ukg_paylocity_zoho_and_atlassian_are_recognised_by_url(self):
        cases = [
            (row(ats="ukg", api_endpoint="POST https://recruiting.ultipro.com/SOU1036SOUND/JobBoard/dcc5dbea-875e-4cd1-bfd2-8e046cecc54f/JobBoardView/LoadSearchResults"),
             {"ats": "ukg", "tenant": "SOU1036SOUND", "board": "dcc5dbea-875e-4cd1-bfd2-8e046cecc54f"}),
            (row(ats="paylocity", api_endpoint="https://recruiting.paylocity.com/recruiting/jobs/All/7299ce4a-4f80-42f8-9f29-92a9a49755f9"),
             {"ats": "paylocity", "board": "7299ce4a-4f80-42f8-9f29-92a9a49755f9"}),
            (row(ats="zoho", api_endpoint="https://ziplyfiber.zohorecruit.com/jobs/Careers"),
             {"ats": "zoho", "host": "ziplyfiber.zohorecruit.com", "site": "Careers"}),
            (row(ats="icims", api_endpoint="https://www.atlassian.com/endpoint/careers/listings"), {"ats": "atlassian"}),
        ]
        for r, want in cases:
            e = gen_employers.entry(r)
            self.assertEqual(want, {k: e[k] for k in want}, r["api_endpoint"])
            self.assertNotIn("enabled", e, r["api_endpoint"])

    def test_may_be_empty_column(self):
        self.assertTrue(gen_employers.entry(row(ats="greenhouse", api_endpoint="https://boards-api.greenhouse.io/v1/boards/x/jobs", may_be_empty="yes"))["may_be_empty"])
        self.assertNotIn("may_be_empty", gen_employers.entry(row(ats="greenhouse", api_endpoint="https://boards-api.greenhouse.io/v1/boards/x/jobs")))


class HtmlSitesTest(unittest.TestCase):
    def test_configured_sites_use_the_generic_html_adapter_whatever_the_research_says(self):
        e = gen_employers.entry(row(company="Apex Systems", ats="custom", api_endpoint="", api_status=""))
        self.assertEqual("html", e["ats"])
        self.assertIn("{page}", e["feed"])
        self.assertEqual(2, e["pages"])
        self.assertNotIn("enabled", e, "the config was verified live; there is no research API status to gate on")

    def test_every_configured_site_has_a_feed_and_a_compilable_link_pattern(self):
        import re as _re
        for company, cfg in gen_employers.HTML_SITES.items():
            self.assertTrue(cfg["feed"].startswith("https://"), company)
            _re.compile(cfg["link_pattern"])

    def test_integers_are_written_unquoted(self):
        rows = [row(company="Apex Systems", ats="custom")]
        with tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False, newline="") as f:
            w = csv.DictWriter(f, fieldnames=list(rows[0].keys()) + ["category"] if "category" not in rows[0] else list(rows[0].keys()))
            w.writeheader(); w.writerows(rows)
        out = io.StringIO()
        with redirect_stdout(out):
            gen_employers.main(f.name)
        os.unlink(f.name)
        self.assertIn("  pages: 2\n", out.getvalue())


class StaffingTest(unittest.TestCase):
    def test_kforce_and_robert_half_are_recognised_by_url(self):
        e = gen_employers.entry(row(ats="custom", api_endpoint="https://kforcewebeast.search.windows.net/indexes/kforcewebjobentity/docs?api-version=2016-09-01"))
        self.assertEqual(("kforce", "WA"), (e["ats"], e["location"]))
        e = gen_employers.entry(row(ats="roberthalf", api_endpoint="https://www.roberthalf.com/us/en/jobs/all/seattle-wa?lobid=RHT"))
        self.assertEqual(("roberthalf", "https://www.roberthalf.com/us/en/jobs/all/seattle-wa?lobid=RHT"), (e["ats"], e["feed"]))
        self.assertNotIn("enabled", e)

    def test_a_jobdiva_portal_is_read_by_its_token(self):
        e = gen_employers.entry(row(ats="jobdiva", api_endpoint="https://www2.jobdiva.com/portal/?a=abc123token&compid=0"))
        self.assertEqual(("jobdiva", "abc123token"), (e["ats"], e["board"]))
        self.assertNotIn("enabled", e)
        e = gen_employers.entry(row(ats="jobdiva", api_endpoint="https://www2.jobdiva.com/portal/?compid=0"))
        self.assertEqual(False, e.get("enabled"), "no token, no feed")


class MoreEmployersTest(unittest.TestCase):
    def test_insight_global_and_grid_dynamics_are_recognised_by_url(self):
        e = gen_employers.entry(row(ats="insightglobal", api_endpoint="https://insightglobal.com/all/jobs?keyword=qa&filter.locations=seattle-wa&filter.distance=50"))
        self.assertEqual(("insightglobal", "seattle-wa"), (e["ats"], e["location"]))
        self.assertNotIn("enabled", e)
        e = gen_employers.entry(row(ats="griddynamics", api_endpoint="https://www.griddynamics.com/careers/discover-openings"))
        self.assertEqual(("griddynamics", "https://www.griddynamics.com/careers/discover-openings"), (e["ats"], e["feed"]))

    def test_uber_is_an_oracle_hcm_site(self):
        e = gen_employers.entry(row(ats="oracle_hcm", seattle_area_location="Seattle",
                                    api_endpoint="https://iaziqy.fa.ocs.oraclecloud.com/hcmRestApi/resources/latest/recruitingCEJobRequisitions?onlyData=true&finder=findReqs;siteNumber=UberCareers"))
        self.assertEqual(("oracle_hcm", "iaziqy.fa.ocs.oraclecloud.com", "UberCareers", ["Seattle"]), (e["ats"], e["host"], e["site"], e["search"]))

    def test_valve_is_read_by_the_html_adapter(self):
        e = gen_employers.entry(row(company="Valve", ats="custom"))
        self.assertEqual("html", e["ats"])
        self.assertRegex("https://www.valvesoftware.com/en/jobs?job_id=113", e["link_pattern"])
        self.assertNotRegex("https://www.valvesoftware.com/en/jobs", e["link_pattern"])


class OracleSearchTest(unittest.TestCase):
    def test_every_area_city_in_the_location_text_is_searched_and_may_be_empty_is_kept(self):
        # Molina: "Bothell" alone matched nothing in its Oracle keyword search; "Seattle" finds its WA postings.
        e = gen_employers.entry(row(ats="oracle_hcm", may_be_empty="yes",
                                    seattle_area_location="Seattle / Bothell (WA plan); IT remote",
                                    api_endpoint="https://hckd.fa.us2.oraclecloud.com/hcmRestApi/resources/latest/recruitingCEJobRequisitions?onlyData=true&finder=findReqs;siteNumber=CX_1"))
        self.assertEqual(["Seattle", "Bothell"], e["search"])
        self.assertIs(True, e["may_be_empty"])


class CommittedYamlTest(unittest.TestCase):
    def test_committed_employers_yaml_matches_the_generator_output(self):
        here = os.path.dirname(os.path.abspath(__file__))
        out = io.StringIO()
        with redirect_stdout(out):
            gen_employers.main(os.path.join(here, "employers.csv"))
        with open(os.path.join(here, "..", "src", "main", "resources", "employers.yaml"), encoding="utf-8") as fh:
            self.assertEqual(fh.read(), out.getvalue(),
                             "employers.yaml is stale: run python3 -I tools/gen_employers.py tools/employers.csv > src/main/resources/employers.yaml")


class MainTest(unittest.TestCase):
    def test_writes_every_employer_sorted_with_quoted_scalars(self):
        rows = [row(company='Zeta "Z" Co', ats="custom"), row(company="alpha", ats="lever", api_endpoint="https://api.lever.co/v0/postings/alpha?mode=json")]
        with tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False, newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
            w.writeheader()
            w.writerows(rows)
        try:
            out = io.StringIO()
            with redirect_stdout(out):
                gen_employers.main(fh.name)
        finally:
            os.unlink(fh.name)
        text = out.getvalue()
        self.assertLess(text.index('- company: "alpha"'), text.index('- company: "Zeta \\"Z\\" Co"'))
        self.assertIn('  board: "alpha"', text)


if __name__ == "__main__":
    unittest.main()

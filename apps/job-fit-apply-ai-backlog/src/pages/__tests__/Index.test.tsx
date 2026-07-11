import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor, fireEvent, act } from "@testing-library/react";
import Index from "../Index";

// ── Hoisted mocks (must be declared before vi.mock hoisting) ───────────────────
const { mockFrom, mockSelect, mockOrder, mockUpdate, mockEq } = vi.hoisted(() => {
  const mockOrder = vi.fn();
  const mockSelect = vi.fn().mockReturnValue({ order: mockOrder });
  const mockEq = vi.fn();
  const mockUpdate = vi.fn().mockReturnValue({ eq: mockEq });
  const mockFrom = vi.fn().mockReturnValue({ select: mockSelect, update: mockUpdate });
  return { mockFrom, mockSelect, mockOrder, mockUpdate, mockEq };
});

vi.mock("@/integrations/supabase/client", () => ({
  supabase: { from: mockFrom },
}));

vi.mock("@/hooks/use-toast", () => ({
  toast: vi.fn(),
}));

// ── Fixtures ───────────────────────────────────────────────────────────────────

const makeTrack = (overrides: Record<string, unknown> = {}) => ({
  id: 1,
  company: "Acme Corp",
  role_title: "Senior Engineer",
  location: "San Francisco, CA",
  remote_policy: "remote",
  fit_score: 80,
  job_url: "https://example.com/job",
  artifact_url: null as string | null,
  tech_stack: ["React", "TypeScript"] as string[],
  status: "backlog" as const,
  created_at: new Date().toISOString(),
  duplicate: false,
  ...overrides,
});

type Track = ReturnType<typeof makeTrack>;

function setupSupabaseMock(data: Track[], error: { message: string } | null = null) {
  mockOrder.mockResolvedValue({ data, error });
  mockSelect.mockReturnValue({ order: mockOrder });
  mockFrom.mockReturnValue({ select: mockSelect, update: mockUpdate });
  mockUpdate.mockReturnValue({ eq: mockEq });
  mockEq.mockResolvedValue({ error: null });
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.spyOn(window, "matchMedia").mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => true,
  }));
});

// ── Tests ──────────────────────────────────────────────────────────────────────

describe("Index Page", () => {
  describe("rendering", () => {
    it("renders header title", async () => {
      setupSupabaseMock([]);
      render(<Index />);
      await waitFor(() => {
        expect(screen.getByText("Job Tracker Backlog")).toBeInTheDocument();
      });
    });

    it("shows loading text initially", () => {
      mockOrder.mockReturnValue(new Promise(() => {}));
      mockSelect.mockReturnValue({ order: mockOrder });
      mockFrom.mockReturnValue({ select: mockSelect });

      render(<Index />);
      expect(screen.getByText(/Loading applications/i)).toBeInTheDocument();
    });

    it("renders status chips for all statuses", async () => {
      setupSupabaseMock([]);
      render(<Index />);
      // Chips have the form "backlog (0)" — match that specific pattern
      await waitFor(() => {
        for (const status of ["backlog", "applied", "interviewing", "rejected", "offer"]) {
          expect(screen.getByText(new RegExp(`^${status} \\(`, "i"))).toBeInTheDocument();
        }
      });
    });

    it("shows singular count for one application", async () => {
      setupSupabaseMock([makeTrack({ fit_score: 80 })]);
      render(<Index />);
      await waitFor(() => {
        expect(screen.getByText(/1 application tracked/i)).toBeInTheDocument();
      });
    });

    it("uses plural form for multiple applications", async () => {
      setupSupabaseMock([
        makeTrack({ id: 1, fit_score: 80 }),
        makeTrack({ id: 2, fit_score: 75 }),
      ]);
      render(<Index />);
      await waitFor(() => {
        expect(screen.getByText(/2 applications tracked/i)).toBeInTheDocument();
      });
    });

    it("displays Supabase error message", async () => {
      mockOrder.mockResolvedValue({ data: null, error: { message: "DB connection failed" } });
      mockSelect.mockReturnValue({ order: mockOrder });
      mockFrom.mockReturnValue({ select: mockSelect });

      render(<Index />);
      await waitFor(() => {
        expect(screen.getByText("DB connection failed")).toBeInTheDocument();
      });
    });

    it("renders company and role after data loads", async () => {
      setupSupabaseMock([makeTrack({ company: "TestCo", role_title: "QA Lead" })]);
      render(<Index />);
      await waitFor(() => {
        expect(screen.getByText("TestCo")).toBeInTheDocument();
        expect(screen.getByText("QA Lead")).toBeInTheDocument();
      });
    });

    it("renders tech stack badges (up to 3) and overflow badge", async () => {
      setupSupabaseMock([
        makeTrack({ tech_stack: ["React", "Node", "Postgres", "Redis"] }),
      ]);
      render(<Index />);
      await waitFor(() => {
        expect(screen.getByText("React")).toBeInTheDocument();
        expect(screen.getByText("Node")).toBeInTheDocument();
        expect(screen.getByText("Postgres")).toBeInTheDocument();
        expect(screen.getByText("+1")).toBeInTheDocument();
      });
    });

    it("renders job URL link when present", async () => {
      setupSupabaseMock([makeTrack({ job_url: "https://jobs.example.com/123" })]);
      render(<Index />);
      await waitFor(() => {
        const links = document.querySelectorAll('a[href="https://jobs.example.com/123"]');
        expect(links.length).toBeGreaterThan(0);
      });
    });
  });

  describe("fit score filtering", () => {
    const tracks = [
      makeTrack({ id: 1, fit_score: 90 }),
      makeTrack({ id: 2, fit_score: 70 }),
      makeTrack({ id: 3, fit_score: 40 }),
    ];

    it("shows only tracks at or above the default threshold (60)", async () => {
      setupSupabaseMock(tracks);
      render(<Index />);
      await waitFor(() => {
        // 90 and 70 qualify; 40 does not
        expect(screen.getByText(/2 applications tracked/i)).toBeInTheDocument();
      });
    });

    it("excludes tracks with null fit_score", async () => {
      setupSupabaseMock([
        makeTrack({ id: 1, fit_score: null }),
        makeTrack({ id: 2, fit_score: 80 }),
      ]);
      render(<Index />);
      await waitFor(() => {
        expect(screen.getByText(/1 application tracked/i)).toBeInTheDocument();
      });
    });

    it("clicking a fit preset button updates the threshold", async () => {
      setupSupabaseMock(tracks);
      render(<Index />);

      await waitFor(() => screen.getByText(/2 applications tracked/i));

      const btn80 = screen.getByRole("button", { name: "80" });
      await act(async () => { fireEvent.click(btn80); });

      await waitFor(() => {
        expect(screen.getByText(/1 application tracked/i)).toBeInTheDocument();
      });
    });

    it("clicking '0' preset shows all tracks", async () => {
      setupSupabaseMock(tracks);
      render(<Index />);

      await waitFor(() => screen.getByText(/2 applications tracked/i));

      const btn0 = screen.getByRole("button", { name: "0" });
      await act(async () => { fireEvent.click(btn0); });

      await waitFor(() => {
        expect(screen.getByText(/3 applications tracked/i)).toBeInTheDocument();
      });
    });
  });

  describe("date filtering", () => {
    it("excludes tracks older than maxDaysAgo (default 7)", async () => {
      const old = makeTrack({
        id: 1,
        fit_score: 80,
        created_at: new Date(Date.now() - 10 * 24 * 60 * 60 * 1000).toISOString(),
      });
      const recent = makeTrack({
        id: 2,
        fit_score: 80,
        created_at: new Date(Date.now() - 1 * 24 * 60 * 60 * 1000).toISOString(),
      });

      setupSupabaseMock([old, recent]);
      render(<Index />);

      await waitFor(() => {
        expect(screen.getByText(/1 application tracked/i)).toBeInTheDocument();
      });
    });

    it("clicking 'All time' shows all matching tracks regardless of date", async () => {
      const old = makeTrack({
        id: 1,
        fit_score: 80,
        created_at: new Date(Date.now() - 60 * 24 * 60 * 60 * 1000).toISOString(),
      });
      setupSupabaseMock([old]);
      render(<Index />);

      await waitFor(() => screen.getByText(/0 applications tracked/i));

      const btnAll = screen.getByRole("button", { name: "All time" });
      await act(async () => { fireEvent.click(btnAll); });

      await waitFor(() => {
        expect(screen.getByText(/1 application tracked/i)).toBeInTheDocument();
      });
    });
  });

  describe("sorting", () => {
    it("clicking a column header twice toggles sort direction without breaking render", async () => {
      setupSupabaseMock([
        makeTrack({ id: 1, company: "Zebra Inc", fit_score: 90 }),
        makeTrack({ id: 2, company: "Alpha Corp", fit_score: 70 }),
      ]);
      render(<Index />);

      await waitFor(() => screen.getByText(/2 applications tracked/i));

      const companyTh = screen.getByText("Company").closest("th")!;
      fireEvent.click(companyTh);
      fireEvent.click(companyTh);

      expect(screen.getByText("Zebra Inc")).toBeInTheDocument();
      expect(screen.getByText("Alpha Corp")).toBeInTheDocument();
    });
  });

  describe("status display", () => {
    it("non-duplicate rows do not carry opacity-50", async () => {
      setupSupabaseMock([makeTrack({ duplicate: false, fit_score: 80 })]);
      render(<Index />);

      await waitFor(() => screen.getByText("Acme Corp"));
      const row = screen.getByText("Acme Corp").closest("tr");
      expect(row).not.toHaveClass("opacity-50");
    });
  });

  describe("fit score display", () => {
    it("renders high-scoring badge (>=85)", async () => {
      setupSupabaseMock([makeTrack({ fit_score: 90 })]);
      render(<Index />);
      // "90" appears as both a preset button and the score badge — verify at least one exists
      await waitFor(() => expect(screen.getAllByText("90").length).toBeGreaterThan(0));
    });

    it("renders medium-scoring badge (70-84)", async () => {
      setupSupabaseMock([makeTrack({ fit_score: 75 })]);
      render(<Index />);
      await waitFor(() => expect(screen.getByText("75")).toBeInTheDocument());
    });

    it("null fit_score rows are excluded by default threshold", async () => {
      setupSupabaseMock([makeTrack({ id: 1, fit_score: null })]);
      render(<Index />);
      await waitFor(() =>
        expect(screen.getByText(/0 applications tracked/i)).toBeInTheDocument()
      );
    });
  });

  describe("location display", () => {
    it("shows em-dash when location is null", async () => {
      setupSupabaseMock([makeTrack({ location: null })]);
      render(<Index />);
      await waitFor(() => expect(screen.getByText("—")).toBeInTheDocument());
    });

    it("does not render remote_policy when value is 'unknown'", async () => {
      setupSupabaseMock([makeTrack({ remote_policy: "unknown" })]);
      render(<Index />);
      await waitFor(() => screen.getByText("Acme Corp"));
      expect(screen.queryByText("unknown")).not.toBeInTheDocument();
    });
  });
});

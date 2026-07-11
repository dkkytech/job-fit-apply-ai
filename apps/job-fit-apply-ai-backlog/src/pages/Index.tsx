import { useEffect, useState, useCallback, useMemo } from "react";
import { ChevronRight } from "lucide-react";
import { supabase } from "@/integrations/supabase/client";
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from "@/components/ui/table";
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from "@/components/ui/select";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { toast } from "@/hooks/use-toast";
import { ExternalLink, MapPin, Briefcase, ArrowUp, ArrowDown, ArrowUpDown } from "lucide-react";

const STATUS_OPTIONS = [
  "backlog", "duplicate", "applied", "interested", "skipped", "interviewing", "rejected", "offer",
] as const;

type Status = (typeof STATUS_OPTIONS)[number];

interface Track {
  id: number;
  company: string;
  role_title: string;
  location: string | null;
  remote_policy: string | null;
  fit_score: number | null;
  job_url: string | null;
  artifact_url: string | null;
  tech_stack: string[] | null;
  status: Status;
  created_at: string;
  duplicate: boolean;
}

const statusColors: Record<Status, string> = {
  backlog: "bg-[hsl(var(--status-backlog))] text-white",
  duplicate: "bg-muted text-muted-foreground",
  applied: "bg-[hsl(var(--status-applied))] text-white",
  interested: "bg-[hsl(var(--status-screening))] text-white",
  skipped: "bg-[hsl(var(--status-withdrawn))] text-white",
  interviewing: "bg-[hsl(var(--status-interviewing))] text-white",
  rejected: "bg-[hsl(var(--status-rejected))] text-white",
  offer: "bg-[hsl(var(--status-offer))] text-white",
};

type SortKey = "id" | "company" | "role_title" | "location" | "fit_score" | "status" | "created_at";
type SortDir = "asc" | "desc";

const Index = () => {
  const [allTracks, setAllTracks] = useState<Track[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [sortKey, setSortKey] = useState<SortKey>("fit_score");
  const [sortDir, setSortDir] = useState<SortDir>("desc");
  const [fitThreshold, setFitThreshold] = useState(60);
  const [maxDaysAgo, setMaxDaysAgo] = useState<number | null>(7);
  const [collapsedIds, setCollapsedIds] = useState<Set<number>>(new Set());

  const FIT_PRESETS = [0, 25, 50, 60, 70, 80, 90];

  const DAY_PRESETS: { label: string; value: number | null }[] = [
    { label: "1", value: 1 },
    { label: "7", value: 7 },
    { label: "30", value: 30 },
    { label: "90", value: 90 },
    { label: "All time", value: null },
  ];

  const tracks = useMemo(() => {
    return allTracks.filter((t) => {
      if (t.fit_score == null || t.fit_score < fitThreshold) return false;
      if (maxDaysAgo === null) return true;
      if (!t.created_at) return false;
      const cutoff = new Date();
      cutoff.setDate(cutoff.getDate() - maxDaysAgo);
      return new Date(t.created_at) >= cutoff;
    });
  }, [allTracks, fitThreshold, maxDaysAgo]);

  const toggleSort = (key: SortKey) => {
    if (sortKey === key) {
      setSortDir((d) => (d === "asc" ? "desc" : "asc"));
    } else {
      setSortKey(key);
      setSortDir("asc");
    }
  };

  const SortIcon = ({ col }: { col: SortKey }) => {
    if (sortKey !== col) return <ArrowUpDown className="h-3 w-3 ml-1 opacity-40" />;
    return sortDir === "asc"
      ? <ArrowUp className="h-3 w-3 ml-1" />
      : <ArrowDown className="h-3 w-3 ml-1" />;
  };

  const fetchTracks = useCallback(async () => {
    const { data, error } = await supabase
      .from("tracks")
      .select("id, company, role_title, location, remote_policy, fit_score, job_url, artifact_url, tech_stack, status, created_at, duplicate")
      .order("created_at", { ascending: false });

    if (error) {
      setError(error.message);
      return;
    }
    setAllTracks(((data as Track[]) ?? []).filter((t) => !t.duplicate && (t.status === "backlog" || t.status === "interested")));
    setLoading(false);
  }, []);

  useEffect(() => { fetchTracks(); }, [fetchTracks]);

  const updateStatus = async (id: number, newStatus: Status) => {
    const prev = allTracks;
    setAllTracks((t) => t.map((row) => (row.id === id ? { ...row, status: newStatus } : row)));

    // Collapse row if status is no longer backlog/interested
    if (newStatus !== "backlog" && newStatus !== "interested") {
      setCollapsedIds((s) => new Set(s).add(id));
    } else {
      setCollapsedIds((s) => { const next = new Set(s); next.delete(id); return next; });
    }

    const { error } = await supabase.from("tracks").update({ status: newStatus }).eq("id", id);
    if (error) {
      setAllTracks(prev);
      setCollapsedIds((s) => { const next = new Set(s); next.delete(id); return next; });
      toast({ title: "Update failed", description: error.message, variant: "destructive" });
    } else {
      toast({ title: "Status updated", description: `Changed to ${newStatus}` });
    }
  };

  const counts = tracks.reduce<Record<string, number>>((acc, t) => {
    acc[t.status] = (acc[t.status] || 0) + 1;
    return acc;
  }, {});

  const sortedTracks = useMemo(() => {
    return [...tracks].sort((a, b) => {
      const av = a[sortKey];
      const bv = b[sortKey];
      if (av == null && bv == null) return 0;
      if (av == null) return 1;
      if (bv == null) return -1;
      let cmp = 0;
      if (typeof av === "number" && typeof bv === "number") cmp = av - bv;
      else cmp = String(av).localeCompare(String(bv));
      return sortDir === "asc" ? cmp : -cmp;
    });
  }, [tracks, sortKey, sortDir]);

  return (
    <div className="min-h-screen bg-background">
      {/* Header */}
      <header className="border-b bg-card px-6 py-5">
        <h1 className="text-3xl font-bold tracking-tight text-foreground">
          Job Tracker Backlog
        </h1>
        <p className="text-muted-foreground mt-1">
          {tracks.length} application{tracks.length !== 1 ? "s" : ""} tracked
        </p>
      </header>

      <main className="max-w-[1400px] mx-auto px-6 py-6 space-y-6">
        {/* Status summary chips */}
        <div className="flex flex-wrap gap-2">
          {STATUS_OPTIONS.map((s) => (
            <Badge key={s} className={`${statusColors[s]} capitalize text-xs px-3 py-1`}>
              {s} ({counts[s] || 0})
            </Badge>
          ))}
        </div>

        <div className="flex flex-wrap items-center gap-8">
          <div className="flex items-center gap-2">
            <label className="text-sm font-medium text-foreground whitespace-nowrap">
              Min Fit Score:
            </label>
            <div className="flex gap-1">
              {FIT_PRESETS.map((preset) => (
                <button
                  key={preset}
                  onClick={() => setFitThreshold(preset)}
                  className={`px-3 py-1 rounded-full text-xs font-medium transition-colors ${
                    fitThreshold === preset
                      ? "bg-primary text-primary-foreground"
                      : "bg-muted text-muted-foreground hover:bg-muted/80"
                  }`}
                >
                  {preset}
                </button>
              ))}
            </div>
          </div>
          <div className="flex items-center gap-2">
            <label className="text-sm font-medium text-foreground whitespace-nowrap">
              Max Days Ago:
            </label>
            <div className="flex gap-1">
              {DAY_PRESETS.map((preset) => (
                <button
                  key={preset.label}
                  onClick={() => setMaxDaysAgo(preset.value)}
                  className={`px-3 py-1 rounded-full text-xs font-medium transition-colors ${
                    maxDaysAgo === preset.value
                      ? "bg-primary text-primary-foreground"
                      : "bg-muted text-muted-foreground hover:bg-muted/80"
                  }`}
                >
                  {preset.label}
                </button>
              ))}
            </div>
          </div>
        </div>

        {error && (
          <Card className="border-destructive">
            <CardContent className="p-4 text-destructive">{error}</CardContent>
          </Card>
        )}

        {loading ? (
          <p className="text-muted-foreground">Loading applications…</p>
        ) : (
          <Card>
            <CardContent className="p-0">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="w-[60px] cursor-pointer select-none" onClick={() => toggleSort("id")}>
                      <span className="flex items-center">ID<SortIcon col="id" /></span>
                    </TableHead>
                    <TableHead className="w-[200px] cursor-pointer select-none" onClick={() => toggleSort("company")}>
                      <span className="flex items-center">Company<SortIcon col="company" /></span>
                    </TableHead>
                    <TableHead className="cursor-pointer select-none" onClick={() => toggleSort("role_title")}>
                      <span className="flex items-center">Role<SortIcon col="role_title" /></span>
                    </TableHead>
                    <TableHead className="w-[130px] cursor-pointer select-none" onClick={() => toggleSort("location")}>
                      <span className="flex items-center">Location<SortIcon col="location" /></span>
                    </TableHead>
                    <TableHead className="w-[90px] text-center cursor-pointer select-none" onClick={() => toggleSort("fit_score")}>
                      <span className="flex items-center justify-center">Fit<SortIcon col="fit_score" /></span>
                    </TableHead>
                    <TableHead className="w-[160px] cursor-pointer select-none" onClick={() => toggleSort("status")}>
                      <span className="flex items-center">Status<SortIcon col="status" /></span>
                    </TableHead>
                    <TableHead className="w-[100px] cursor-pointer select-none" onClick={() => toggleSort("created_at")}>
                      <span className="flex items-center">Date<SortIcon col="created_at" /></span>
                    </TableHead>
                    <TableHead className="w-[50px]">Job</TableHead>
                    <TableHead className="w-[50px]">Report</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {sortedTracks.map((track) => {
                    const isCollapsed = collapsedIds.has(track.id);
                    return isCollapsed ? (
                      <TableRow
                        key={track.id}
                        className="opacity-50 cursor-pointer hover:opacity-70"
                        onClick={() => setCollapsedIds((s) => { const next = new Set(s); next.delete(track.id); return next; })}
                      >
                        <TableCell className="text-xs text-muted-foreground">
                          <ChevronRight className="h-3 w-3 inline" />
                        </TableCell>
                        <TableCell className="font-medium">{track.company}</TableCell>
                        <TableCell className="text-sm text-muted-foreground">{track.role_title}</TableCell>
                        <TableCell colSpan={6}>
                          <Badge className={`${statusColors[track.status]} capitalize text-xs px-2 py-0.5`}>
                            {track.status}
                          </Badge>
                        </TableCell>
                      </TableRow>
                    ) : (
                      <TableRow key={track.id} className={track.duplicate ? "opacity-50" : ""}>
                        <TableCell className="text-xs text-muted-foreground">{track.id}</TableCell>
                        <TableCell className="font-medium">{track.company}</TableCell>
                        <TableCell>
                          <div className="space-y-1">
                            <span className="block text-sm">{track.role_title}</span>
                            {track.tech_stack && track.tech_stack.length > 0 && (
                              <div className="flex flex-wrap gap-1">
                                {track.tech_stack.slice(0, 3).map((t) => (
                                  <Badge key={t} variant="secondary" className="text-[10px] px-1.5 py-0">
                                    {t}
                                  </Badge>
                                ))}
                                {track.tech_stack.length > 3 && (
                                  <Badge variant="secondary" className="text-[10px] px-1.5 py-0">
                                    +{track.tech_stack.length - 3}
                                  </Badge>
                                )}
                              </div>
                            )}
                          </div>
                        </TableCell>
                        <TableCell className="text-muted-foreground text-xs">
                          <span className="flex items-center gap-1">
                            <MapPin className="h-3 w-3" />
                            {track.location ?? "—"}
                          </span>
                          {track.remote_policy && track.remote_policy !== "unknown" && (
                            <span className="text-[10px] uppercase tracking-wider text-muted-foreground/70">
                              {track.remote_policy}
                            </span>
                          )}
                        </TableCell>
                        <TableCell className="text-center">
                          {track.fit_score != null && (
                            <span
                              className={`inline-block rounded-full px-2 py-0.5 text-xs font-semibold ${
                                track.fit_score >= 85
                                  ? "bg-[hsl(var(--status-offer)/0.15)] text-[hsl(var(--status-offer))]"
                                  : track.fit_score >= 70
                                  ? "bg-[hsl(var(--status-interviewing)/0.15)] text-[hsl(var(--status-interviewing))]"
                                  : "bg-muted text-muted-foreground"
                              }`}
                            >
                              {track.fit_score}
                            </span>
                          )}
                        </TableCell>
                        <TableCell>
                          <Select
                            value={track.status}
                            onValueChange={(v) => updateStatus(track.id, v as Status)}
                          >
                            <SelectTrigger className="h-8 text-xs w-[140px]">
                              <SelectValue />
                            </SelectTrigger>
                            <SelectContent>
                              {STATUS_OPTIONS.map((s) => (
                                <SelectItem key={s} value={s} className="capitalize text-xs">
                                  {s}
                                </SelectItem>
                              ))}
                            </SelectContent>
                          </Select>
                        </TableCell>
                        <TableCell className="text-xs text-muted-foreground">
                          {new Date(track.created_at).toLocaleDateString()}
                        </TableCell>
                        <TableCell>
                          {track.job_url && (
                            <a href={track.job_url} target="_blank" rel="noopener noreferrer"
                              className="text-muted-foreground hover:text-foreground transition-colors">
                              <ExternalLink className="h-4 w-4" />
                            </a>
                          )}
                        </TableCell>
                        <TableCell>
                          {track.artifact_url && (
                            <a href={`${track.artifact_url}report.md`} target="_blank" rel="noopener noreferrer"
                              className="text-muted-foreground hover:text-foreground transition-colors">
                              <ExternalLink className="h-4 w-4" />
                            </a>
                          )}
                        </TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
            </CardContent>
          </Card>
        )}
      </main>
    </div>
  );
};

export default Index;

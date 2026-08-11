import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { reducer, toast, useToast } from "../use-toast";

// ToasterToast / State are not exported from the module; derive them from the reducer signature.
type State = Parameters<typeof reducer>[0];
type ToasterToast = State["toasts"][number];

// A minimal toast object for reducer tests.
const mk = (id: string, extra: Partial<ToasterToast> = {}): ToasterToast => ({
  id,
  open: true,
  ...extra,
});

describe("use-toast reducer", () => {
  it("ADD_TOAST prepends and enforces TOAST_LIMIT of 1", () => {
    const afterFirst = reducer({ toasts: [] }, { type: "ADD_TOAST", toast: mk("1") });
    expect(afterFirst.toasts.map((t) => t.id)).toEqual(["1"]);

    // Adding a second toast evicts the first (limit = 1), keeping the newest at the front.
    const afterSecond = reducer(afterFirst, { type: "ADD_TOAST", toast: mk("2") });
    expect(afterSecond.toasts.map((t) => t.id)).toEqual(["2"]);
  });

  it("UPDATE_TOAST merges fields into the matching toast only", () => {
    const start = { toasts: [mk("1", { title: "old" }), mk("2", { title: "keep" })] };
    const next = reducer(start, { type: "UPDATE_TOAST", toast: { id: "1", title: "new" } });

    expect(next.toasts.find((t) => t.id === "1")?.title).toBe("new");
    expect(next.toasts.find((t) => t.id === "2")?.title).toBe("keep");
  });

  it("DISMISS_TOAST with an id closes only that toast", () => {
    const start = { toasts: [mk("1"), mk("2")] };
    const next = reducer(start, { type: "DISMISS_TOAST", toastId: "1" });

    expect(next.toasts.find((t) => t.id === "1")?.open).toBe(false);
    expect(next.toasts.find((t) => t.id === "2")?.open).toBe(true);
  });

  it("DISMISS_TOAST without an id closes every toast", () => {
    const start = { toasts: [mk("1"), mk("2")] };
    const next = reducer(start, { type: "DISMISS_TOAST" });

    expect(next.toasts.every((t) => t.open === false)).toBe(true);
  });

  it("REMOVE_TOAST with an id drops only that toast", () => {
    const start = { toasts: [mk("1"), mk("2")] };
    const next = reducer(start, { type: "REMOVE_TOAST", toastId: "1" });

    expect(next.toasts.map((t) => t.id)).toEqual(["2"]);
  });

  it("REMOVE_TOAST without an id clears all toasts", () => {
    const start = { toasts: [mk("1"), mk("2")] };
    const next = reducer(start, { type: "REMOVE_TOAST" });

    expect(next.toasts).toEqual([]);
  });
});

describe("toast() + useToast()", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    // Flush the pending REMOVE_TOAST timers and reset the shared in-memory state between tests.
    act(() => {
      vi.runOnlyPendingTimers();
    });
    vi.useRealTimers();
  });

  it("exposes a created toast through the hook and returns control handles", () => {
    const { result } = renderHook(() => useToast());

    let handle!: ReturnType<typeof toast>;
    act(() => {
      handle = toast({ title: "Saved", description: "All good" });
    });

    expect(result.current.toasts).toHaveLength(1);
    expect(result.current.toasts[0].title).toBe("Saved");
    expect(result.current.toasts[0].open).toBe(true);
    expect(typeof handle.dismiss).toBe("function");
    expect(typeof handle.update).toBe("function");
  });

  it("update() changes the live toast's content in place", () => {
    const { result } = renderHook(() => useToast());

    let handle!: ReturnType<typeof toast>;
    act(() => {
      handle = toast({ title: "Before" });
    });
    act(() => {
      handle.update({ id: handle.id, title: "After" } as ToasterToast);
    });

    expect(result.current.toasts[0].title).toBe("After");
  });

  it("dismiss() marks the toast closed", () => {
    const { result } = renderHook(() => useToast());

    let handle!: ReturnType<typeof toast>;
    act(() => {
      handle = toast({ title: "Bye" });
    });
    act(() => {
      handle.dismiss();
    });

    expect(result.current.toasts[0].open).toBe(false);
  });

  it("hook-level dismiss() with no id closes the active toast", () => {
    const { result } = renderHook(() => useToast());

    act(() => {
      toast({ title: "Active" });
    });
    act(() => {
      result.current.dismiss();
    });

    expect(result.current.toasts.every((t) => t.open === false)).toBe(true);
  });
});

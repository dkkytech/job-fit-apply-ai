import { defineConfig } from "vite";
import react from "@vitejs/plugin-react-swc";
import path from "path";

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => ({
  server: {
    host: process.env.VITE_DEV_HOST ?? "localhost",
    port: 3001,
    allowedHosts: process.env.VITE_DEV_HOST ? [".ts.net"] : undefined,
    hmr: {
      overlay: false,
    },
  },
  preview: {
    host: process.env.VITE_DEV_HOST ?? "localhost",
    port: 8080,
  },
  plugins: [react()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
    dedupe: ["react", "react-dom", "react/jsx-runtime", "react/jsx-dev-runtime", "@tanstack/react-query", "@tanstack/query-core"],
  },
}));

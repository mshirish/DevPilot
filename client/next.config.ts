import type { NextConfig } from "next";

/**
 * Production (Vercel): set BACKEND_URL to the Spring Boot server, e.g. https://devpilot-api.onrender.com.
 * The frontend then proxies the backend's paths, so the browser only ever talks to one domain.
 * That keeps the session cookie first-party, which is what makes login work without a custom domain.
 *
 * Local dev / docker compose: leave BACKEND_URL unset; the browser calls NEXT_PUBLIC_API_URL directly.
 */
const backendUrl = process.env.BACKEND_URL?.replace(/\/$/, "");

const nextConfig: NextConfig = {
  // Produces a minimal self-contained server for the Docker image
  output: "standalone",

  // With the proxy on, API calls are same-origin (empty base URL)
  env: backendUrl ? { NEXT_PUBLIC_API_URL: "" } : {},

  async rewrites() {
    if (!backendUrl) return [];
    return {
      beforeFiles: [
        { source: "/api/:path*", destination: `${backendUrl}/api/:path*` },
        // GitHub OAuth start + callback (Spring Security endpoints)
        { source: "/oauth2/:path*", destination: `${backendUrl}/oauth2/:path*` },
        { source: "/login/oauth2/:path*", destination: `${backendUrl}/login/oauth2/:path*` },
      ],
      afterFiles: [],
      fallback: [],
    };
  },
};

export default nextConfig;

import { readFileSync } from "fs";
import { dirname, join } from "path";
import { fileURLToPath } from "url";

const __dirname = dirname(fileURLToPath(import.meta.url));

const { version } = JSON.parse(readFileSync(join(__dirname, "package.json"), "utf8"));
let piVersion = "unknown";
try {
  const piPkgPath = join(__dirname, "node_modules/@earendil-works/pi-coding-agent/package.json");
  piVersion = JSON.parse(readFileSync(piPkgPath, "utf8")).version;
} catch {
  // package not found, use default
}

/** @type {import("next").NextConfig} */
const nextConfig = {
  serverExternalPackages: ["@earendil-works/pi-coding-agent", "@earendil-works/pi-ai"],
  output: "standalone",
  outputFileTracingRoot: __dirname,
  allowedDevOrigins: ["192.168.*.*", "axylinuxesc6562950372-1.tail78a984.ts.net"],
  async headers() {
    return [
      {
        source: "/",
        headers: [
          { key: "Cache-Control", value: "private, no-cache, max-age=0, must-revalidate" },
        ],
      },
    ];
  },
  env: {
    NEXT_PUBLIC_APP_VERSION: version,
    NEXT_PUBLIC_PI_VERSION: piVersion,
  },
};

export default nextConfig;

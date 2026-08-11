#!/usr/bin/env node
import { createHash } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import path from "node:path";

const [fixtureRootValue, mode = "aligned", portFileValue] = process.argv.slice(2);
if (!fixtureRootValue || !portFileValue) {
  throw new Error("usage: resource-alignment-fixture-server.mjs FIXTURE_ROOT MODE PORT_FILE");
}

const fixtureRoot = path.resolve(fixtureRootValue);
const portFile = path.resolve(portFileValue);
const resources = [
  ["service-manager", "0.3.4", "service-manager.tgz"],
  ["openhouse-control-plane", "1.0.1", "openhouse-control-plane.tgz"],
  ["openhouse-runtime", "0.1.0+pi.0.80.10", "runtime-aarch64.tgz"],
  ["wuyou", "0.1.0", "wuyou.tgz"],
  ["openhouse-web", "1.1.2", "openhouse-web.tgz"],
];
const localSetVersion = "2026.08.11.1";
const localSequence = 2026081101;
const localMembers = [];
const publicationResources = [];
const releases = [];
const archiveBodies = new Map();

const sha256 = (value) => createHash("sha256").update(value).digest("hex");

for (const [id, version, archive] of resources) {
  const body = Buffer.from(`fixture archive for ${id}@${version}\n`, "utf8");
  const digest = sha256(body);
  const archivePath = `assets/${archive}`;
  const metadataPath = `metadata/${id}.json`;
  const url = `/resources-v2/${encodeURIComponent(id)}/${encodeURIComponent(version)}/${archive}`;
  const metadata = {
    id,
    version,
    archive,
    compression: "gzip",
    abi: "arm64-v8a",
    size: body.length,
    sha256: digest,
    url,
    mirrors: [],
    minApkVersionCode: 126,
  };
  await mkdir(path.join(fixtureRoot, path.dirname(archivePath)), { recursive: true });
  await mkdir(path.join(fixtureRoot, path.dirname(metadataPath)), { recursive: true });
  await writeFile(path.join(fixtureRoot, archivePath), body);
  await writeFile(path.join(fixtureRoot, metadataPath), `${JSON.stringify(metadata, null, 2)}\n`);
  localMembers.push({ id, version, sha256: digest });
  publicationResources.push({ id, version, archivePath, metadataPath });
  releases.push(metadata);
  archiveBodies.set(url, body);
}

const localSet = {
  schema: 2,
  id: "openhouse-core-stack",
  version: localSetVersion,
  sequence: localSequence,
  abi: "arm64-v8a",
  minApkVersionCode: 126,
  resources: localMembers,
};
await writeFile(path.join(fixtureRoot, "resource-set.json"), `${JSON.stringify(localSet, null, 2)}\n`);
await writeFile(path.join(fixtureRoot, "publish-manifest.json"), `${JSON.stringify({
  schema: 2,
  market: "rescue",
  resources: publicationResources,
  resourceSet: { id: localSet.id, version: localSet.version, manifestPath: "resource-set.json" },
}, null, 2)}\n`);

const remoteSet = structuredClone(localSet);
if (mode === "remote-newer") {
  remoteSet.version = "2026.08.11.2";
  remoteSet.sequence += 1;
} else if (mode === "remote-older") {
  remoteSet.version = "2026.08.10.9";
  remoteSet.sequence -= 1;
} else if (mode === "set-version-mismatch") {
  remoteSet.version = "2026.08.11.different";
} else if (mode === "set-sha-mismatch") {
  remoteSet.resources[0].sha256 = "0".repeat(64);
}
const resourceSetCatalog = {
  id: remoteSet.id,
  latestVersion: remoteSet.version,
  versions: [remoteSet],
};
const catalogResources = resources.map(([id]) => {
  const release = structuredClone(releases.find((candidate) => candidate.id === id));
  if (mode === "untrusted-origin" && id === "wuyou") {
    release.url = "https://untrusted.invalid/wuyou.tgz";
  }
  return { id, latestVersion: release.version, versions: [release] };
});
if (mode === "missing-resource") {
  catalogResources.pop();
}

let catalogRequests = 0;
let resourceSetRequests = 0;
const server = createServer((request, response) => {
  const requestUrl = new URL(request.url ?? "/", "http://127.0.0.1");
  const json = (status, value) => {
    response.writeHead(status, { "content-type": "application/json" });
    response.end(JSON.stringify(value));
  };
  if (requestUrl.pathname === "/api/v2/resources") {
    catalogRequests += 1;
    if (mode === "transient-catalog" && catalogRequests === 1) {
      json(503, { error: "temporary fixture outage" });
      return;
    }
    const revision = mode === "changing-catalog" && catalogRequests > 1 ? "changed-revision" : "stable-revision";
    json(200, { schema: 2, generatedAt: "2026-08-11T00:00:00Z", revision, resources: catalogResources });
    return;
  }
  if (requestUrl.pathname === "/api/v2/resource-sets/openhouse-core-stack") {
    resourceSetRequests += 1;
    if (mode === "changing-set" && resourceSetRequests > 1) {
      json(200, { ...resourceSetCatalog, latestVersion: "changed-during-validation" });
    } else {
      json(200, resourceSetCatalog);
    }
    return;
  }
  const body = archiveBodies.get(requestUrl.pathname);
  if (body) {
    const output = mode === "corrupt-archive" && requestUrl.pathname.includes("/wuyou/")
      ? Buffer.concat([body, Buffer.from("corrupt")])
      : body;
    response.writeHead(200, { "content-type": "application/gzip", "content-length": output.length });
    response.end(output);
    return;
  }
  json(404, { error: "not found" });
});

server.listen(0, "127.0.0.1", async () => {
  const address = server.address();
  if (!address || typeof address === "string") throw new Error("fixture server has no TCP address");
  await writeFile(portFile, `${address.port}\n`);
});

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => server.close(() => process.exit(0)));
}

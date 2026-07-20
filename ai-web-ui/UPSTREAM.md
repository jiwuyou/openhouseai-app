# WuxianPi AI Web UI upstream

This directory was imported from `/root/projects/wuxianpi` at upstream commit
`734caf8`. The import intentionally includes the source worktree's pending
chat-default shell changes from `app/globals.css` and `components/AppShell.tsx`.

The pending backend change that seeds the built-in `wuxianpi` assistant is
represented here by the stable `DEFAULT_ASSISTANT_ID = "wuxianpi"` client
contract and its default-first selection behavior. Its server implementation
and backend-only tests belong to `runtime/wuxianpi-node`, not this browser-only
package.

Excluded from the import: `.git`, `node_modules`, `.next`, `dist`,
`tsconfig.tsbuildinfo`, `bun.lock`, server-only Next.js routes, and release
artifacts. This package is a Vite SPA and must not depend on a Next.js server.

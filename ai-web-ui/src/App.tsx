import { Suspense } from "react";
import { AppShell } from "@/components/AppShell";

export function App() {
  return (
    <Suspense fallback={<main className="wuxianpi-state">正在加载 WuxianPi AI…</main>}>
      <AppShell />
    </Suspense>
  );
}

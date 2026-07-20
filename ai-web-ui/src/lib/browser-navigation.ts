import { useCallback, useEffect, useMemo, useState } from "react";

export function useBrowserNavigation() {
  const [, setRevision] = useState(0);
  useEffect(() => {
    const refresh = () => setRevision((value) => value + 1);
    window.addEventListener("popstate", refresh);
    return () => window.removeEventListener("popstate", refresh);
  }, []);

  const replace = useCallback((href: string) => {
    window.history.replaceState(null, "", href);
    window.dispatchEvent(new PopStateEvent("popstate"));
  }, []);

  const searchParams = useMemo(() => new URLSearchParams(window.location.search), [window.location.search]);
  return { replace, searchParams };
}

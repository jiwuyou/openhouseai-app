export class LatestRequestGate {
  private version = 0;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private resolvePending: ((version: number | null) => void) | null = null;

  schedule(delayMs: number): Promise<number | null> {
    this.cancelPending();
    const version = ++this.version;
    return new Promise((resolve) => {
      this.resolvePending = resolve;
      this.timer = setTimeout(() => {
        this.timer = null;
        this.resolvePending = null;
        resolve(this.isCurrent(version) ? version : null);
      }, delayMs);
    });
  }

  invalidate(): void {
    this.version++;
    this.cancelPending();
  }

  isCurrent(version: number): boolean {
    return version === this.version;
  }

  private cancelPending(): void {
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    const resolve = this.resolvePending;
    this.resolvePending = null;
    resolve?.(null);
  }
}

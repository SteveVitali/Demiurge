// Spec §10.2: Browser lifecycle manager
// Launches Chromium once, creates fresh contexts per task, reuses browser process

import { chromium, Browser, BrowserContext, Page } from 'playwright';

export class BrowserManager {
  private browser: Browser | null = null;
  private activeContext: BrowserContext | null = null;
  private activePage: Page | null = null;

  async launch(): Promise<string> {
    if (this.browser && this.browser.isConnected()) {
      return this.browser.version();
    }
    this.browser = await chromium.launch({
      headless: true,
      args: ['--no-sandbox', '--disable-setuid-sandbox'],
    });
    process.stderr.write(`[worker] Browser launched: ${this.browser.version()}\n`);
    return this.browser.version();
  }

  async createContext(storageStatePath?: string): Promise<{ context: BrowserContext; page: Page }> {
    await this.closeActiveContext();
    if (!this.browser || !this.browser.isConnected()) {
      throw new Error('Browser not launched');
    }
    const contextOptions: Record<string, unknown> = {};
    if (storageStatePath) {
      try {
        const fs = await import('fs');
        if (fs.existsSync(storageStatePath)) {
          contextOptions.storageState = storageStatePath;
        }
      } catch {
        // If storage state file doesn't exist, create fresh context
      }
    }
    this.activeContext = await this.browser.newContext(contextOptions as any);
    this.activePage = await this.activeContext.newPage();
    return { context: this.activeContext, page: this.activePage };
  }

  async closeActiveContext(): Promise<void> {
    if (this.activeContext) {
      try {
        await this.activeContext.close();
      } catch {
        // Context may already be closed
      }
      this.activeContext = null;
      this.activePage = null;
    }
  }

  async saveStorageState(outputPath: string): Promise<void> {
    if (!this.activeContext) {
      throw new Error('No active browser context');
    }
    await this.activeContext.storageState({ path: outputPath });
  }

  getActivePage(): Page | null {
    return this.activePage;
  }

  getActiveContext(): BrowserContext | null {
    return this.activeContext;
  }

  isConnected(): boolean {
    return this.browser !== null && this.browser.isConnected();
  }

  async close(): Promise<void> {
    await this.closeActiveContext();
    if (this.browser) {
      try {
        await this.browser.close();
      } catch {
        // Browser may already be closed
      }
      this.browser = null;
    }
  }
}

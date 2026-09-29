import { DurableObject } from "cloudflare:workers";
import { DemoStore } from "./store.js";
import { createHandler } from "./server.js";

/** A deployment is one wallet tenant. The browser cannot choose the namespace. */
export class TenantDemo extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.queue = Promise.resolve();
    ctx.blockConcurrencyWhile(async () => {
      this.store = await DemoStore.open(ctx.storage);
      this.handle = createHandler(this.store, ctx.storage, env);
    });
  }

  fetch(request) {
    // Keep API calls and callbacks ordered, including across outbound wallet I/O.
    // Database transaction boundaries remain inside DemoStore.
    const result = this.queue.then(() => this.handle(request));
    this.queue = result.catch(() => {});
    return result;
  }
}

export default {
  async fetch(request, env) {
    const path = new URL(request.url).pathname;
    if (path === "/health" || path.startsWith("/api/") || path === "/webhooks/custody") {
      return env.TENANT.getByName("tenant-demo").fetch(request);
    }
    return env.ASSETS.fetch(request);
  }
};

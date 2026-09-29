import { DurableObject } from "cloudflare:workers";
import { timingSafeEqual } from "node:crypto";
import { DemoStore } from "../src/store.js";
import { registerStoreTests } from "./store-cases.js";
import { registerHttpTests } from "./http-cases.js";

// This entrypoint is deployed only in test mode. Production never imports it.
export class TestRunner extends DurableObject {
  async fetch(request) {
    const tests = [];
    registerStoreTests((name, run) => tests.push({ name, run }),
      () => DemoStore.open(this.ctx.storage));
    registerHttpTests((name, run) => tests.push({ name, run }),
      () => DemoStore.open(this.ctx.storage), this.ctx.storage);
    if (request.method === "GET") return Response.json(tests.map(t => t.name));
    const { index } = await request.json();
    if (!Number.isInteger(index) || !tests[index]) return new Response("Not found", { status: 404 });
    try {
      await tests[index].run();
      return Response.json({ ok: true, name: tests[index].name });
    } catch (error) {
      return Response.json({ ok: false, name: tests[index].name, error: error.message }, { status: 500 });
    } finally {
      await this.ctx.storage.deleteAll();
    }
  }
}

export default {
  fetch(request, env) {
    const actual = Buffer.from(request.headers.get("authorization") ?? "");
    const expected = Buffer.from(`Bearer ${env.TENANT_DEMO_SETUP_TOKEN}`);
    if (!env.TENANT_DEMO_SETUP_TOKEN || actual.length !== expected.length
        || !timingSafeEqual(actual, expected)) return new Response("Forbidden", { status: 403 });
    return env.TENANT.getByName(crypto.randomUUID()).fetch(request);
  }
};

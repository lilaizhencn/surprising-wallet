import { bindings, defineConfig, exports } from "cf/config";

export default defineConfig(({ mode }) => {
  const test = mode === "test";
  const preview = mode === "preview";
  const name = test ? "tenant-demo-tests" : preview ? "tenant-demo-preview" : "tenant-demo";
  const className = test ? "TestRunner" : "TenantDemo";
  return {
    accountId: "0fc085353257d1c837047e0dd87b1c8b",
    worker: {
      name,
      entrypoint: test ? "./test/remote-worker.js" : "./src/worker.js",
      compatibilityDate: "2026-09-29",
      workersDev: true,
      domains: test || preview ? [] : ["tenant-demo.tokdou.com"],
      assets: { runWorkerFirst: test ? true : ["/api/*", "/webhooks/*", "/health"] },
      exports: { [className]: exports.durableObject({ storage: "sqlite" }) },
      env: {
        ASSETS: bindings.assets(),
        TENANT: bindings.durableObject({ worker: name, exportName: className }),
        TENANT_DEMO_PUBLIC_BASE_URL: bindings.text("https://tenant-demo.tokdou.com"),
        WALLET_BASE_URL: bindings.text("https://custody-api.tokdou.com"),
        TENANT_DEMO_SETUP_TOKEN: bindings.secret(),
        ...(test ? {} : {
          WALLET_KEY_ID: bindings.secret(),
          WALLET_API_SECRET: bindings.secret(),
          WEBHOOK_SECRET: bindings.secret()
        })
      }
    }
  };
});

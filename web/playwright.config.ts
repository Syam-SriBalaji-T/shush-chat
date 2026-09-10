import { defineConfig, devices } from "@playwright/test";

/**
 * Drives the real app against a running stack, the way a person uses it -- no test hooks, no
 * injected state, nothing clicked by hand.
 *
 * This replaces the Selenium journey test that used to run inside `./mvnw verify`. That test
 * drove a single static HTML file the API served itself; with the frontend in its own
 * container there is nothing for the API's test suite to open. The trade is real and worth
 * saying plainly: `./mvnw verify` no longer covers the browser journey, and `npm test` here
 * does, against a stack that has to be up.
 */
export default defineConfig({
  testDir: "./tests",
  // A chat test drives two browsers against shared state; running files in parallel would have
  // them matching with each other's users.
  workers: 1,
  fullyParallel: false,
  timeout: 90_000,
  expect: { timeout: 20_000 },
  reporter: [["list"]],
  use: {
    baseURL: process.env.SHUSH_URL ?? "http://localhost:8081",
    trace: "retain-on-failure",
  },
  projects: [
    {
      name: "chromium",
      use: {
        ...devices["Desktop Chrome"],
        // A synthetic camera, so the capture path is exercised rather than mocked out.
        launchOptions: {
          args: ["--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream"],
        },
      },
    },
  ],
});

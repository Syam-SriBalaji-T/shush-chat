/**
 * Standalone output so the runtime image carries the server and its traced dependencies and
 * nothing else -- no node_modules tree, no source. Same reason the API image is two stages.
 */
const nextConfig = {
  output: "standalone",
  reactStrictMode: true,
  // No image optimisation server: chat images are presigned URLs from object storage, which
  // this process has no business fetching, resizing and caching.
  images: { unoptimized: true },
};

export default nextConfig;

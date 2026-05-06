import { defineConfig } from 'tsup';

export default defineConfig({
  entry: ['src/index.ts'],
  format: ['esm', 'cjs'],
  dts: true,
  sourcemap: true,
  clean: true,
  // Bundling viem would inflate the published tarball ~1 MB and stop
  // consumers from sharing a single viem instance with their host
  // app — keep it as a peer-friendly external dep.
  external: ['viem'],
  splitting: false,
  treeshake: true,
});

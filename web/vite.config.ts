import { defineConfig } from 'vite';

// The web client. `npm run build` writes web/dist (deployed to Firebase Hosting).
export default defineConfig({
  root: 'web',
  publicDir: 'public',
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    target: 'es2022',
    assetsInlineLimit: 0,
    rollupOptions: {
      output: {
        // three.js changes rarely: its own file stays cached across game updates
        manualChunks: { three: ['three'] },
      },
    },
  },
  server: { port: Number(process.env.WEB_PORT ?? 8000) },
});
